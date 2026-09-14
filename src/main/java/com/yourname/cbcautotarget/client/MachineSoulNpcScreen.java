package com.yourname.cbcautotarget.client;

import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.Tab;
import com.yourname.cbcautotarget.menu.MachineSoulNpcMenu;
import com.yourname.cbcautotarget.network.ToggleMachineSoulGyroStabilizationPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * NPC: страж и переход назад в главное меню (как у остальных вкладок), плюс
 * единственная настройка — включение/выключение гироскопической
 * стабилизации Sable. Кнопка "Exit" в шапке — это переиспользованная
 * Save-кнопка базового экрана: на этой вкладке сохранять нечего, она
 * только закрывает GUI (её перевод уже звучит как "Exit"/"Выход").
 */
public class MachineSoulNpcScreen extends BaseMachineSoulScreen<MachineSoulNpcMenu> {
    private static final int GUI_H = 165;
    private static final int TOGGLE_W = 130, TOGGLE_H = 18;
    private static final int TOGGLE_X = (BaseMachineSoulScreen.GUI_W - TOGGLE_W) / 2;
    private static final int TOGGLE_Y = 134;

    private boolean gyroStabilizationActive;

    public MachineSoulNpcScreen(MachineSoulNpcMenu m, Inventory i, Component t) {
        super(m, i, t, Tab.NPC, m.blockPos, GUI_H);
        this.gyroStabilizationActive = m.isGyroStabilizationActive();
    }

    @Override protected int getInvYBase() { return 0; }
    @Override protected boolean isInventoryHidden() { return true; }

    @Override protected void renderContent(GuiGraphics g, int lx, int ty, int mx, int my) {
        drawToggle(g, lx + TOGGLE_X, ty + TOGGLE_Y, gyroStabilizationActive, "STABILIZATION", mx, my);
    }

    private void drawToggle(GuiGraphics g, int x, int y, boolean on, String text, int mx, int my) {
        boolean h = mx >= x && mx < x + TOGGLE_W && my >= y && my < y + TOGGLE_H;
        g.fill(x, y, x + TOGGLE_W, y + TOGGLE_H, on ? 0xFF234B43 : 0xFF263A42);
        drawBorder(g, x, y, TOGGLE_W, TOGGLE_H, h ? 0xFF8DE8F2 : 0xFF57C9D9);
        g.drawCenteredString(font, text + (on ? " ON" : " OFF"), x + TOGGLE_W / 2, y + 5,
                on ? 0xFF8DE8F2 : 0xFFB8C9CE);
        if (h) g.renderTooltip(font, Component.literal("Sable roll stabilization for physical structures"), mx, my);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            int x = leftPos + TOGGLE_X, y = topPos + TOGGLE_Y;
            if (mx >= x && mx < x + TOGGLE_W && my >= y && my < y + TOGGLE_H) {
                gyroStabilizationActive = !gyroStabilizationActive;
                menu.setGyroStabilizationActive(gyroStabilizationActive);
                PacketDistributor.sendToServer(new ToggleMachineSoulGyroStabilizationPacket(blockPos));
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override protected boolean onSaveClicked() { onClose(); return true; }
}
