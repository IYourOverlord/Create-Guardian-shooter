package com.yourname.cbcautotarget.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.CommandRole;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.Tab;
import com.yourname.cbcautotarget.menu.MachineSoulMoveMenu;
import com.yourname.cbcautotarget.network.SaveMachineSoulMovePacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Экран Move.
 *
 * Геометрия:
 *   SAVE_BOTTOM = 16
 *   Hint-строка: y = SAVE_BOTTOM + 2 = 18 (1 строка, заканчивается на 27)
 *   FIRST_ROW_Y = 28 (из MachineSoulMoveMenu)
 *   6 строк × ROW_H(18) = 108px → last_row_bottom = 136
 *   INV_Y_BASE = 155
 *   GUI_H = 155 + 54 + 4 + 18 + 4 = 235
 */
public class MachineSoulMoveScreen extends BaseMachineSoulScreen<MachineSoulMoveMenu> {

    // GUI_H = INV_Y_BASE(155) + 3*18(54) + 4 + 18 + 4 = 235
    private static final int GUI_H_MOVE = MachineSoulMoveMenu.INV_Y_BASE + 54 + 4 + 18 + 4;

    public MachineSoulMoveScreen(MachineSoulMoveMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, Tab.MOVEMENT, menu.blockPos, GUI_H_MOVE);
    }

    @Override protected int getInvYBase() { return MachineSoulMoveMenu.INV_Y_BASE; }
    @Override protected int getInvX()    { return MachineSoulMoveMenu.INV_X; }

    @Override
    protected void renderContent(GuiGraphics g, int lx, int ty, int mx, int my) {
        drawSaveButton(g, lx, ty, mx, my);

        // Hint — одна строка, между шапкой и первым рядом ролей
        int hintY = ty + SAVE_BOTTOM + 2;  // 18
        g.drawString(font,
                Component.translatable("gui.cbc_autotarget.soul.tab.movement.hint"),
                lx + PAD, hintY, COL_TEXT_DIM, false);

        CommandRole[] roles = MachineSoulMoveMenu.MOVE_ROLES;
        for (int i = 0; i < roles.length; i++) {
            drawRoleRow(g, lx, ty, i, roles[i], mx, my);
        }

        renderGhostItems(g, mx, my);
    }

    private void drawRoleRow(GuiGraphics g, int lx, int ty,
                             int idx, CommandRole role, int mx, int my) {
        int rowY  = ty + MachineSoulMoveMenu.rowY(idx);
        int rowBg = (idx % 2 == 0) ? COL_ROW_EVEN : COL_ROW_ODD;

        // Фон строки
        g.fill(lx + 1, rowY, lx + GUI_W - 1, rowY + MachineSoulMoveMenu.ROW_H, rowBg);
        // Разделитель снизу
        g.fill(lx + 2, rowY + MachineSoulMoveMenu.ROW_H - 1,
                lx + GUI_W - 2, rowY + MachineSoulMoveMenu.ROW_H, COL_BORDER);

        // Лейбл
        int textY = rowY + (MachineSoulMoveMenu.ROW_H - font.lineHeight) / 2;
        g.drawString(font, roleLabel(role), lx + PAD, textY, COL_TEXT, false);

        // Индикатор — зелёный если слот заполнен
        boolean assigned = !menu.getFreqItem(idx * 2).isEmpty()
                || !menu.getFreqItem(idx * 2 + 1).isEmpty();
        int dotX = lx + MachineSoulMoveMenu.FREQ_X0 - 12;
        int dotY = rowY + (MachineSoulMoveMenu.ROW_H - 5) / 2;
        g.fill(dotX, dotY, dotX + 5, dotY + 5, assigned ? COL_LINK_OK : COL_LINK_MISS);

        // Фоны freq-слотов
        int slotY = ty + MachineSoulMoveMenu.slotY(idx);
        drawSlotBg(g, lx + MachineSoulMoveMenu.FREQ_X0, slotY);
        drawSlotBg(g, lx + MachineSoulMoveMenu.FREQ_X1, slotY);
    }

    private void renderGhostItems(GuiGraphics g, int mx, int my) {
        for (int i = 0; i < MachineSoulMoveMenu.FREQ_SLOTS; i++) {
            Slot slot = menu.slots.get(i);
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;

            int x = leftPos + ((i % 2 == 0) ? MachineSoulMoveMenu.FREQ_X0 : MachineSoulMoveMenu.FREQ_X1);
            int y = topPos  + MachineSoulMoveMenu.slotY(i / 2);

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
        for (int i = 0; i < MachineSoulMoveMenu.FREQ_SLOTS; i++) {
            int x = leftPos + ((i % 2 == 0) ? MachineSoulMoveMenu.FREQ_X0 : MachineSoulMoveMenu.FREQ_X1);
            int y = topPos  + MachineSoulMoveMenu.slotY(i / 2);
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
        Map<CommandRole, ItemStack[]> map = new LinkedHashMap<>();
        CommandRole[] roles = MachineSoulMoveMenu.MOVE_ROLES;
        for (int i = 0; i < roles.length; i++) {
            map.put(roles[i], new ItemStack[]{
                    menu.getFreqItem(i * 2).copy(),
                    menu.getFreqItem(i * 2 + 1).copy()
            });
        }
        PacketDistributor.sendToServer(new SaveMachineSoulMovePacket(blockPos, map));
        onClose();
        return true;
    }

    private static Component roleLabel(CommandRole role) {
        return switch (role) {
            case MOVE_FORWARD  -> Component.translatable("gui.cbc_autotarget.soul.role.forward");
            case MOVE_BACKWARD -> Component.translatable("gui.cbc_autotarget.soul.role.backward");
            case MOVE_LEFT     -> Component.translatable("gui.cbc_autotarget.soul.role.left");
            case MOVE_RIGHT    -> Component.translatable("gui.cbc_autotarget.soul.role.right");
            case MOVE_UP       -> Component.translatable("gui.cbc_autotarget.soul.role.up");
            case MOVE_DOWN     -> Component.translatable("gui.cbc_autotarget.soul.role.down");
            default            -> Component.literal(role.name());
        };
    }
}