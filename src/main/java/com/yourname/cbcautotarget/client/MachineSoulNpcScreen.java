package com.yourname.cbcautotarget.client;

import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.Tab;
import com.yourname.cbcautotarget.menu.MachineSoulNpcMenu;
import com.yourname.cbcautotarget.network.ToggleMachineSoulCreativeLockPacket;
import com.yourname.cbcautotarget.network.ToggleMachineSoulGyroStabilizationPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * NPC: страж и переход назад в главное меню (как у остальных вкладок), плюс
 * настройки — «The NPC» (блокировка редактирования, только Creative) и
 * гироскопическая стабилизация Sable.
 * Кнопка «Exit» в шапке — это переиспользованная Save-кнопка базового экрана.
 */
public class MachineSoulNpcScreen extends BaseMachineSoulScreen<MachineSoulNpcMenu> {
    private static final int GUI_H = 165;
    private static final int TOGGLE_W = 130, TOGGLE_H = 18;
    private static final int TOGGLE_X = (BaseMachineSoulScreen.GUI_W - TOGGLE_W) / 2;
    private static final int TOGGLE_Y = 134;
    // «The NPC» располагается над STABILIZATION
    private static final int LOCK_Y = TOGGLE_Y - TOGGLE_H - 6;

    private boolean gyroStabilizationActive;
    private boolean creativeLocked;

    public MachineSoulNpcScreen(MachineSoulNpcMenu m, Inventory i, Component t) {
        super(m, i, t, Tab.NPC, m.blockPos, GUI_H);
        this.gyroStabilizationActive = m.isGyroStabilizationActive();
        this.creativeLocked = m.isCreativeLocked();
    }

    @Override protected int getInvYBase() { return 0; }
    @Override protected boolean isInventoryHidden() { return true; }

    private boolean isCreativePlayer() {
        Minecraft mc = Minecraft.getInstance();
        return mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.CREATIVE;
    }

    @Override protected void renderContent(GuiGraphics g, int lx, int ty, int mx, int my) {
        boolean creative = isCreativePlayer();
        boolean locked = creativeLocked;

        // Кнопка «The NPC» — переключается только Creative-игроком
        drawToggle(g, lx + TOGGLE_X, ty + LOCK_Y, locked, "THE NPC", mx, my,
                !creative,
                locked
                        ? "Block is locked. Only Creative players can edit."
                        : "Lock this block for non-Creative players.");

        // Тумблер STABILIZATION — задизейблен если заблокировано и не Creative
        boolean gyroDisabled = locked && !creative;
        drawToggle(g, lx + TOGGLE_X, ty + TOGGLE_Y, gyroStabilizationActive, "STABILIZATION", mx, my,
                gyroDisabled,
                gyroDisabled
                        ? "Block is locked. Only Creative players can edit."
                        : "Sable roll stabilization for physical structures");
    }

    private void drawToggle(GuiGraphics g, int x, int y, boolean on, String text,
                            int mx, int my, boolean disabled, String tooltip) {
        boolean hovered = mx >= x && mx < x + TOGGLE_W && my >= y && my < y + TOGGLE_H;
        boolean activeHover = hovered && !disabled;
        int bg          = disabled ? 0xFF1A2226 : (on ? 0xFF234B43 : 0xFF263A42);
        int borderColor = disabled ? 0xFF3A4A50 : (activeHover ? 0xFF8DE8F2 : 0xFF57C9D9);
        int textColor   = disabled ? 0xFF4A6068 : (on ? 0xFF8DE8F2 : 0xFFB8C9CE);
        g.fill(x, y, x + TOGGLE_W, y + TOGGLE_H, bg);
        drawBorder(g, x, y, TOGGLE_W, TOGGLE_H, borderColor);
        String label = text + (on ? " ON" : " OFF");
        int tx = x + (TOGGLE_W - font.width(label)) / 2;
        int ty2 = y + (TOGGLE_H - font.lineHeight) / 2;
        g.drawString(font, label, tx, ty2, textColor, false);
        if (hovered && tooltip != null) {
            g.renderTooltip(font, Component.literal(tooltip), mx, my);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            boolean creative = isCreativePlayer();

            // Кнопка «The NPC» — только Creative может переключать
            int lx = leftPos + TOGGLE_X, ly = topPos + LOCK_Y;
            if (mx >= lx && mx < lx + TOGGLE_W && my >= ly && my < ly + TOGGLE_H) {
                if (creative) {
                    creativeLocked = !creativeLocked;
                    menu.setCreativeLocked(creativeLocked);
                    PacketDistributor.sendToServer(new ToggleMachineSoulCreativeLockPacket(blockPos));
                }
                return true;
            }

            // Тумблер STABILIZATION — soft-fail если блок заблокирован и не Creative
            int sx = leftPos + TOGGLE_X, sy = topPos + TOGGLE_Y;
            if (mx >= sx && mx < sx + TOGGLE_W && my >= sy && my < sy + TOGGLE_H) {
                if (creativeLocked && !creative) return true; // заблокировано — тултип показан, ничего не делаем
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