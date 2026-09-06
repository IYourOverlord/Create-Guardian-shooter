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
    @Override protected void renderSlot(GuiGraphics g, Slot s) { if (s.index < MachineSoulMoveMenu.FREQ_SLOTS) return; super.renderSlot(g,s); }
    @Override protected int getInvYBase() { return MachineSoulMoveMenu.INV_Y_BASE; }
    @Override protected int getInvX() { return MachineSoulMoveMenu.INV_X; }
    @Override protected void renderContent(GuiGraphics g, int lx, int ty, int mx, int my) {
        drawSaveButton(g,lx,ty,mx,my);
        for (int i=0;i<MachineSoulMoveMenu.MOVE_ROLES.length;i++) {
            int[] bx={42,128,146,128,42,18}; int[] by={24,32,68,100,116,68}; int x=lx+bx[i], y=ty+by[i];
            boolean h=mx>=x&&mx<x+54&&my>=y&&my<y+20;
            g.fill(x,y,x+54,y+20,h||selected==i?COL_SAVE_HOVER_BG:COL_SAVE_BG);
            drawBorder(g,x,y,54,20,h||selected==i?COL_ACCENT2:COL_SAVE_BORDER);
            g.drawCenteredString(font, shortName(MachineSoulMoveMenu.MOVE_ROLES[i]),x+27,y+6,h||selected==i?COL_ACCENT2:COL_TEXT);
            if (h) g.renderTooltip(font, Component.literal("Assign two Redstone Link frequencies to this command"),mx,my);
        }
        if(selected>=0){int sy=ty+140;g.drawString(font,Component.literal("LINK A     LINK B"),lx+145,sy-11,COL_TEXT_DIM,false);}
        renderGhostItems(g,mx,my);
    }
    private static String shortName(CommandRole r) { return switch(r) { case MOVE_FORWARD->"FWD"; case MOVE_BACKWARD->"BACK"; case MOVE_LEFT->"LEFT"; case MOVE_RIGHT->"RIGHT"; case MOVE_UP->"UP"; case MOVE_DOWN->"DOWN"; default->r.name(); }; }
    private void renderGhostItems(GuiGraphics g,int mx,int my){ if(selected<0)return; for(int j=0;j<2;j++){int i=selected*2+j;ItemStack st=menu.slots.get(i).getItem();int x=leftPos+145+j*22,y=topPos+140;if(st.isEmpty())continue;RenderSystem.enableBlend();RenderSystem.setShaderColor(1,1,1,.65f);g.renderItem(st,x,y);RenderSystem.setShaderColor(1,1,1,1);RenderSystem.disableBlend();g.fill(x,y,x+16,y+16,COL_GHOST_OVERLAY);}}
    @Override public boolean mouseClicked(double mx,double my,int b) { if(b==0) for(int i=0;i<6;i++){int[] bx={42,128,146,128,42,18}; int[] by={24,32,68,100,116,68}; int x=leftPos+bx[i],y=topPos+by[i];if(mx>=x&&mx<x+54&&my>=y&&my<y+20){selected=i;return true;}} for(int i=0;i<MachineSoulMoveMenu.FREQ_SLOTS;i++){int x=leftPos+145+(i%2)*22,y=topPos+140;if(selected>=0&&i/2==selected&&mx>=x&&mx<x+16&&my>=y&&my<y+16){ItemStack c=menu.getCarried();menu.setFreqItem(i,(b==1||c.isEmpty())?ItemStack.EMPTY:c);return true;}} return super.mouseClicked(mx,my,b); }
    @Override protected boolean onSaveClicked(){Map<CommandRole,ItemStack[]> m=new LinkedHashMap<>();for(int i=0;i<6;i++)m.put(MachineSoulMoveMenu.MOVE_ROLES[i],new ItemStack[]{menu.getFreqItem(i*2).copy(),menu.getFreqItem(i*2+1).copy()});PacketDistributor.sendToServer(new SaveMachineSoulMovePacket(blockPos,m));onClose();return true;}
}
