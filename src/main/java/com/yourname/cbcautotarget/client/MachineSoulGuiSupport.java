package com.yourname.cbcautotarget.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.level.Level;
import java.lang.reflect.Method;

/** Stable 1.21.1 vanilla inventory renderer. Coordinates never depend on the mouse. */
final class MachineSoulGuiSupport {
    private MachineSoulGuiSupport() {}
    static ElderGuardian guardian(){Level level=Minecraft.getInstance().level;return level==null?null:new ElderGuardian(EntityType.ELDER_GUARDIAN,level);}
    static void renderGuardian(GuiGraphics g,ElderGuardian e,int cx,int cy,int size,float yaw,float pitch){
        if(e==null)return;
        e.setYRot(yaw);e.setYBodyRot(yaw);e.setYHeadRot(yaw);e.yRotO=yaw;e.yBodyRotO=yaw;e.yHeadRotO=yaw;
        try{for(Method m:InventoryScreen.class.getDeclaredMethods())if(m.getName().equals("renderEntityInInventoryFollowsAngle")){
            m.setAccessible(true);float r=yaw*((float)Math.PI/180f);
            m.invoke(null,g,cx-size,cy-size,cx+size,cy+size,size,1.0f,Mth.sin(r),Mth.cos(r),e);return;
        }}catch(Throwable ignored){}
    }
}
