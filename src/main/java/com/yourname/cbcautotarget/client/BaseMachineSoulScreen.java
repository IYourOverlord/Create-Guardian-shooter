package com.yourname.cbcautotarget.client;

import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.Tab;
import com.yourname.cbcautotarget.network.GoHomeMachineSoulPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.util.Mth;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Базовый класс для всех трёх экранов Machine Soul.
 * Вкладки убраны — навигация только через главную страницу.
 *
 * ── Геометрия ─────────────────────────────────────────────────────────────
 *
 *  Y=0    ┌────────────────────────────────────────┐
 *         │  [←]  «Название вкладки»    [ Save ] │  HEADER_H=16
 *  Y=16   ├────────────────────────────────────────┤  разделитель 1px
 *  Y=17   │  контентная область                     │  CONTENT_TOP=17
 *         │  ...                                    │
 *         ├────────────────────────────────────────┤
 *         │  инвентарь                              │
 *         └────────────────────────────────────────┘
 *
 *  [←]    x=PAD=6,              y=2,  w=BACK_W=12, h=12
 *  Заголовок — центр по ширине
 *  [Save]  x=GUI_W-PAD-SAVE_W, y=2,  w=SAVE_W=38, h=12
 *
 *  SAVE_BOTTOM = HEADER_H  — для совместимости с наследниками,
 *  которые начинают контент от SAVE_BOTTOM + 4 = 20 (> CONTENT_TOP=17 ✓)
 */
