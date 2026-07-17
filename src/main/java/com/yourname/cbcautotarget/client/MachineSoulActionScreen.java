package com.yourname.cbcautotarget.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.Tab;
import com.yourname.cbcautotarget.menu.MachineSoulActionMenu;
import com.yourname.cbcautotarget.network.SaveMachineSoulActionPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Экран Action.
 *
 * Геометрия:
 *   SAVE_BOTTOM = 16
 *   Hint: y = SAVE_BOTTOM+2 = 18. Под текст хинта зарезервировано
 *         HINT_RESERVED_H=52px (до 5 строк) — рассчитано по самому длинному
 *         переводу строки "tab.action.hint" среди всех 19+ языков (es_es,
 *         144 символа), чтобы строка FIRE не перекрывала текст ни при каком
 *         языке. drawWrappedText сам обрезает реальное число строк, запас
 *         просто гарантирует, что FIRE-строка ниже текста.
 *   FIRE строка: FIRST_ROW_Y = 18 + 52 = 70 (из MachineSoulActionMenu)
 *   FIRE_ROW_BOTTOM = 70 + 18 = 88
 *   INV_Y_BASE = 92 (из MachineSoulActionMenu)
 *   GUI_H = 92 + 54 + 4 + 18 + 4 = 172
 */
public class MachineSoulActionScreen extends BaseMachineSoulScreen<MachineSoulActionMenu> {

    private static final int GUI_H_ACTION = MachineSoulActionMenu.INV_Y_BASE + 54 + 4 + 18 + 4;

    private static final int HINT_Y = SAVE_BOTTOM + 2; // 18

    public MachineSoulActionScreen(MachineSoulActionMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, Tab.ACTION, menu.blockPos, GUI_H_ACTION);
    }

    @Override protected int getInvYBase() { return MachineSoulActionMenu.INV_Y_BASE; }
    @Override protected int getInvX()    { return MachineSoulActionMenu.INV_X; }

    @Override
    protected void renderContent(GuiGraphics g, int lx, int ty, int mx, int my) {
        drawSaveButton(g, lx, ty, mx, my);

        // Hint — ниже SAVE_BOTTOM(16)
        drawWrappedText(g, lx + PAD, ty + HINT_Y, GUI_W - PAD * 2,
                Component.translatable("gui.cbc_autotarget.soul.tab.action.hint").getString(),
                COL_TEXT_DIM);

        // Строка FIRE — начинается с FIRST_ROW_Y, гарантированно ниже хинта
        // при любом языке локализации (см. запас HINT_RESERVED_H в комментарии класса).
        int rowY  = ty + MachineSoulActionMenu.FIRE_ROW_Y;
        g.fill(lx + 1, rowY, lx + GUI_W - 1, rowY + MachineSoulActionMenu.ROW_H, COL_ROW_EVEN);
        g.fill(lx + 2, rowY + MachineSoulActionMenu.ROW_H - 1,
                lx + GUI_W - 2, rowY + MachineSoulActionMenu.ROW_H, COL_BORDER);

        int textY = rowY + (MachineSoulActionMenu.ROW_H - font.lineHeight) / 2;
        g.drawString(font,
                Component.translatable("gui.cbc_autotarget.soul.role.fire"),
                lx + PAD, textY, COL_TEXT, false);

        // Индикатор
        boolean assigned = !menu.getFreqItem(0).isEmpty() || !menu.getFreqItem(1).isEmpty();
        int dotX = lx + MachineSoulActionMenu.FREQ_X0 - 12;
        int dotY = rowY + (MachineSoulActionMenu.ROW_H - 5) / 2;
        g.fill(dotX, dotY, dotX + 5, dotY + 5, assigned ? COL_LINK_OK : COL_LINK_MISS);

        // Фоны слотов
        int slotY = ty + MachineSoulActionMenu.FIRE_SLOT_Y;
        drawSlotBg(g, lx + MachineSoulActionMenu.FREQ_X0, slotY);
        drawSlotBg(g, lx + MachineSoulActionMenu.FREQ_X1, slotY);

        // Ghost-предметы
        renderGhostItems(g, mx, my);
    }

    private void renderGhostItems(GuiGraphics g, int mx, int my) {
        for (int i = 0; i < MachineSoulActionMenu.FREQ_SLOTS; i++) {
            Slot slot = menu.slots.get(i);
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;

            int x = leftPos + (i == 0 ? MachineSoulActionMenu.FREQ_X0 : MachineSoulActionMenu.FREQ_X1);
            int y = topPos  + MachineSoulActionMenu.FIRE_SLOT_Y;

            boolean hov = mx >= x && mx < x + 16 && my >= y && my < y + 16;
            if (hov) g.fill(x, y, x + 16, y + 16, COL_GHOST_HOVER);

            RenderSystem.enableBlend();
            RenderSystem.setShaderColor(1f, 1f, 1f, 0.5f);
            g.renderItem(stack, x, y);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.disableBlend();
            g.fill(x, y, x + 16, y + 16, COL_GHOST_OVERLAY);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (int i = 0; i < MachineSoulActionMenu.FREQ_SLOTS; i++) {
            int x = leftPos + (i == 0 ? MachineSoulActionMenu.FREQ_X0 : MachineSoulActionMenu.FREQ_X1);
            int y = topPos  + MachineSoulActionMenu.FIRE_SLOT_Y;
            if (mx >= x && mx < x + 16 && my >= y && my < y + 16) {
                ItemStack carried = menu.getCarried();
                menu.setFreqItem(i, (button == 1 || carried.isEmpty()) ? ItemStack.EMPTY : carried);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    protected boolean onSaveClicked() {
        PacketDistributor.sendToServer(new SaveMachineSoulActionPacket(
                blockPos,
                menu.getFreqItem(0).copy(),
                menu.getFreqItem(1).copy()));
        onClose();
        return true;
    }

}
