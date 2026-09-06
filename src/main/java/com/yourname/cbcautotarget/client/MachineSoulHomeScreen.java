package com.yourname.cbcautotarget.client;

import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.Tab;
import com.yourname.cbcautotarget.menu.MachineSoulHomeMenu;
import com.yourname.cbcautotarget.network.SwitchMachineSoulTabPacket;
import com.yourname.cbcautotarget.network.ToggleMachineSoulSearchPacket;
import com.yourname.cbcautotarget.network.ToggleMachineSoulSubLevelOnlyPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/** Transparent radial hub for Machine Soul. */
public class MachineSoulHomeScreen extends AbstractContainerScreen<MachineSoulHomeMenu> {
    private static final int W=340, H=220, CX=170, CY=112, BUTTON_W=64, BUTTON_H=18;
    private final BlockPos blockPos;
    private boolean searchActive, subLevel;
    private float guardianYaw,guardianPitch;
    private int introTicks;
    private record Card(Tab tab,String label,String tip) {}
    private static final Card[] CARDS={
            new Card(Tab.VISION,"VISION","Detection radius and distances"),
            new Card(Tab.MOVEMENT,"MOVE","Six movement Redstone Link commands"),
            new Card(Tab.ACTION,"FIRE","FIRE Redstone Link command"),
            new Card(Tab.TARGET,"TARGET","Player and commander targeting")};

    public MachineSoulHomeScreen(MachineSoulHomeMenu menu, Inventory inv, Component title){
        super(menu,inv,title);blockPos=menu.blockPos;searchActive=menu.isTargetSearchActive();subLevel=menu.isRequireSubLevel();imageWidth=W;imageHeight=H;inventoryLabelY=H+500;titleLabelY=H+500;
    }
    @Override protected void renderBg(GuiGraphics g,float pt,int mx,int my){
        int lx=leftPos,ty=topPos;
        float target=-Mth.clamp(((mx-(lx+CX))/70f)*45f,-45f,45f);
        float targetPitch=Mth.clamp(((my-(ty+CY))/70f)*30f,-30f,30f);
        if(introTicks<12){introTicks++;guardianYaw=Mth.lerp(introTicks/12f,0f,target<0?-45f:45f);}else guardianYaw=Mth.lerp(.10f,guardianYaw,target);
        guardianPitch=Mth.lerp(.10f,guardianPitch,targetPitch);
        MachineSoulGuiSupport.renderGuardian(g,MachineSoulGuiSupport.guardian(),lx+CX,ty+CY,21,guardianYaw,guardianPitch);
        String title=Component.translatable("gui.cbc_autotarget.home.title").getString();g.drawCenteredString(font,title,lx+W/2,ty+6,0xFFE7F1F4);
        g.drawCenteredString(font,Component.literal("MACHINE SOUL").getString(),lx+CX,ty+24,0xFF71838A);
        drawToggle(g,lx+CX-32,ty+42,searchActive,"SEARCH",mx,my);
        drawToggle(g,lx+CX+38,ty+42,subLevel,"SHIP",mx,my);
        int[][] pos={{20,62},{256,62},{256,142},{20,142}};
        for(int i=0;i<CARDS.length;i++){int x=lx+pos[i][0],y=ty+pos[i][1];boolean h=inside(mx,my,x,y,BUTTON_W,BUTTON_H);g.fill(x,y,x+BUTTON_W,y+BUTTON_H,h?0xFF315660:0xFF263A42);BaseMachineSoulScreen.drawBorder(g,x,y,BUTTON_W,BUTTON_H,h?0xFF8DE8F2:0xFF57C9D9);g.drawCenteredString(font,CARDS[i].label,x+BUTTON_W/2,y+5,h?0xFF8DE8F2:0xFFD7F7FA);if(h)g.renderTooltip(font,Component.literal(CARDS[i].tip),mx,my);}
    }
    private void drawToggle(GuiGraphics g,int x,int y,boolean on,String text,int mx,int my){boolean h=inside(mx,my,x,y,64,18);g.fill(x,y,x+64,y+18,on?0xFF234B43:0xFF263A42);BaseMachineSoulScreen.drawBorder(g,x,y,64,18,h?0xFF8DE8F2:0xFF57C9D9);g.drawCenteredString(font,text+(on?" ON":" OFF"),x+32,y+5,on?0xFF8DE8F2:0xFFB8C9CE);}
    private boolean inside(double mx,double my,int x,int y,int w,int h){return mx>=x&&mx<x+w&&my>=y&&my<y+h;}
    @Override public boolean mouseClicked(double mx,double my,int b){if(b!=0)return super.mouseClicked(mx,my,b);int x=(int)mx,y=(int)my;
        if(inside(x,y,leftPos+CX-32,topPos+42,64,18)){searchActive=!searchActive;menu.setTargetSearchActive(searchActive);PacketDistributor.sendToServer(new ToggleMachineSoulSearchPacket(blockPos));return true;}
        if(inside(x,y,leftPos+CX+38,topPos+42,64,18)){subLevel=!subLevel;menu.setRequireSubLevel(subLevel);PacketDistributor.sendToServer(new ToggleMachineSoulSubLevelOnlyPacket(blockPos));return true;}
        int[][] pos={{20,62},{256,62},{256,142},{20,142}};for(int i=0;i<CARDS.length;i++)if(inside(x,y,leftPos+pos[i][0],topPos+pos[i][1],BUTTON_W,BUTTON_H)){PacketDistributor.sendToServer(new SwitchMachineSoulTabPacket(blockPos,CARDS[i].tab()));return true;}return super.mouseClicked(mx,my,b);
    }
    @Override public void render(GuiGraphics g,int mx,int my,float pt){renderBackground(g,mx,my,pt);super.render(g,mx,my,pt);renderTooltip(g,mx,my);}
    @Override protected void renderLabels(GuiGraphics g,int mx,int my){}
    @Override public boolean isPauseScreen(){return false;}
}