public abstract class BaseMachineSoulScreen<M extends AbstractContainerMenu>
        extends AbstractContainerScreen<M> {

    // ── Фиксированные размеры ─────────────────────────────────────────────────
    protected static final int GUI_W       = 260;
    protected static final int PAD         = 6;
    protected static final int SLOT_SIZE   = 18;

    // ── Шапка (← + заголовок + Save) ─────────────────────────────────────────
    protected static final int HEADER_H    = 16;
    protected static final int BTN_Y       = 2;    // Y кнопок в шапке
    protected static final int BTN_H       = 12;   // высота кнопок

    // ── Кнопка «←» ───────────────────────────────────────────────────────────
    protected static final int BACK_W      = 12;
    protected static final int BACK_X      = PAD;  // 6

    // ── Кнопка Save ──────────────────────────────────────────────────────────
    protected static final int SAVE_W      = 38;
    protected static final int SAVE_H      = BTN_H;
    protected static final int SAVE_X      = GUI_W - PAD - SAVE_W;   // 156
    protected static final int SAVE_Y_REL  = BTN_Y;
    /** Алиас для совместимости с наследниками: контент начинается от SAVE_BOTTOM+4 */
    protected static final int SAVE_BOTTOM = HEADER_H;               // 16

    // ── Начало контентной области ─────────────────────────────────────────────
    protected static final int CONTENT_TOP = HEADER_H + 1;           // 17

    // ── Палитра ───────────────────────────────────────────────────────────────
    protected static final int COL_BG            = 0xE0080F08;
    protected static final int COL_BORDER        = 0xFF1A4A1A;
    protected static final int COL_HEADER_BG     = 0xFF0A1F0A;
    protected static final int COL_HEADER_SEP    = 0xFF2A6B2A;
    protected static final int COL_CONTENT_BG    = 0xFF0D2B0D;
    protected static final int COL_ROW_EVEN      = 0xFF0F320F;
    protected static final int COL_ROW_ODD       = 0xFF0A250A;
    protected static final int COL_TEXT          = 0xFFAADDAA;
    protected static final int COL_TEXT_DIM      = 0xFF5A8A5A;
    protected static final int COL_TITLE         = 0xFFCCFFCC;
    protected static final int COL_ACCENT        = 0xFF44BB44;
    protected static final int COL_ACCENT2       = 0xFF33FF88;
    protected static final int COL_SLOT_BG       = 0xFF0A200A;
    protected static final int COL_SLOT_FR       = 0xFF2A6B2A;
    protected static final int COL_INV_LABEL     = 0xFF4A7A4A;
    protected static final int COL_SAVE_BG       = 0xFF0F4A1A;
    protected static final int COL_SAVE_BORDER   = 0xFF33BB44;
    protected static final int COL_SAVE_TEXT     = 0xFFAAFFBB;
    protected static final int COL_SAVE_HOVER_BG = 0xFF1A6B28;
    protected static final int COL_BACK_BG       = 0xFF0A1F0A;
    protected static final int COL_BACK_BORDER   = 0xFF2A6B2A;
    protected static final int COL_BACK_HOVER_BG = 0xFF153515;
    protected static final int COL_LINK_OK       = 0xFF33FF55;
    protected static final int COL_LINK_MISS     = 0xFF1A4A1A;
    protected static final int COL_RADIUS_BAR    = 0xFF0A2A0A;
    protected static final int COL_RADIUS_FILL   = 0xFF33AA33;
    protected static final int COL_GHOST_OVERLAY = 0x4433FF66;
    protected static final int COL_GHOST_HOVER   = 0x6055FF88;

    // ── Поля экземпляра ───────────────────────────────────────────────────────
    protected final Tab activeTab;
    protected final BlockPos blockPos;
    protected final int guiH;
    private float guardianYaw = 0f;
    private float guardianStartYaw = 0f;
    private int guardianStartTicks = 0;
    private boolean guardianStarted = false;

    protected BaseMachineSoulScreen(M menu, Inventory inv, Component title,
                                    Tab activeTab, BlockPos blockPos, int guiH) {
        super(menu, inv, title);
        this.activeTab  = activeTab;
        this.blockPos   = blockPos;
        this.guiH       = guiH;
        imageWidth      = GUI_W;
        imageHeight     = guiH;
        inventoryLabelY = guiH + 500;
        titleLabelY     = guiH + 500;
    }

    // ── Переопределяется наследником ─────────────────────────────────────────

    public BlockPos getBlockPos() { return blockPos; }

    protected abstract int getInvYBase();
    protected int getInvX() { return 8; }
    protected abstract void renderContent(GuiGraphics g, int lx, int ty, int mx, int my);
    protected abstract boolean onSaveClicked();

    /**
     * Позволяет наследнику временно скрыть панель инвентаря игрока (слоты +
     * подпись "Inventory"), когда она не нужна для текущего отображаемого
     * режима — например, для встроенной панели фильтра целей внутри
     * MachineSoulTargetScreen. Слоты инвентаря относятся к контейнеру-меню
     * и не могут быть удалены, но их можно не рисовать и не учитывать при
     * расчёте области под них.
     */
    protected boolean isInventoryHidden() { return false; }

    /** Название вкладки для заголовка шапки. */
    protected String getTabTitle() {
        return switch (activeTab) {
            case VISION   -> "👁  Vision";
            case MOVEMENT -> "⬡  Move";
            case ACTION   -> "⚔  Action";
            case TARGET   -> "🎯  Target";
        };
    }

    // ── Общий рендер ─────────────────────────────────────────────────────────

    @Override
    protected void renderBg(GuiGraphics g, float pt, int mx, int my) {
        int lx = leftPos, ty = topPos;

        // 1. Основной фон + внешняя граница
        g.fill(lx, ty, lx + GUI_W, ty + guiH, COL_BG);
        drawBorder(g, lx, ty, GUI_W, guiH, COL_BORDER);

        // 2. Шапка: фон
        g.fill(lx + 1, ty + 1, lx + GUI_W - 1, ty + HEADER_H, COL_HEADER_BG);

        // 3. Кнопки в шапке
        drawBackButton(g, lx, ty, mx, my);
        drawSaveButton(g, lx, ty, mx, my);

        // 4. Заголовок — название текущей вкладки, центр между кнопками
        String tabTitle = getTabTitle();
        int titleX = lx + (GUI_W - font.width(tabTitle)) / 2;
        int titleY = ty + BTN_Y + (BTN_H - font.lineHeight) / 2;
        g.drawString(font, tabTitle, titleX, titleY, COL_TITLE, false);

        // 5. Разделитель шапка / контент
        g.fill(lx + 1, ty + HEADER_H, lx + GUI_W - 1, ty + HEADER_H + 1, COL_HEADER_SEP);

        // 6. Контентная область
        int contentBottom = ty + (isInventoryHidden() ? guiH - 4 : getInvYBase() - 4);
        g.fill(lx + 1, ty + CONTENT_TOP, lx + GUI_W - 1, contentBottom, COL_CONTENT_BG);

        // 7. Древний страж — общий фокус нового интерфейса
        if (showGuardian()) drawGuardian(g, lx, ty, mx, my);

        // 8. Контент вкладки
        renderContent(g, lx, ty, mx, my);

        // 9. Инвентарь (пропускается, если наследник его скрыл)
        if (!isInventoryHidden()) {
            renderInventoryBg(g, lx, ty);
        }
    }

    // ── Инвентарь ─────────────────────────────────────────────────────────────

    private void renderInventoryBg(GuiGraphics g, int lx, int ty) {
        int invX = lx + getInvX();
        int invY = ty + getInvYBase();
        g.drawString(font, Component.translatable("container.inventory"),
                invX, invY - font.lineHeight - 2, COL_INV_LABEL, false);
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                drawSlotBg(g, invX + col * 18, invY + row * 18);
        for (int col = 0; col < 9; col++)
            drawSlotBg(g, invX + col * 18, invY + 58);
    }

    // ── Кнопка «←» ───────────────────────────────────────────────────────────

    protected void drawBackButton(GuiGraphics g, int lx, int ty, int mx, int my) {
        boolean hov = isBackHovered(mx, my);
        int bx = lx + BACK_X;
        int by = ty + BTN_Y;

        g.fill(bx, by, bx + BACK_W, by + BTN_H,
                hov ? COL_BACK_HOVER_BG : COL_BACK_BG);
        drawBorder(g, bx, by, BACK_W, BTN_H,
                hov ? COL_ACCENT : COL_BACK_BORDER);

        String arrow = "←";
        int aw = font.width(arrow);
        g.drawString(font, arrow,
                bx + (BACK_W - aw) / 2,
                by + (BTN_H - font.lineHeight) / 2,
                hov ? COL_ACCENT2 : COL_ACCENT, false);
    }

    protected boolean isBackHovered(int mx, int my) {
        return mx >= leftPos + BACK_X && mx < leftPos + BACK_X + BACK_W
                && my >= topPos + BTN_Y && my < topPos + BTN_Y + BTN_H;
    }

    // ── Кнопка Save ──────────────────────────────────────────────────────────

    protected void drawSaveButton(GuiGraphics g, int lx, int ty, int mx, int my) {
        int bx = lx + SAVE_X;
        int by = ty + SAVE_Y_REL;
        boolean hov = mx >= bx && mx < bx + SAVE_W && my >= by && my < by + SAVE_H;

        g.fill(bx, by, bx + SAVE_W, by + SAVE_H, hov ? COL_SAVE_HOVER_BG : COL_SAVE_BG);
        drawBorder(g, bx, by, SAVE_W, SAVE_H, COL_SAVE_BORDER);

        String label = Component.translatable("gui.cbc_autotarget.soul.save").getString();
        int tw = font.width(label);
        g.drawString(font, label,
                bx + (SAVE_W - tw) / 2,
                by + (SAVE_H - font.lineHeight) / 2,
                COL_SAVE_TEXT, false);
    }

    protected boolean isSaveHovered(int mx, int my) {
        int bx = leftPos + SAVE_X;
        int by = topPos  + SAVE_Y_REL;
        return mx >= bx && mx < bx + SAVE_W && my >= by && my < by + SAVE_H;
    }

    // ── Слот-фон ─────────────────────────────────────────────────────────────

    protected void drawSlotBg(GuiGraphics g, int x, int y) {
        g.fill(x - 1, y - 1, x + SLOT_SIZE + 1, y + SLOT_SIZE + 1, COL_SLOT_FR);
        g.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, COL_SLOT_BG);
    }


    protected boolean showGuardian() { return true; }

    protected void drawGuardian(GuiGraphics g, int lx, int ty, int mx, int my) {
        int cx = lx + 72;
        int cy = ty + 88;
        if (!guardianStarted) {
            guardianStarted = true;
            guardianStartYaw = 0f;
            guardianStartTicks = 0;
        }
        float target = Mth.clamp(((mx - cx) / 42f) * 45f, -45f, 45f);
        if (guardianStartTicks < 12) {
            guardianStartTicks++;
            float direction = target < 0 ? -1f : 1f;
            guardianYaw = Mth.lerp(guardianStartTicks / 12f, 0f, direction * 45f);
        } else {
            guardianYaw = Mth.lerp(0.10f, guardianYaw, target);
        }
        MachineSoulGuiSupport.renderGuardian(g, MachineSoulGuiSupport.guardian(), cx, cy, 38, guardianYaw, 0f);
    }

    // ── Утилиты ───────────────────────────────────────────────────────────────

    protected static void drawBorder(GuiGraphics g, int x, int y, int w, int h, int col) {
        g.fill(x,         y,         x + w, y + 1,     col);
        g.fill(x,         y + h - 1, x + w, y + h,     col);
        g.fill(x,         y,         x + 1, y + h,     col);
        g.fill(x + w - 1, y,         x + w, y + h,     col);
    }

    protected void drawWrappedText(GuiGraphics g, int x, int y, int maxW, String text, int color) {
        var lines = font.split(Component.literal(text), maxW);
        for (var line : lines) {
            g.drawString(font, line, x, y, color, false);
            y += font.lineHeight + 1;
        }
    }

    protected int wrappedTextHeight(String text, int maxW) {
        var lines = font.split(Component.literal(text), maxW);
        return lines.size() * (font.lineHeight + 1);
    }

    // ── Клики ────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && isBackHovered((int)mx, (int)my)) {
            PacketDistributor.sendToServer(new GoHomeMachineSoulPacket(blockPos));
            return true;
        }
        if (button == 0 && isSaveHovered((int)mx, (int)my)) {
            onSaveClicked();
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) { }

    @Override
    public boolean isPauseScreen() { return false; }
}