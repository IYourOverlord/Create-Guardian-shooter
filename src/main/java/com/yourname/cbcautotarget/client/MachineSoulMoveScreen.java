package com.yourname.cbcautotarget.client;

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

/** Movement frequency cells arranged around the fixed guardian. */
public class MachineSoulMoveScreen extends BaseMachineSoulScreen<MachineSoulMoveMenu> {
    private static final int GUI_H=235;
    // Six pairs: upper-left, upper-right, middle-left, middle-right, lower-left, lower-right.
    private static final int[] CELL_X={28,218,12,234,28,218};
    private static final int[] CELL_Y={30,30,78,78,126,126};
    private static final String[] LABELS={"FORWARD","BACKWARD","LEFT","RIGHT","UP","DOWN"};
    public MachineSoulMoveScreen(MachineSoulMoveMenu m,Inventory i,Component t){super(m,i,t,Tab.MOVEMENT,m.blockPos,GUI_H);}
    @Override protected void renderSlot(GuiGraphics g,Slot s){if(s.index<MachineSoulMoveMenu.FREQ_SLOTS)return;super.renderSlot(g,s);}
    @Override protected int getInvYBase(){return MachineSoulMoveMenu.INV_Y_BASE;}
    @Override protected int getInvX(){return MachineSoulMoveMenu.INV_X;}
    @Override protected void renderContent(GuiGraphics g,int lx,int ty,int mx,int my){
        for(int r=0;r<6;r++){int x=lx+CELL_X[r],y=ty+CELL_Y[r];
            g.drawCenteredString(font,LABELS[r],x+19,y-10,COL_TEXT_DIM);
            drawSlotBg(g,x,y);drawSlotBg(g,x+22,y);
            for(int j=0;j<2;j++){ItemStack st=menu.slots.get(r*2+j).getItem();if(!st.isEmpty())g.renderItem(st,x+j*22,y);}
            if(mx>=x&&mx<x+38&&my>=y&&my<y+18)g.renderTooltip(font,Component.literal(LABELS[r]+" Redstone Link frequencies"),mx,my);
        }
        // Navigation is drawn by BaseMachineSoulScreen at header level.
    }
    @Override public boolean mouseClicked(double mx,double my,int b){
        for(int r=0;r<6;r++){int x=leftPos+CELL_X[r],y=topPos+CELL_Y[r];for(int j=0;j<2;j++)if(mx>=x+j*22&&mx<x+j*22+18&&my>=y&&my<y+18){int id=r*2+j;ItemStack carried=menu.getCarried();menu.setFreqItem(id,(b==1||carried.isEmpty())?ItemStack.EMPTY:carried);return true;}}
        return super.mouseClicked(mx,my,b);
    }
    @Override protected boolean onSaveClicked(){Map<CommandRole,ItemStack[]> map=new LinkedHashMap<>();for(int r=0;r<6;r++)map.put(MachineSoulMoveMenu.MOVE_ROLES[r],new ItemStack[]{menu.getFreqItem(r*2).copy(),menu.getFreqItem(r*2+1).copy()});PacketDistributor.sendToServer(new SaveMachineSoulMovePacket(blockPos,map));onClose();return true;}
}
