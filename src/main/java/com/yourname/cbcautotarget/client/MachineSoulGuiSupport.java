package com.yourname.cbcautotarget.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.util.Mth;

import java.lang.reflect.Method;

/** Shared presentation helpers for the Machine Soul radial interface. */
final class MachineSoulGuiSupport {
    private MachineSoulGuiSupport() {}

    static ElderGuardian guardian() {
        Level level = Minecraft.getInstance().level;
        return level == null ? null : new ElderGuardian(EntityType.ELDER_GUARDIAN, level);
    }

    /**
     * Uses the vanilla inventory renderer without binding to a version-specific
     * overload at compile time. This keeps the mod compatible with the 1.21.1
     * NeoForge mappings used by the project.
     */
    static void renderGuardian(GuiGraphics g, ElderGuardian entity, int cx, int cy,
                               int scale, float yaw, float pitch) {
        if (entity == null) return;
        try {
            for (Method m : InventoryScreen.class.getDeclaredMethods()) {
                if (!m.getName().equals("renderEntityInInventoryFollowsMouse")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 8) {
                    m.setAccessible(true);
                    m.invoke(null, g, cx - scale, cy - scale, cx + scale, cy + scale,
                            scale, yaw, pitch, entity);
                    return;
                }
                if (p.length == 9) {
                    m.setAccessible(true);
                    m.invoke(null, g, cx - scale, cy - scale, cx + scale, cy + scale,
                            scale, 0f, yaw, pitch, entity);
                    return;
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // A missing overload should not break the GUI; the surrounding UI remains usable.
        }
    }

    static int radialX(int cx, int cy, int index, int count, int radius) {
        double a = -Math.PI / 2.0 + Math.PI * index / Math.max(1, count - 1);
        return cx + (int) Math.round(Math.cos(a) * radius);
    }

    static int radialY(int cx, int cy, int index, int count, int radius) {
        double a = -Math.PI / 2.0 + Math.PI * index / Math.max(1, count - 1);
        return cy + (int) Math.round(Math.sin(a) * radius);
    }
}
