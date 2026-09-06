package com.yourname.cbcautotarget.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.lang.reflect.Method;

/** Fixed-position entity rendering with explicit angles and a safe vanilla fallback. */
final class MachineSoulGuiSupport {
    private MachineSoulGuiSupport() {}
    static ElderGuardian guardian() { Level l=Minecraft.getInstance().level; return l==null?null:new ElderGuardian(EntityType.ELDER_GUARDIAN,l); }
    static void renderGuardian(GuiGraphics g,ElderGuardian e,int cx,int cy,int size,float yaw,float pitch){
        if(e==null)return; e.setYRot(yaw);e.setYBodyRot(yaw);e.setYHeadRot(yaw);e.yRotO=yaw;e.yBodyRotO=yaw;e.yHeadRotO=yaw;
        // Prefer the direct quaternion renderer; if mappings differ, continue to the
        // known 1.21.1 angle helper instead of swallowing the render entirely.
        try{
            for(Method m:InventoryScreen.class.getDeclaredMethods()) if(m.getName().equals("renderEntityInInventory")){
                Class<?>[] t=m.getParameterTypes(); if(t.length!=8) continue;
                m.setAccessible(true); Object[] a={g,(float)cx,(float)cy,0f,
                    new Vector3f(size,size,size),
                    new Quaternionf().rotateZ((float)Math.PI).rotateY((float)Math.toRadians(yaw)).rotateX((float)Math.toRadians(pitch)),null,e};
                m.invoke(null,a); return;
            }
        }catch(Throwable ignored){}
        try{
            for(Method m:InventoryScreen.class.getDeclaredMethods()) if(m.getName().equals("renderEntityInInventoryFollowsAngle")){
                m.setAccessible(true); float r=(float)Math.toRadians(yaw);
                m.invoke(null,g,cx-size,cy-size,cx+size,cy+size,size,1f,Mth.sin(r),Mth.cos(r),e); return;
            }
        }catch(Throwable ignored){}
    }
}
