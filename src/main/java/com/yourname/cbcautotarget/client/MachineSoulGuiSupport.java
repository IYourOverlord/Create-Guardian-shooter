package com.yourname.cbcautotarget.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.level.Level;

/** Vanilla 1.21.1 inventory renderer. The rectangle remains fixed on screen. */
final class MachineSoulGuiSupport {
    private MachineSoulGuiSupport() {}

    static ElderGuardian guardian() {
        Level level = Minecraft.getInstance().level;
        return level == null ? null : new ElderGuardian(EntityType.ELDER_GUARDIAN, level);
    }

    static void renderGuardian(GuiGraphics g, ElderGuardian entity, int cx, int cy,
                               int size, float yaw, float pitch) {
        if (entity == null) return;
        entity.setYRot(yaw);
        entity.setYBodyRot(yaw);
        entity.setYHeadRot(yaw);
        entity.yRotO = yaw;
        entity.yBodyRotO = yaw;
        entity.yHeadRotO = yaw;

        float yawRad = yaw * Mth.DEG_TO_RAD;
        float pitchRad = pitch * Mth.DEG_TO_RAD;
        InventoryScreen.renderEntityInInventoryFollowsAngle(
                g, cx - size, cy - size, cx + size, cy + size,
                size, 0.5f, Mth.sin(yawRad), -Mth.sin(pitchRad), entity);
    }
}
