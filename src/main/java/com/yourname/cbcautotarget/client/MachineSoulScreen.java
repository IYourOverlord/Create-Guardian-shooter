package com.yourname.cbcautotarget.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.CommandRole;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.Tab;
import com.yourname.cbcautotarget.menu.MachineSoulMenu;
import com.yourname.cbcautotarget.network.SaveMachineSoulConfigPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.EnumMap;
import java.util.Map;

/**
 * GUI блока Machine Soul — три вкладки:
 *
 *  👁  Vision   — настройка радиуса обнаружения (слайдер + кнопки ±)
 *  ⬡  Move     — freq-слоты для FORWARD / BACKWARD / LEFT / RIGHT / UP / DOWN
 *  ⚔  Action   — freq-слот для FIRE + диаграмма угла атаки
 *
 * ── Геометрия ──────────────────────────────────────────────────────────────
 *
 *  Y=0        ┌──────────────────────────────────────┐
 *             │  [Vision]  [Move]  [Action]           │  TAB_H = 22
 *  Y=22       ├──────────────────────────────────────┤  ← разделитель (1px)
 *  Y=23       │  контентная область                   │  CONTENT_TOP
 *             │    hint (9px текст + 6px gap)         │
 *  Y=38       │    строка 0  [лейбл]  ●  [S0][S1]   │  FIRST_ROW_Y
 *  Y=64       │    строка 1  ...                      │
 *             │    ...                                 │
 *  Y=194      ├──────────────────────────────────────┤  inv label
 *  Y=198      │  [инвентарь 9×3]                      │
 *  Y=254      │  [хотбар 9×1]                         │
 *  Y=256      └──────────────────────────────────────┘  GUI_H
 *
 *  Movement содержит 6 строк: 6 × ROW_H(26) = 156px → последняя строка заканчивается
 *  на Y = 38 + 6×26 = 194. Это оставляет достаточно места до инвентаря (Y=198).
 *
 *  Action содержит 1 строку (FIRE) + диаграмму под ней.
 *
 *  Freq-слоты (X): FREQ_SLOT_X0=144, FREQ_SLOT_X1=164 — в правой части.
 *  Слот (18×18) рисуется от (x-1,y-1) до (x+19,y+19) включая рамку.
 *  Slot.x/y в Menu совпадают с FREQ_SLOT_X0/X1 и getSlotScreenY() — без смещений.
 */
public class MachineSoulScreen extends AbstractContainerScreen<MachineSoulMenu> {

    // ── Размеры GUI ───────────────────────────────────────────────────────────
    private static final int GUI_W       = 200;
    private static final int GUI_H       = 234;
    private static final int TAB_H      = 18;
    private static final int TAB_W      = 50;
    // CONTENT_TOP, FIRST_ROW_Y, ROW_H берём прямо из Menu — единый источник истины
    private static final int CONTENT_TOP = MachineSoulMenu.CONTENT_TOP;   // 19
    private static final int FIRST_ROW_Y = MachineSoulMenu.FIRST_ROW_Y;   // 29
    private static final int ROW_H       = MachineSoulMenu.ROW_H;         // 18
    private static final int SLOT_OFFSET = MachineSoulMenu.SLOT_OFFSET_Y; // 0
    private static final int PAD         = 8;
    private static final int SLOT_SIZE   = 18;

    // ── Цвета ─────────────────────────────────────────────────────────────────
    private static final int COL_BG            = 0xD0101010;
    private static final int COL_BORDER        = 0xFF555555;
    private static final int COL_TAB_ACTIVE    = 0xFF1E1E2E;
    private static final int COL_TAB_INACTIVE  = 0xFF111118;
    private static final int COL_TAB_BORDER    = 0xFF6060A0;
    private static final int COL_CONTENT_BG    = 0xFF1E1E2E;
    private static final int COL_ROW_EVEN      = 0xFF202030;
    private static final int COL_ROW_ODD       = 0xFF1A1A28;
    private static final int COL_TEXT          = 0xFFCCCCCC;
    private static final int COL_TEXT_DIM      = 0xFF888899;
    private static final int COL_TITLE         = 0xFFFFFFFF;
    private static final int COL_LINK_OK       = 0xFF44DD66;
    private static final int COL_LINK_MISS     = 0xFF555566;
    private static final int COL_SLOT_BG       = 0xFF2A2A3A;
    private static final int COL_SLOT_FR       = 0xFF6868A0;
    private static final int COL_INV_LABEL     = 0xFF8888AA;
    private static final int COL_GHOST_OVERLAY = 0x48AAAAFF;
    private static final int COL_GHOST_HOVER   = 0x60FFFFFF;
    private static final int COL_ACCENT        = 0xFF7070D0;
    private static final int COL_RADIUS_BAR    = 0xFF4040A0;
    private static final int COL_RADIUS_FILL   = 0xFF7070DD;

