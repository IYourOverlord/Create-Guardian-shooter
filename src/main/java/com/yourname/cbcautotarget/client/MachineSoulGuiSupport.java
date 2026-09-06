package com.yourname.cbcautotarget.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import java.lang.reflect.Method;

/** Vanilla entity renderer helper. Uses the angle overload so the entity stays fixed. */
final class MachineSoulGuiSupport {
    private MachineSoulGuiSupport() {}
    static ElderGuardian guardian() {
        Level level = Minecraft.getInstance().level;
        return level == null ? null : new ElderGuardian(EntityType.ELDER_GUARDIAN, level);
    }
    static void renderGuardian(GuiGraphics g, ElderGuardian entity, int cx, int cy, int size, float yaw) {
        if (entity == null) return;
        entity.setYRot(yaw); entity.setYBodyRot(yaw); entity.setYHeadRot(yaw);
        entity.yRotO = yaw; entity.yBodyRotO = yaw; entity.yHeadRotO = yaw;
        try {
            for (Method m : InventoryScreen.class.getDeclaredMethods()) {
                if (!m.getName().equals("renderEntityInInventoryFollowsAngle")) continue;
                m.setAccessible(true);
                // 1.21.1: graphics, x1, y1, x2, y2, size, scale, angleX, angleY, entity
                m.invoke(null, g, cx - size, cy - size, cx + size, cy + size,
                        size, 1.0f, 0.0f, yaw, entity);
                return;
            }
        } catch (Throwable ignored) { }
    }
}
