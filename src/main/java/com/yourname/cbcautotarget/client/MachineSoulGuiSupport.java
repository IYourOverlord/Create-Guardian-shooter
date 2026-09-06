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

/** Fixed-position entity rendering with explicit yaw and pitch. */
final class MachineSoulGuiSupport {
    private MachineSoulGuiSupport() {}
    static ElderGuardian guardian() { Level l=Minecraft.getInstance().level; return l==null?null:new ElderGuardian(EntityType.ELDER_GUARDIAN,l); }
    static void renderGuardian(GuiGraphics g,ElderGuardian e,int cx,int cy,int size,float yaw,float pitch){
        if(e==null)return; e.setYRot(yaw);e.setYBodyRot(yaw);e.setYHeadRot(yaw);e.yRotO=yaw;e.yBodyRotO=yaw;e.yHeadRotO=yaw;
        try{
            for(Method m:InventoryScreen.class.getDeclaredMethods()) if(m.getName().equals("renderEntityInInventory")){
                m.setAccessible(true); Class<?>[] t=m.getParameterTypes(); Object[] a=new Object[t.length];
                a[0]=g;a[1]=(float)cx;a[2]=(float)cy;a[3]=0f;
                a[4]=new Vector3f(size,size,size);
                a[5]=new Quaternionf().rotateZ((float)Math.PI).rotateY((float)Math.toRadians(yaw)).rotateX((float)Math.toRadians(pitch));
                a[6]=null;a[7]=e;m.invoke(null,a);return;
            }
        }catch(Throwable ignored){}
        // Fallback for mappings without the direct renderer.
        try{for(Method m:InventoryScreen.class.getDeclaredMethods())if(m.getName().equals("renderEntityInInventoryFollowsAngle")){m.setAccessible(true);float r=(float)Math.toRadians(yaw);m.invoke(null,g,cx-size,cy-size,cx+size,cy+size,size,1f,Mth.sin(r),Mth.cos(r),e);return;}}catch(Throwable ignored){}
    }
}
