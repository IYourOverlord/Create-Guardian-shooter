package com.yourname.cbcautotarget.client;

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
    }
    @Override protected boolean onSaveClicked(){PacketDistributor.sendToServer(new SaveMachineSoulActionPacket(blockPos,menu.slots.get(0).getItem().copy(),menu.slots.get(1).getItem().copy()));onClose();return true;}
}