    // ── Вкладки ───────────────────────────────────────────────────────────────
    private Tab activeTab = Tab.VISION;

    // ── Статус линков ─────────────────────────────────────────────────────────
    private final Map<CommandRole, Boolean> linkStatus = new EnumMap<>(CommandRole.class);

    // ── Состояние радиуса ─────────────────────────────────────────────────────
    private int radiusValue;

    // ── Кнопки ───────────────────────────────────────────────────────────────
    private Button btnRadiusMinus;
    private Button btnRadiusPlus;
    private Button btnSave;

    public MachineSoulScreen(MachineSoulMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        imageWidth  = GUI_W;
        imageHeight = GUI_H;
        // Прячем стандартные лейблы ванили (они рисуются через renderLabels)
        inventoryLabelY = GUI_H + 200;
        titleLabelY     = GUI_H + 200;

        for (CommandRole role : CommandRole.values()) linkStatus.put(role, false);
        radiusValue = menu.getDetectionRadius();
    }

    // ── Инициализация ─────────────────────────────────────────────────────────

    @Override
    protected void init() {
        super.init();
        buildButtons();
    }

    private void buildButtons() {
        if (btnRadiusMinus != null) removeWidget(btnRadiusMinus);
        if (btnRadiusPlus  != null) removeWidget(btnRadiusPlus);
        if (btnSave        != null) removeWidget(btnSave);

        int cx = leftPos;
        int cy = topPos;

        // Кнопки ± радиуса — на вкладке Vision
        // Позиция: под слайдером. Слайдер на y = CONTENT_TOP+PAD + lineH+6 + lineH+10 = ~60
        int sliderY    = cy + CONTENT_TOP + PAD + font.lineHeight + 6 + font.lineHeight + 10;
        int btnY       = sliderY + 12; // под слайдером (8px бар + 4px gap)
        btnRadiusMinus = addRenderableWidget(Button.builder(
                Component.literal("−"),
                b -> changeRadius(-5)
        ).bounds(cx + PAD, btnY, 20, 14).build());

        btnRadiusPlus = addRenderableWidget(Button.builder(
                Component.literal("+"),
                b -> changeRadius(+5)
        ).bounds(cx + PAD + 22, btnY, 20, 14).build());

        // Кнопка Save — в правом верхнем углу контентной области
        // topPos + TAB_H + 1 (разделитель) + 4 (отступ) = topPos + CONTENT_TOP + 4
        btnSave = addRenderableWidget(Button.builder(
                Component.translatable("gui.cbc_autotarget.soul.save"),
                b -> onSaveClicked()
        ).bounds(cx + GUI_W - PAD - 40, cy + CONTENT_TOP + 4, 40, 14).build());

        updateButtonVisibility();
    }

    private void updateButtonVisibility() {
        boolean isVision = activeTab == Tab.VISION;
        btnRadiusMinus.visible = isVision;
        btnRadiusPlus.visible  = isVision;
    }

    private void changeRadius(int delta) {
        radiusValue = Math.max(MachineSoulBlockEntity.MIN_DETECTION_RADIUS,
                Math.min(MachineSoulBlockEntity.MAX_DETECTION_RADIUS,
                        radiusValue + delta));
        menu.setDetectionRadius(radiusValue);
    }

    // ── Сохранение ───────────────────────────────────────────────────────────

    private boolean configPacketSent = false;

    private void sendConfigIfNeeded() {
        if (configPacketSent) return;
        configPacketSent = true;

        Map<CommandRole, ItemStack[]> slotMap = new EnumMap<>(CommandRole.class);
        for (CommandRole role : CommandRole.values()) {
            ItemStack f0 = menu.getBufferItem(role, 0).copy();
            ItemStack f1 = menu.getBufferItem(role, 1).copy();
            slotMap.put(role, new ItemStack[]{f0, f1});
        }
        PacketDistributor.sendToServer(new SaveMachineSoulConfigPacket(
                menu.blockPos, radiusValue, slotMap));
    }

