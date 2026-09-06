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
        // Keep the 42px clipping frame, but render the guardian at one quarter of
        // that model scale. These values must not be conflated: the first
        // four coordinates define the window, while the fifth controls the
        // entity size.
        final int frame = size;
        final int modelSize = Math.max(1, Math.round(size * 0.25f));
        InventoryScreen.renderEntityInInventoryFollowsAngle(
                g, cx - frame, cy - frame, cx + frame, cy + frame,
                modelSize, 1.0f, Mth.sin(yawRad), -Mth.sin(pitchRad), entity);
    }
}
