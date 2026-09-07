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

/** FIRE frequency cells around the guardian; inventory is kept below the model. */
public class MachineSoulActionScreen extends BaseMachineSoulScreen<MachineSoulActionMenu>{
    private static final int GUI_H=235;
    private static final int CELL_X=218,CELL_Y=78;
    public MachineSoulActionScreen(MachineSoulActionMenu m,Inventory i,Component t){super(m,i,t,Tab.ACTION,m.blockPos,GUI_H);}
    @Override protected int getInvYBase(){return MachineSoulActionMenu.INV_Y_BASE;}
    @Override protected int getInvX(){return MachineSoulActionMenu.INV_X;}
    @Override protected void renderContent(GuiGraphics g,int lx,int ty,int mx,int my){
        int x=lx+CELL_X,y=ty+CELL_Y;g.drawCenteredString(font,"FIRE",x+19,y-10,COL_TEXT_DIM);drawSlotBg(g,x,y);drawSlotBg(g,x+22,y);
        if(mx>=x&&mx<x+38&&my>=y&&my<y+18)g.renderTooltip(font,Component.literal("FIRE Redstone Link frequencies"),mx,my);
        renderGhostItems(g,lx,ty,mx,my);
    }

    private void renderGhostItems(GuiGraphics g,int lx,int ty,int mx,int my){
        for (int i=0;i<MachineSoulActionMenu.FREQ_SLOTS;i++){
            Slot slot=menu.slots.get(i);
            ItemStack stack=slot.getItem();
            if (stack.isEmpty()) continue;

            int x=lx+(i==0?MachineSoulActionMenu.FREQ_X0:MachineSoulActionMenu.FREQ_X1);
            int y=ty+MachineSoulActionMenu.FIRE_SLOT_Y;

            boolean hov = mx>=x && mx<x+16 && my>=y && my<y+16;
            if (hov) g.fill(x, y, x+16, y+16, COL_GHOST_HOVER);

            RenderSystem.enableBlend();
            RenderSystem.setShaderColor(1f, 1f, 1f, 0.5f);
            g.renderItem(stack, x, y);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.disableBlend();
            g.fill(x, y, x+16, y+16, COL_GHOST_OVERLAY);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (int i=0;i<MachineSoulActionMenu.FREQ_SLOTS;i++){
            int x=leftPos+(i==0?MachineSoulActionMenu.FREQ_X0:MachineSoulActionMenu.FREQ_X1);
            int y=topPos+MachineSoulActionMenu.FIRE_SLOT_Y;
            if (mx>=x && mx<x+16 && my>=y && my<y+16){
                ItemStack carried = menu.getCarried();
                menu.setFreqItem(i, (button==1 || carried.isEmpty()) ? ItemStack.EMPTY : carried);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override protected boolean onSaveClicked(){PacketDistributor.sendToServer(new SaveMachineSoulActionPacket(blockPos,menu.getFreqItem(0).copy(),menu.getFreqItem(1).copy()));onClose();return true;}
}