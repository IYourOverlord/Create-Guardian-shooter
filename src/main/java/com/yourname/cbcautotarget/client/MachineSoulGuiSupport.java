package com.yourname.cbcautotarget.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.level.Level;
import java.lang.reflect.Method;

/** Fixed rectangle; only synthetic mouse angles change, so the model never translates. */
final class MachineSoulGuiSupport {
    private MachineSoulGuiSupport() {}
    static ElderGuardian guardian(){Level level=Minecraft.getInstance().level;return level==null?null:new ElderGuardian(EntityType.ELDER_GUARDIAN,level);}
    static void renderGuardian(GuiGraphics g,ElderGuardian e,int cx,int cy,int size,float yaw,float pitch){
        if(e==null)return;
        e.setYRot(yaw);e.setYBodyRot(yaw);e.setYHeadRot(yaw);e.yRotO=yaw;e.yBodyRotO=yaw;e.yHeadRotO=yaw;
        try{for(Method m:InventoryScreen.class.getDeclaredMethods())if(m.getName().equals("renderEntityInInventoryFollowsMouse")){
            m.setAccessible(true);
            // Keep x1/y1/x2/y2 fixed. These are only virtual look coordinates.
            int lookX=cx+(int)(Math.sin(Math.toRadians(yaw))*40f);
            int lookY=cy+(int)(Math.sin(Math.toRadians(pitch))*40f);
            m.invoke(null,g,cx-size,cy-size,cx+size,cy+size,size,1.0f,lookX,lookY,e);
            return;
        }}catch(Throwable ignored){}
    }
}
