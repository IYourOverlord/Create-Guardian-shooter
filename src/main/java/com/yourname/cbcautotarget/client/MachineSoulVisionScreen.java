package com.yourname.cbcautotarget.client;

import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.Tab;
import com.yourname.cbcautotarget.menu.MachineSoulVisionMenu;
import com.yourname.cbcautotarget.network.SaveMachineSoulVisionPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.network.PacketDistributor;

/** Vision: three editable numeric fields and compact controls around the guardian. */
public class MachineSoulVisionScreen extends BaseMachineSoulScreen<MachineSoulVisionMenu> {
    private static final int GUI_H=155;
    private EditBox radius, keep, still;
    private int selected=-1;
    public MachineSoulVisionScreen(MachineSoulVisionMenu m, Inventory i, Component t){super(m,i,t,Tab.VISION,m.blockPos,GUI_H);}
    @Override protected int getInvYBase(){return 0;}
    @Override protected boolean isInventoryHidden(){return true;}
    @Override protected void renderSlot(GuiGraphics g, Slot s){}
    @Override protected void init(){super.init(); radius=make("Radius",menu.getDetectionRadius(),178,40);keep=make("Keep",menu.getKeepDistance(),178,76);still=make("Still",menu.getStandStillDistance(),178,112);addRenderableWidget(radius);addRenderableWidget(keep);addRenderableWidget(still);}
    private EditBox make(String label,int value,int x,int y){EditBox b=new EditBox(font,leftPos+x,topPos+y,58,17,Component.literal(label));b.setValue(Integer.toString(value));b.setFilter(v->v.isEmpty()||v.matches("\\d{0,3}"));b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(label+" distance in blocks")));return b;}
    @Override protected void renderContent(GuiGraphics g,int lx,int ty,int mx,int my){
        drawSaveButton(g,lx,ty,mx,my);
        button(g,lx+28,ty+34,0,"RADIUS",mx,my); button(g,lx+128,ty+42,1,"KEEP",mx,my); button(g,lx+30,ty+104,2,"STILL",mx,my);
        g.drawString(font,"Radius",lx+178,ty+31,COL_TEXT_DIM,false);g.drawString(font,"Keep distance",lx+178,ty+67,COL_TEXT_DIM,false);g.drawString(font,"Stand still",lx+178,ty+103,COL_TEXT_DIM,false);
    }
    private void button(GuiGraphics g,int x,int y,int id,String text,int mx,int my){boolean h=mx>=x&&mx<x+64&&my>=y&&my<y+22;g.fill(x,y,x+64,y+22,h||selected==id?COL_SAVE_HOVER_BG:COL_SAVE_BG);drawBorder(g,x,y,64,22,h||selected==id?COL_ACCENT2:COL_SAVE_BORDER);g.drawCenteredString(font,text,x+32,y+7,h||selected==id?COL_ACCENT2:COL_TEXT);if(h)g.renderTooltip(font,Component.literal(text+" setting"),mx,my);}
    @Override public boolean mouseClicked(double x,double y,int b){if(b==0){if(hit(x,y,28,34)){selected=0;radius.setFocused(true);radius.setCursorPosition(radius.getValue().length());return true;}if(hit(x,y,128,42)){selected=1;keep.setFocused(true);keep.setCursorPosition(keep.getValue().length());return true;}if(hit(x,y,30,104)){selected=2;still.setFocused(true);still.setCursorPosition(still.getValue().length());return true;}}return super.mouseClicked(x,y,b);}
    private boolean hit(double x,double y,int bx,int by){return x>=leftPos+bx&&x<leftPos+bx+64&&y>=topPos+by&&y<topPos+by+22;}
    private int val(EditBox b,int lo,int hi,int fallback){try{return Math.max(lo,Math.min(hi,Integer.parseInt(b.getValue())));}catch(Exception e){return fallback;}}
    @Override protected boolean onSaveClicked(){int r=val(radius,MachineSoulBlockEntity.MIN_DETECTION_RADIUS,MachineSoulBlockEntity.MAX_DETECTION_RADIUS,menu.getDetectionRadius());int k=val(keep,MachineSoulBlockEntity.MIN_KEEP_DISTANCE,MachineSoulBlockEntity.MAX_KEEP_DISTANCE,menu.getKeepDistance());int s=val(still,MachineSoulBlockEntity.MIN_STAND_STILL_DISTANCE,MachineSoulBlockEntity.MAX_STAND_STILL_DISTANCE,menu.getStandStillDistance());PacketDistributor.sendToServer(new SaveMachineSoulVisionPacket(blockPos,r,k,s));onClose();return true;}
}