    private void onSaveClicked() {
        sendConfigIfNeeded();
        onClose();
    }

    @Override
    public void onClose() {
        sendConfigIfNeeded();
        super.onClose();
    }

    // ── Обновление статуса линков (с сервера) ─────────────────────────────────

    public void updateLinkStatus(Map<CommandRole, Boolean> status) {
        linkStatus.putAll(status);
    }

    // ── Рендер фона ──────────────────────────────────────────────────────────

    @Override
    protected void renderBg(GuiGraphics g, float pt, int mouseX, int mouseY) {
        int lx = leftPos;
        int ty = topPos;

        // Основной фон
        g.fill(lx, ty, lx + GUI_W, ty + GUI_H, COL_BG);
        drawBorder(g, lx, ty, GUI_W, GUI_H, COL_BORDER);

        // ── Вкладки ───────────────────────────────────────────────────────────
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            drawTab(g, lx, ty, i, tabs[i]);
        }
        // Разделительная линия под вкладками
        g.fill(lx + 1, ty + TAB_H, lx + GUI_W - 1, ty + TAB_H + 1, COL_TAB_BORDER);

        // ── Контентная область ────────────────────────────────────────────────
        int contentY = ty + CONTENT_TOP;
        // Контент заканчивается над инвентарём: INV_Y_BASE - 4 (отступ под label)
        int contentBottom = ty + MachineSoulMenu.INV_Y_BASE - 4;
        g.fill(lx + 1, contentY, lx + GUI_W - 1, contentBottom, COL_CONTENT_BG);

        switch (activeTab) {
            case VISION   -> renderVisionContent(g, lx, ty, mouseX, mouseY);
            case MOVEMENT -> renderMovementContent(g, lx, ty, mouseX, mouseY);
            case ACTION   -> renderActionContent(g, lx, ty, mouseX, mouseY);
            case TARGET   -> renderTargetContent(g, lx, ty, mouseX, mouseY);
        }

        // ── Инвентарь игрока ──────────────────────────────────────────────────
        // Лейбл "Inventory" над сеткой
        int invLabelY = ty + MachineSoulMenu.INV_Y_BASE - font.lineHeight - 2;
        g.drawString(font, Component.translatable("container.inventory"),
                lx + PAD, invLabelY, COL_INV_LABEL, false);

