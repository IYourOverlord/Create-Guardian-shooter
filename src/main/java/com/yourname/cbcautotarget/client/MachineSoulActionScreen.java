package com.yourname.cbcautotarget.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.Tab;
import com.yourname.cbcautotarget.menu.MachineSoulActionMenu;
import com.yourname.cbcautotarget.network.SaveMachineSoulActionPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/** FIRE command radial control; Redstone Link inventory remains visible and usable. */
public class MachineSoulActionScreen extends BaseMachineSoulScreen<MachineSoulActionMenu> {
    private static final int GUI_H=172;
    public MachineSoulActionScreen(MachineSoulActionMenu menu, Inventory inv, Component title){super(menu,inv,title,Tab.ACTION,menu.blockPos,GUI_H);}
    @Override protected void renderSlot(GuiGraphics g, Slot s) { if (s.index < MachineSoulActionMenu.FREQ_SLOTS) return; super.renderSlot(g,s); }
    @Override protected int getInvYBase(){return MachineSoulActionMenu.INV_Y_BASE;}
    @Override protected int getInvX(){return MachineSoulActionMenu.INV_X;}
    @Override protected void renderContent(GuiGraphics g,int lx,int ty,int mx,int my){
        drawSaveButton(g,lx,ty,mx,my); int x=lx+150,y=ty+55; boolean h=mx>=x&&mx<x+70&&my>=y&&my<y+22;
        g.fill(x,y,x+70,y+22,h?COL_SAVE_HOVER_BG:COL_SAVE_BG);drawBorder(g,x,y,70,22,h?COL_ACCENT2:COL_SAVE_BORDER);g.drawCenteredString(font,"FIRE",x+35,y+7,h?COL_ACCENT2:COL_TEXT);
        if(h)g.renderTooltip(font,Component.literal("Assign Redstone Link frequencies to the FIRE command"),mx,my); g.drawString(font,Component.literal("LINK A   LINK B"),lx+175,ty+103,COL_TEXT_DIM,false); renderGhostItems(g,mx,my);
    }
    private void renderGhostItems(GuiGraphics g,int mx,int my){for(int i=0;i<2;i++){Slot s=menu.slots.get(i);ItemStack st=s.getItem();if(st.isEmpty())continue;int x=leftPos+175+i*22,y=topPos+112;RenderSystem.enableBlend();RenderSystem.setShaderColor(1,1,1,.5f);g.renderItem(st,x,y);RenderSystem.setShaderColor(1,1,1,1);RenderSystem.disableBlend();g.fill(x,y,x+16,y+16,COL_GHOST_OVERLAY);}}
    @Override public boolean mouseClicked(double mx,double my,int b){for(int i=0;i<2;i++){int x=leftPos+175+i*22,y=topPos+112;if(mx>=x&&mx<x+16&&my>=y&&my<y+16){ItemStack c=menu.getCarried();menu.setFreqItem(i,(b==1||c.isEmpty())?ItemStack.EMPTY:c);return true;}}return super.mouseClicked(mx,my,b);}
    @Override protected boolean onSaveClicked(){PacketDistributor.sendToServer(new SaveMachineSoulActionPacket(blockPos,menu.getFreqItem(0).copy(),menu.getFreqItem(1).copy()));onClose();return true;}
}
