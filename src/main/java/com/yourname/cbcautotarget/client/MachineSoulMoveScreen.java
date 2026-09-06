package com.yourname.cbcautotarget.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.CommandRole;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.Tab;
import com.yourname.cbcautotarget.menu.MachineSoulMoveMenu;
import com.yourname.cbcautotarget.network.SaveMachineSoulMovePacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.LinkedHashMap;
import java.util.Map;

/** Movement commands arranged as compact radial controls; inventory stays functional. */
public class MachineSoulMoveScreen extends BaseMachineSoulScreen<MachineSoulMoveMenu> {
    private static final int GUI_H = 235;
    private int selected = -1;
    public MachineSoulMoveScreen(MachineSoulMoveMenu menu, Inventory inv, Component title) { super(menu, inv, title, Tab.MOVEMENT, menu.blockPos, GUI_H); }
    @Override protected int getInvYBase() { return MachineSoulMoveMenu.INV_Y_BASE; }
    @Override protected int getInvX() { return MachineSoulMoveMenu.INV_X; }
    @Override protected void renderContent(GuiGraphics g, int lx, int ty, int mx, int my) {
        drawSaveButton(g,lx,ty,mx,my);
        for (int i=0;i<MachineSoulMoveMenu.MOVE_ROLES.length;i++) {
            int x=lx+135+(i%2)*58, y=ty+28+(i/2)*28;
            boolean h=mx>=x&&mx<x+54&&my>=y&&my<y+20;
            g.fill(x,y,x+54,y+20,h||selected==i?COL_SAVE_HOVER_BG:COL_SAVE_BG);
            drawBorder(g,x,y,54,20,h||selected==i?COL_ACCENT2:COL_SAVE_BORDER);
            g.drawCenteredString(font, shortName(MachineSoulMoveMenu.MOVE_ROLES[i]),x+27,y+6,h||selected==i?COL_ACCENT2:COL_TEXT);
            if (h) g.renderTooltip(font, Component.literal("Assign two Redstone Link frequencies to this command"),mx,my);
        }
        g.drawString(font,Component.literal("Redstone Link slots"),lx+135,ty+91,COL_TEXT_DIM,false);
        renderGhostItems(g,mx,my);
    }
    private static String shortName(CommandRole r) { return switch(r) { case MOVE_FORWARD->"FWD"; case MOVE_BACKWARD->"BACK"; case MOVE_LEFT->"LEFT"; case MOVE_RIGHT->"RIGHT"; case MOVE_UP->"UP"; case MOVE_DOWN->"DOWN"; default->r.name(); }; }
    private void renderGhostItems(GuiGraphics g,int mx,int my) { for(int i=0;i<MachineSoulMoveMenu.FREQ_SLOTS;i++){ Slot s=menu.slots.get(i); ItemStack st=s.getItem(); if(st.isEmpty())continue; int x=leftPos+((i%2==0)?MachineSoulMoveMenu.FREQ_X0:MachineSoulMoveMenu.FREQ_X1), y=topPos+MachineSoulMoveMenu.slotY(i/2); if(mx>=x&&mx<x+16&&my>=y&&my<y+16)g.fill(x,y,x+16,y+16,COL_GHOST_HOVER); RenderSystem.enableBlend(); RenderSystem.setShaderColor(1,1,1,.5f); g.renderItem(st,x,y); RenderSystem.setShaderColor(1,1,1,1); RenderSystem.disableBlend(); g.fill(x,y,x+16,y+16,COL_GHOST_OVERLAY); } }
    @Override public boolean mouseClicked(double mx,double my,int b) { if(b==0) for(int i=0;i<6;i++){int x=leftPos+135+(i%2)*58,y=topPos+28+(i/2)*28;if(mx>=x&&mx<x+54&&my>=y&&my<y+20){selected=i;return true;}} for(int i=0;i<MachineSoulMoveMenu.FREQ_SLOTS;i++){int x=leftPos+((i%2==0)?MachineSoulMoveMenu.FREQ_X0:MachineSoulMoveMenu.FREQ_X1),y=topPos+MachineSoulMoveMenu.slotY(i/2);if(mx>=x&&mx<x+16&&my>=y&&my<y+16){ItemStack c=menu.getCarried();menu.setFreqItem(i,(b==1||c.isEmpty())?ItemStack.EMPTY:c);return true;}} return super.mouseClicked(mx,my,b); }
    @Override protected boolean onSaveClicked(){Map<CommandRole,ItemStack[]> m=new LinkedHashMap<>();for(int i=0;i<6;i++)m.put(MachineSoulMoveMenu.MOVE_ROLES[i],new ItemStack[]{menu.getFreqItem(i*2).copy(),menu.getFreqItem(i*2+1).copy()});PacketDistributor.sendToServer(new SaveMachineSoulMovePacket(blockPos,m));onClose();return true;}
}