        // Фон инвентаря — 3 строки
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                drawSlotBg(g,
                        lx + MachineSoulMenu.INV_X + col * 18,
                        ty + MachineSoulMenu.INV_Y_BASE + row * 18);
            }
        }
        // Хотбар
        for (int col = 0; col < 9; col++) {
            drawSlotBg(g,
                    lx + MachineSoulMenu.INV_X + col * 18,
                    ty + MachineSoulMenu.INV_Y_BASE + 58);
        }
    }

    // ── Вкладка Vision ────────────────────────────────────────────────────────

    private void renderVisionContent(GuiGraphics g, int lx, int ty, int mx, int my) {
        int y = ty + CONTENT_TOP + PAD;

        // Заголовок
        g.drawString(font,
                Component.translatable("gui.cbc_autotarget.soul.tab.vision.radius_label"),
                lx + PAD, y, COL_TEXT, false);
        y += font.lineHeight + 6;

        // Подсказка
        g.drawString(font,
                Component.translatable("gui.cbc_autotarget.soul.tab.vision.radius_hint"),
                lx + PAD, y, COL_TEXT_DIM, false);
        y += font.lineHeight + 10;

        // Слайдер
        int barX = lx + PAD;
        int barW = GUI_W - PAD * 2;
        int barH = 8;
        g.fill(barX, y, barX + barW, y + barH, COL_RADIUS_BAR);
        float t = (float)(radiusValue - MachineSoulBlockEntity.MIN_DETECTION_RADIUS)
                / (MachineSoulBlockEntity.MAX_DETECTION_RADIUS - MachineSoulBlockEntity.MIN_DETECTION_RADIUS);
        int fillW = (int)(barW * t);
        if (fillW > 0) g.fill(barX, y, barX + fillW, y + barH, COL_RADIUS_FILL);
        drawBorder(g, barX - 1, y - 1, barW + 2, barH + 2, COL_SLOT_FR);
        y += barH + 4;

        // Значение радиуса рядом с кнопками (кнопки уже добавлены в buildButtons)
        String valStr = radiusValue + " "
                + Component.translatable("gui.cbc_autotarget.soul.tab.vision.blocks").getString();
        g.drawString(font, valStr, lx + PAD + 48, y + 1, COL_ACCENT, false);
        y += 22;

        // Разделитель
        g.fill(lx + PAD, y, lx + GUI_W - PAD, y + 1, COL_BORDER);
        y += 8;

        // Информационные подсказки
        drawWrappedText(g, lx + PAD, y, GUI_W - PAD * 2,
                Component.translatable("gui.cbc_autotarget.soul.tab.vision.info_movement").getString(),
                COL_TEXT_DIM);
        y += font.lineHeight * 2 + 4;
        drawWrappedText(g, lx + PAD, y, GUI_W - PAD * 2,
                Component.translatable("gui.cbc_autotarget.soul.tab.vision.info_action").getString(),
                0xFF9999DD);
    }

    // ── Вкладка Movement ─────────────────────────────────────────────────────

    private static final CommandRole[] MOVEMENT_ROLES = {
            CommandRole.MOVE_FORWARD,
            CommandRole.MOVE_BACKWARD,
            CommandRole.MOVE_LEFT,
            CommandRole.MOVE_RIGHT,
            CommandRole.MOVE_UP,
            CommandRole.MOVE_DOWN
    };

    private void renderMovementContent(GuiGraphics g, int lx, int ty, int mx, int my) {
        // Подсказка над списком
        int hintY = ty + CONTENT_TOP + PAD;
        g.drawString(font,
                Component.translatable("gui.cbc_autotarget.soul.tab.movement.hint"),
                lx + PAD, hintY, COL_TEXT_DIM, false);

        // Строки ролей
        for (int i = 0; i < MOVEMENT_ROLES.length; i++) {
            CommandRole role = MOVEMENT_ROLES[i];
            renderRoleRow(g, lx, ty, role, i, mx, my);
        }
    }

    // ── Вкладка Action ───────────────────────────────────────────────────────

    private void renderActionContent(GuiGraphics g, int lx, int ty, int mx, int my) {
        // Подсказка об угле срабатывания
        int hintY = ty + CONTENT_TOP + PAD;
        drawWrappedText(g, lx + PAD, hintY, GUI_W - PAD * 2,
                Component.translatable("gui.cbc_autotarget.soul.tab.action.hint").getString(),
                COL_TEXT_DIM);

        // Строка FIRE (индекс 0 — первая в своей вкладке)
        renderRoleRow(g, lx, ty, CommandRole.FIRE, 0, mx, my);

        // Диаграмма угла атаки — под строкой FIRE
        int fireRowY = ty + MachineSoulMenu.getRowY(CommandRole.FIRE);
        int diagCX   = lx + GUI_W / 2;
        int diagCY   = fireRowY + ROW_H + 40;
        drawFovDiagram(g, diagCX, diagCY, 30, mx, my);
    }

    // ── Вкладка Target ───────────────────────────────────────────────────────

    /** Y переключателя "Target players" — ниже подсказки. */
    private static final int TARGET_TOGGLE_Y = CONTENT_TOP + PAD + 22;
    private static final int TARGET_TOGGLE_H = ROW_H;

    private void renderTargetContent(GuiGraphics g, int lx, int ty, int mx, int my) {
        // Подсказка
        int hintY = ty + CONTENT_TOP + PAD;
        drawWrappedText(g, lx + PAD, hintY, GUI_W - PAD * 2,
                Component.translatable("gui.cbc_autotarget.soul.tab.target.hint").getString(),
                COL_TEXT_DIM);

        boolean enabled = menu.blockEntity.isTargetPlayers();
        boolean hov = isTargetToggleHovered(mx, my);

        int x = lx + PAD;
        int y = ty + TARGET_TOGGLE_Y;
        int w = GUI_W - PAD * 2;
        int h = TARGET_TOGGLE_H;

        g.fill(x, y, x + w, y + h, hov ? COL_ROW_ODD : COL_ROW_EVEN);
        drawBorder(g, x, y, w, h, enabled ? COL_LINK_OK : COL_TAB_BORDER);

        // Индикатор-точка слева
        int dotR = 3;
        int dotCx = x + 10;
        int dotCy = y + h / 2;
        g.fill(dotCx - dotR, dotCy - dotR, dotCx + dotR, dotCy + dotR,
                enabled ? COL_LINK_OK : COL_LINK_MISS);

        // Текст переключателя
        String label = Component.translatable("gui.cbc_autotarget.soul.tab.target.toggle").getString()
                + ": "
                + (enabled
                ? Component.translatable("gui.cbc_autotarget.soul.tab.target.toggle.on").getString()
                : Component.translatable("gui.cbc_autotarget.soul.tab.target.toggle.off").getString());
        g.drawString(font, label, x + 10 + dotR + 6, y + (h - font.lineHeight) / 2, COL_TEXT, false);
    }

    private boolean isTargetToggleHovered(int mx, int my) {
        int x = leftPos + PAD;
        int y = topPos + TARGET_TOGGLE_Y;
        int w = GUI_W - PAD * 2;
        int h = TARGET_TOGGLE_H;
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /**
     * Рисует одну строку роли (фон, лейбл, точка-статус, фоны слотов).
     * rowIndex — порядковый номер строки на текущей вкладке (для чередования цветов).
     */
    private void renderRoleRow(GuiGraphics g, int lx, int ty,
                               CommandRole role, int rowIndex, int mx, int my) {
        int rowY  = ty + MachineSoulMenu.getRowY(role);
        int rowBg = (rowIndex % 2 == 0) ? COL_ROW_EVEN : COL_ROW_ODD;

        // Фон строки
        g.fill(lx + 1, rowY, lx + GUI_W - 1, rowY + ROW_H, rowBg);
        // Нижний разделитель строки
        g.fill(lx + 2, rowY + ROW_H - 1, lx + GUI_W - 2, rowY + ROW_H, COL_BORDER);

        // Лейбл (вертикально центрирован в строке)
        int textY = rowY + (ROW_H - font.lineHeight) / 2;
        g.drawString(font, roleLabel(role), lx + PAD, textY, COL_TEXT, false);

        // Точка-индикатор статуса линка (слева от слотов)
        boolean linked = linkStatus.getOrDefault(role, false);
        int dotX = lx + MachineSoulMenu.FREQ_SLOT_X0 - 12;
        int dotY = rowY + (ROW_H - 5) / 2;
        g.fill(dotX, dotY, dotX + 5, dotY + 5, linked ? COL_LINK_OK : COL_LINK_MISS);

        // Фоны слотов (точно совпадают с координатами Slot в Menu)
        int slotY = ty + MachineSoulMenu.getSlotScreenY(role);
        drawSlotBg(g, lx + MachineSoulMenu.FREQ_SLOT_X0, slotY);
        drawSlotBg(g, lx + MachineSoulMenu.FREQ_SLOT_X1, slotY);
    }

    // ── FOV-диаграмма ─────────────────────────────────────────────────────────

    private void drawFovDiagram(GuiGraphics g, int cx, int cy, int r, int mx, int my) {
        // Сектор ±90° перед блоком (зона атаки)
        for (int px = cx - r; px <= cx + r; px++) {
            for (int py = cy - r; py <= cy + r; py++) {
                int dx = px - cx;
                int dy = py - cy;
                double dist = Math.sqrt((double)dx * dx + (double)dy * dy);
                if (dist > r - 1) continue;
                double angle = Math.toDegrees(Math.atan2(dx, -dy));
                if (Math.abs(angle) <= 90.0) {
                    g.fill(px, py, px + 1, py + 1, 0x4488AAFF);
                }
            }
        }

        // Контур круга
        drawCircleOutline(g, cx, cy, r, COL_ACCENT);

        // Оси
        g.fill(cx - r, cy, cx + r, cy + 1, 0x80AAAADD);   // горизонталь
        g.fill(cx, cy - r, cx + 1, cy, 0x80DDAA88);        // вперёд

        // Стрелка "вперёд"
        g.fill(cx - 1, cy - r + 4, cx + 2, cy, 0xFFDDAA44);
        g.fill(cx - 3, cy - r + 8, cx + 4, cy - r + 10, 0xFFDDAA44);

        // Подписи
        g.drawString(font, "360°", cx - r - 22, cy - 4, COL_ACCENT, false);
        g.drawString(font, "±90°", cx + 2, cy - r - 10, 0xFF88AAFF, false);
    }

    private void drawCircleOutline(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int a = 0; a < 360; a++) {
            double rad = Math.toRadians(a);
            int px = cx + (int)Math.round(r * Math.sin(rad));
            int py = cy - (int)Math.round(r * Math.cos(rad));
            g.fill(px, py, px + 1, py + 1, color);
        }
    }

    // ── Вкладки (таббар) ──────────────────────────────────────────────────────

    private void drawTab(GuiGraphics g, int lx, int ty, int idx, Tab tab) {
        int tx     = lx + idx * TAB_W;
        boolean active = tab == activeTab;

        g.fill(tx, ty, tx + TAB_W, ty + TAB_H,
                active ? COL_TAB_ACTIVE : COL_TAB_INACTIVE);

        // Граница снизу вкладки
        if (!active) {
            g.fill(tx, ty + TAB_H - 1, tx + TAB_W, ty + TAB_H, COL_TAB_BORDER);
        } else {
            // Перекрываем разделительную линию фоном контента (сливается с содержимым)
            g.fill(tx + 1, ty + TAB_H, tx + TAB_W - 1, ty + TAB_H + 1, COL_CONTENT_BG);
        }

        // Боковые границы
        g.fill(tx, ty, tx + 1, ty + TAB_H, COL_TAB_BORDER);
        if (idx == Tab.values().length - 1) {
            g.fill(tx + TAB_W - 1, ty, tx + TAB_W, ty + TAB_H, COL_TAB_BORDER);
        }

        // Текст вкладки
        String label  = tabLabel(tab);
        int textW     = font.width(label);
        int textX     = tx + (TAB_W - textW) / 2;
        int textY     = ty + (TAB_H - font.lineHeight) / 2;
        g.drawString(font, label, textX, textY,
                active ? COL_TITLE : COL_TEXT_DIM, false);

        // Акцентная полоска активной вкладки
        if (active) {
            g.fill(tx + 4, ty + TAB_H - 2, tx + TAB_W - 4, ty + TAB_H - 1, COL_ACCENT);
        }
    }

    // ── render ────────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        renderBackground(g, mouseX, mouseY, pt);
        super.render(g, mouseX, mouseY, pt);
        renderGhostItems(g, mouseX, mouseY);
        renderTooltip(g, mouseX, mouseY);
    }

    /**
     * Рендерит ghost-предметы в freq-слотах текущей вкладки.
     * Координаты вычисляются так же, как в Menu.addFreqSlots() —
     * leftPos + FREQ_SLOT_X0/X1 и topPos + getSlotScreenY(role).
     */
    private void renderGhostItems(GuiGraphics g, int mouseX, int mouseY) {
        CommandRole[] roles = CommandRole.values();

        for (int i = 0; i < MachineSoulMenu.FREQ_SLOTS; i++) {
            CommandRole role = roles[i / 2];
            if (role.tab() != activeTab) continue;

            Slot slot = menu.slots.get(i);
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;

            // Эти координаты — точная копия того, что Menu передаёт в GhostSlot
            int slotX = (i % 2 == 0) ? MachineSoulMenu.FREQ_SLOT_X0 : MachineSoulMenu.FREQ_SLOT_X1;
            int slotY = MachineSoulMenu.getSlotScreenY(role);

            int x = leftPos  + slotX;
            int y = topPos   + slotY;

            boolean hovered = mouseX >= x && mouseX < x + 16
                    && mouseY >= y && mouseY < y + 16;
            if (hovered) g.fill(x, y, x + 16, y + 16, COL_GHOST_HOVER);

            RenderSystem.enableBlend();
            RenderSystem.setShaderColor(1f, 1f, 1f, 0.5f);
            g.renderItem(stack, x, y);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.disableBlend();

            g.fill(x, y, x + 16, y + 16, COL_GHOST_OVERLAY);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // Намеренно пусто — все лейблы рисуем сами в renderBg
    }

    // ── Клики ────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Клик по вкладкам
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            int tx = leftPos + i * TAB_W;
            if (mouseX >= tx && mouseX < tx + TAB_W
                    && mouseY >= topPos && mouseY < topPos + TAB_H) {
                activeTab = tabs[i];
                updateButtonVisibility();
                return true;
            }
        }

        // Клики по ghost-слотам текущей вкладки
        CommandRole[] roles = CommandRole.values();
        for (int i = 0; i < MachineSoulMenu.FREQ_SLOTS; i++) {
            CommandRole role = roles[i / 2];
            if (role.tab() != activeTab) continue;

            int slotX = (i % 2 == 0) ? MachineSoulMenu.FREQ_SLOT_X0 : MachineSoulMenu.FREQ_SLOT_X1;
            int slotY = MachineSoulMenu.getSlotScreenY(role);
            int x = leftPos  + slotX;
            int y = topPos   + slotY;

            if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                ItemStack carried = menu.getCarried();
                if (button == 1 || carried.isEmpty()) {
                    menu.setBufferItem(i, ItemStack.EMPTY);
                } else {
                    menu.setBufferItem(i, carried);
                }
                return true;
            }
        }

        // Клик по переключателю "Target players" на вкладке Target
        if (activeTab == Tab.TARGET && isTargetToggleHovered((int) mouseX, (int) mouseY)) {
            boolean newValue = !menu.blockEntity.isTargetPlayers();
            menu.blockEntity.setTargetPlayers(newValue); // оптимистично, для мгновенного отклика GUI
            PacketDistributor.sendToServer(
                    new com.yourname.cbcautotarget.network.ToggleMachineSoulTargetPlayersPacket(menu.blockPos));
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    // ── Вспомогательные ───────────────────────────────────────────────────────

    /**
     * Рисует фон слота: рамку COL_SLOT_FR и заливку COL_SLOT_BG.
     * Слот отображается как квадрат (SLOT_SIZE × SLOT_SIZE) с рамкой в 1px.
     * Предметы рендерятся движком поверх этого фона по тем же x,y.
     */
    private void drawSlotBg(GuiGraphics g, int x, int y) {
        // Рамка (1px снаружи от зоны предмета)
        g.fill(x - 1, y - 1, x + SLOT_SIZE + 1, y + SLOT_SIZE + 1, COL_SLOT_FR);
        // Заливка
        g.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, COL_SLOT_BG);
    }

    private static void drawBorder(GuiGraphics g, int x, int y, int w, int h, int col) {
        g.fill(x,         y,         x + w, y + 1,     col);
        g.fill(x,         y + h - 1, x + w, y + h,     col);
        g.fill(x,         y,         x + 1, y + h,     col);
        g.fill(x + w - 1, y,         x + w, y + h,     col);
    }

    private void drawWrappedText(GuiGraphics g, int x, int y, int maxW, String text, int color) {
        var lines = font.split(Component.literal(text), maxW);
        int lineY = y;
        for (var line : lines) {
            g.drawString(font, line, x, lineY, color, false);
            lineY += font.lineHeight + 1;
        }
    }

    private static String tabLabel(Tab tab) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        return switch (tab) {
            case VISION   -> "👁 "  + mc.font.plainSubstrByWidth(
                    Component.translatable("gui.cbc_autotarget.soul.tab.vision").getString(),   34);
            case MOVEMENT -> "⬡ "  + mc.font.plainSubstrByWidth(
                    Component.translatable("gui.cbc_autotarget.soul.tab.movement").getString(), 34);
            case ACTION   -> "⚔ "  + mc.font.plainSubstrByWidth(
                    Component.translatable("gui.cbc_autotarget.soul.tab.action").getString(),   34);
            case TARGET   -> "🎯 " + mc.font.plainSubstrByWidth(
                    Component.translatable("gui.cbc_autotarget.soul.tab.target").getString(),   34);
        };
    }

    private static Component roleLabel(CommandRole role) {
        return switch (role) {
            case FIRE          -> Component.translatable("gui.cbc_autotarget.soul.role.fire");
            case MOVE_FORWARD  -> Component.translatable("gui.cbc_autotarget.soul.role.forward");
            case MOVE_BACKWARD -> Component.translatable("gui.cbc_autotarget.soul.role.backward");
            case MOVE_LEFT     -> Component.translatable("gui.cbc_autotarget.soul.role.left");
            case MOVE_RIGHT    -> Component.translatable("gui.cbc_autotarget.soul.role.right");
            case MOVE_UP       -> Component.translatable("gui.cbc_autotarget.soul.role.up");
            case MOVE_DOWN     -> Component.translatable("gui.cbc_autotarget.soul.role.down");
        };
    }

    @Override
    public boolean isPauseScreen() { return false; }
}