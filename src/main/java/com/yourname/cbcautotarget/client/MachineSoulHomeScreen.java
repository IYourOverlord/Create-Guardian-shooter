package com.yourname.cbcautotarget.client;

import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.Tab;
import com.yourname.cbcautotarget.menu.MachineSoulHomeMenu;
import com.yourname.cbcautotarget.network.SwitchMachineSoulTabPacket;
import com.yourname.cbcautotarget.network.ToggleMachineSoulSearchPacket;
import com.yourname.cbcautotarget.network.ToggleMachineSoulSubLevelOnlyPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Главная страница (хаб) GUI Machine Soul — компактная версия.
 *
 * Отображает:
 *   - кнопку-переключатель "Поиск цели: ВКЛ/ВЫКЛ"
 *   - кнопку-переключатель "Только на физической конструкции: ВКЛ/ВЫКЛ"
 *     (приоритетнее кнопки "Поиск цели": если включена, блок работает
 *     только пока находится на Sable sub-level, независимо от состояния
 *     кнопки "Поиск цели")
 *   - карточки-ссылки на каждую из вкладок:
 *       👁  Vision   — настройка радиуса, дистанции
 *       ⬡  Move     — частотные слоты для движения
 *       ⚔  Action   — слот огня, угол атаки
 *       🎯  Target   — таргетинг игроков
 *
 * Геометрия:
 *   GUI_W = 200, GUI_H = 222
 *   Шапка: y=0..30 (заголовок y=5, подчёркивание y=15, подзаголовок y=18)
 *   Кнопка "Поиск цели": y=33..49 (TOGGLE_Y=33, TOGGLE_H=16)
 *   Кнопка "Только на физической конструкции": y=52..68 (SUBLEVEL_TOGGLE_Y=52, TOGGLE_H=16)
 *   Карточки: начало y=71, высота каждой CARD_H=32, отступ CARD_GAP=5
 */
public class MachineSoulHomeScreen extends AbstractContainerScreen<MachineSoulHomeMenu> {

    // ── Размеры ───────────────────────────────────────────────────────────────
    private static final int GUI_W      = 200;
    private static final int HEADER_H   = 30;
    private static final int PAD        = 8;
    private static final int CARD_H     = 32;
    private static final int CARD_GAP   = 5;
    private static final int ICON_SIZE  = 22;

    // ── Кнопка "Поиск цели" ──────────────────────────────────────────────────
    private static final int TOGGLE_Y = HEADER_H + 3;  // 33
    private static final int TOGGLE_H = 16;

    // ── Кнопка "Только на физической конструкции" (под кнопкой "Поиск цели") ──
    private static final int SUBLEVEL_TOGGLE_Y = TOGGLE_Y + TOGGLE_H + 3; // 52

    // Карточки начинаются после обеих кнопок-переключателей
    private static final int CARDS_Y  = SUBLEVEL_TOGGLE_Y + TOGGLE_H + 5; // 71

    private static final int GUI_H    = CARDS_Y + 4 * (CARD_H + CARD_GAP) - CARD_GAP + 8; // 222

    // ── Цвета (тёмно-зелёная палитра — идентична BaseMachineSoulScreen) ───────
    private static final int COL_BG          = 0xE0080F08;
    private static final int COL_BORDER      = 0xFF1A4A1A;
    private static final int COL_CONTENT_BG  = 0xFF0D2B0D;
    private static final int COL_TITLE       = 0xFFCCFFCC;
    private static final int COL_TEXT        = 0xFFAADDAA;
    private static final int COL_TEXT_DIM    = 0xFF5A8A5A;
    private static final int COL_ACCENT      = 0xFF44BB44;
    private static final int COL_ACCENT2     = 0xFF33FF88;
    private static final int COL_CARD_BG     = 0xFF0F320F;
    private static final int COL_CARD_HOVER  = 0xFF153D15;
    private static final int COL_CARD_BORDER = 0xFF2A6B2A;
    private static final int COL_CARD_BORDER_HOVER = 0xFF44BB44;
    private static final int COL_ICON_BG     = 0xFF0A250A;

    // Цвета кнопки "Поиск цели"
    private static final int COL_TOGGLE_ON_BG          = 0xFF153D15;
    private static final int COL_TOGGLE_ON_BORDER      = 0xFF44FF66;
    private static final int COL_TOGGLE_ON_BORDER_HOVER = 0xFF66FF88;
    private static final int COL_TOGGLE_ON_TEXT        = 0xFFAAFFBB;
    private static final int COL_TOGGLE_OFF_BG          = 0xFF2A1212;
    private static final int COL_TOGGLE_OFF_BORDER      = 0xFFBB4444;
    private static final int COL_TOGGLE_OFF_BORDER_HOVER = 0xFFFF6666;
    private static final int COL_TOGGLE_OFF_TEXT        = 0xFFFFAAAA;
    private static final int COL_TOGGLE_DOT_ON  = 0xFF44FF66;
    private static final int COL_TOGGLE_DOT_OFF = 0xFFFF5555;

    // Цвета кнопки "Только на физической конструкции" (синеватая палитра — отличается от зелёной "Поиск цели")
    private static final int COL_SUBLEVEL_ON_BG           = 0xFF12283D;
    private static final int COL_SUBLEVEL_ON_BORDER       = 0xFF4499FF;
    private static final int COL_SUBLEVEL_ON_BORDER_HOVER = 0xFF66BBFF;
    private static final int COL_SUBLEVEL_ON_TEXT         = 0xFFAADDFF;
    private static final int COL_SUBLEVEL_OFF_BG           = 0xFF1A1A1A;
    private static final int COL_SUBLEVEL_OFF_BORDER       = 0xFF666666;
    private static final int COL_SUBLEVEL_OFF_BORDER_HOVER = 0xFF999999;
    private static final int COL_SUBLEVEL_OFF_TEXT         = 0xFFAAAAAA;
    private static final int COL_SUBLEVEL_DOT_ON  = 0xFF44AAFF;
    private static final int COL_SUBLEVEL_DOT_OFF = 0xFF888888;

    private final BlockPos blockPos;

    // Индекс карточки под курсором (-1 = нет)
    private int hoveredCard = -1;
    private boolean hoveredToggle = false;
    private boolean hoveredSubLevelToggle = false;

    // Локальное (оптимистичное) состояние флага поиска цели
    private boolean searchActive;

    // Локальное (оптимистичное) состояние режима "Только на физической конструкции"
    private boolean requireSubLevel;

    // ── Данные карточек ───────────────────────────────────────────────────────
    private record CardEntry(Tab tab, String icon, String titleKey, String descKey) {}

    private static final CardEntry[] CARDS = {
            new CardEntry(Tab.VISION,   "👁",  "gui.cbc_autotarget.home.card.vision.title",   "gui.cbc_autotarget.home.card.vision.desc"),
            new CardEntry(Tab.MOVEMENT, "⬡",  "gui.cbc_autotarget.home.card.movement.title", "gui.cbc_autotarget.home.card.movement.desc"),
            new CardEntry(Tab.ACTION,   "⚔",  "gui.cbc_autotarget.home.card.action.title",   "gui.cbc_autotarget.home.card.action.desc"),
            new CardEntry(Tab.TARGET,   "🎯", "gui.cbc_autotarget.home.card.target.title",   "gui.cbc_autotarget.home.card.target.desc"),
    };

    public MachineSoulHomeScreen(MachineSoulHomeMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.blockPos   = menu.blockPos;
        this.searchActive = menu.isTargetSearchActive();
        this.requireSubLevel = menu.isRequireSubLevel();
        imageWidth      = GUI_W;
        imageHeight     = GUI_H;
        inventoryLabelY = GUI_H + 500;
        titleLabelY     = GUI_H + 500;
    }

    // ── Рендер фона ───────────────────────────────────────────────────────────

    @Override
    protected void renderBg(GuiGraphics g, float pt, int mouseX, int mouseY) {
        int lx = leftPos;
        int ty = topPos;

        // Основной фон + граница
        g.fill(lx, ty, lx + GUI_W, ty + GUI_H, COL_BG);
        drawBorder(g, lx, ty, GUI_W, GUI_H, COL_BORDER);

        // Шапка с названием блока
        g.fill(lx + 1, ty + 1, lx + GUI_W - 1, ty + HEADER_H, COL_CONTENT_BG);

        // Декоративная акцентная полоска снизу шапки
        g.fill(lx + 1, ty + HEADER_H, lx + GUI_W - 1, ty + HEADER_H + 1, COL_CARD_BORDER);

        // Заголовок
        String title = Component.translatable("gui.cbc_autotarget.home.title").getString();
        int titleW = font.width(title);
        g.drawString(font, title, lx + (GUI_W - titleW) / 2, ty + 5, COL_TITLE, false);

        // Акцентная подчёркивающая линия под заголовком
        g.fill(lx + (GUI_W - titleW) / 2, ty + 15, lx + (GUI_W + titleW) / 2, ty + 16, COL_ACCENT);

        // Подзаголовок-подсказка
        String sub = Component.translatable("gui.cbc_autotarget.home.subtitle").getString();
        int subW = font.width(sub);
        g.drawString(font, sub, lx + (GUI_W - subW) / 2, ty + 18, COL_TEXT_DIM, false);

        // Обновить состояния под курсором перед рисованием
        hoveredCard            = getHoveredCard(mouseX, mouseY);
        hoveredToggle          = isHoveredToggle(mouseX, mouseY);
        hoveredSubLevelToggle  = isHoveredSubLevelToggle(mouseX, mouseY);

        // Кнопка "Поиск цели"
        drawSearchToggle(g, lx, ty, mouseX, mouseY);

        // Кнопка "Только на физической конструкции"
        drawSubLevelToggle(g, lx, ty, mouseX, mouseY);

        // Карточки
        for (int i = 0; i < CARDS.length; i++) {
            drawCard(g, lx, ty, i, CARDS[i], i == hoveredCard, mouseX, mouseY);
        }
    }

    // ── Кнопка "Поиск цели" ──────────────────────────────────────────────────

    private void drawSearchToggle(GuiGraphics g, int lx, int ty, int mx, int my) {
        int x = lx + PAD;
        int y = ty + TOGGLE_Y;
        int w = GUI_W - PAD * 2;
        int h = TOGGLE_H;

        int bg     = searchActive ? COL_TOGGLE_ON_BG  : COL_TOGGLE_OFF_BG;
        int border = searchActive
                ? (hoveredToggle ? COL_TOGGLE_ON_BORDER_HOVER  : COL_TOGGLE_ON_BORDER)
                : (hoveredToggle ? COL_TOGGLE_OFF_BORDER_HOVER : COL_TOGGLE_OFF_BORDER);
        int textCol = searchActive ? COL_TOGGLE_ON_TEXT : COL_TOGGLE_OFF_TEXT;

        g.fill(x, y, x + w, y + h, bg);
        drawBorder(g, x, y, w, h, border);

        // Индикатор-точка слева
        int dotR = 3;
        int dotCx = x + 9;
        int dotCy = y + h / 2;
        int dotCol = searchActive ? COL_TOGGLE_DOT_ON : COL_TOGGLE_DOT_OFF;
        g.fill(dotCx - dotR, dotCy - dotR, dotCx + dotR, dotCy + dotR, dotCol);

        // Текст кнопки
        String label = Component.translatable("gui.cbc_autotarget.home.search_toggle").getString()
                + ": "
                + (searchActive
                ? Component.translatable("gui.cbc_autotarget.home.search_toggle.on").getString()
                : Component.translatable("gui.cbc_autotarget.home.search_toggle.off").getString());
        g.drawString(font, label, x + 9 + dotR + 5, y + (h - font.lineHeight) / 2, textCol, false);
    }

    private boolean isHoveredToggle(int mx, int my) {
        int x = leftPos + PAD;
        int y = topPos + TOGGLE_Y;
        int w = GUI_W - PAD * 2;
        int h = TOGGLE_H;
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ── Кнопка "Только на физической конструкции" ───────────────────────────

    private void drawSubLevelToggle(GuiGraphics g, int lx, int ty, int mx, int my) {
        int x = lx + PAD;
        int y = ty + SUBLEVEL_TOGGLE_Y;
        int w = GUI_W - PAD * 2;
        int h = TOGGLE_H;

        int bg     = requireSubLevel ? COL_SUBLEVEL_ON_BG  : COL_SUBLEVEL_OFF_BG;
        int border = requireSubLevel
                ? (hoveredSubLevelToggle ? COL_SUBLEVEL_ON_BORDER_HOVER  : COL_SUBLEVEL_ON_BORDER)
                : (hoveredSubLevelToggle ? COL_SUBLEVEL_OFF_BORDER_HOVER : COL_SUBLEVEL_OFF_BORDER);
        int textCol = requireSubLevel ? COL_SUBLEVEL_ON_TEXT : COL_SUBLEVEL_OFF_TEXT;

        g.fill(x, y, x + w, y + h, bg);
        drawBorder(g, x, y, w, h, border);

        // Индикатор-точка слева
        int dotR = 3;
        int dotCx = x + 9;
        int dotCy = y + h / 2;
        int dotCol = requireSubLevel ? COL_SUBLEVEL_DOT_ON : COL_SUBLEVEL_DOT_OFF;
        g.fill(dotCx - dotR, dotCy - dotR, dotCx + dotR, dotCy + dotR, dotCol);

        // Текст кнопки
        String label = Component.translatable("gui.cbc_autotarget.home.sublevel_toggle").getString()
                + ": "
                + (requireSubLevel
                ? Component.translatable("gui.cbc_autotarget.home.search_toggle.on").getString()
                : Component.translatable("gui.cbc_autotarget.home.search_toggle.off").getString());
        g.drawString(font, label, x + 9 + dotR + 5, y + (h - font.lineHeight) / 2, textCol, false);
    }

    private boolean isHoveredSubLevelToggle(int mx, int my) {
        int x = leftPos + PAD;
        int y = topPos + SUBLEVEL_TOGGLE_Y;
        int w = GUI_W - PAD * 2;
        int h = TOGGLE_H;
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ── Карточки ─────────────────────────────────────────────────────────────

    private void drawCard(GuiGraphics g, int lx, int ty, int idx, CardEntry card,
                          boolean hovered, int mx, int my) {
        int cardX = lx + PAD;
        int cardY = ty + CARDS_Y + idx * (CARD_H + CARD_GAP);
        int cardW = GUI_W - PAD * 2;

        // Фон карточки
        g.fill(cardX, cardY, cardX + cardW, cardY + CARD_H,
                hovered ? COL_CARD_HOVER : COL_CARD_BG);

        // Граница карточки
        drawBorder(g, cardX, cardY, cardW, CARD_H,
                hovered ? COL_CARD_BORDER_HOVER : COL_CARD_BORDER);

        // Если наведена — тонкая светящаяся рамка внутри
        if (hovered) {
            g.fill(cardX + 1, cardY + 1, cardX + cardW - 1, cardY + 2, 0x3044BB44);
            g.fill(cardX + 1, cardY + CARD_H - 2, cardX + cardW - 1, cardY + CARD_H - 1, 0x3044BB44);
        }

        // Иконка-блок слева (квадрат ICON_SIZE×ICON_SIZE)
        int iconBx = cardX + 5;
        int iconBy = cardY + (CARD_H - ICON_SIZE) / 2;
        g.fill(iconBx, iconBy, iconBx + ICON_SIZE, iconBy + ICON_SIZE, COL_ICON_BG);
        drawBorder(g, iconBx, iconBy, ICON_SIZE, ICON_SIZE, hovered ? COL_ACCENT : COL_CARD_BORDER);

        // Символ иконки (центрируем в квадрате)
        int iconTW = font.width(card.icon());
        int iconTH = font.lineHeight;
        g.drawString(font, card.icon(),
                iconBx + (ICON_SIZE - iconTW) / 2,
                iconBy + (ICON_SIZE - iconTH) / 2,
                hovered ? COL_ACCENT2 : COL_ACCENT, false);

        // Текст: название и описание
        int textX = iconBx + ICON_SIZE + 6;
        int textW = cardW - (textX - cardX) - 6;

        String titleStr = Component.translatable(card.titleKey()).getString();
        g.drawString(font, titleStr, textX, cardY + 5, hovered ? COL_ACCENT2 : COL_TITLE, false);

        // Описание (уменьшенный цвет)
        String descStr = Component.translatable(card.descKey()).getString();
        // Обрезаем если не влезает
        String descTrunc = font.plainSubstrByWidth(descStr, textW);
        g.drawString(font, descTrunc, textX, cardY + 5 + font.lineHeight + 2, COL_TEXT_DIM, false);

        // Стрелка вправо (индикатор перехода) — справа по центру
        int arrowX = cardX + cardW - 12;
        int arrowY = cardY + (CARD_H - font.lineHeight) / 2;
        g.drawString(font, "›", arrowX, arrowY,
                hovered ? COL_ACCENT2 : COL_CARD_BORDER, false);
    }

    // ── render ────────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        renderBackground(g, mouseX, mouseY, pt);
        super.render(g, mouseX, mouseY, pt);
        renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // Всё рисуем в renderBg — стандартные лейблы скрыты
    }

    // ── Клики ─────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (isHoveredToggle((int) mouseX, (int) mouseY)) {
                // Оптимистичное переключение локального состояния — мгновенный отклик в GUI.
                searchActive = !searchActive;
                menu.setTargetSearchActive(searchActive);
                PacketDistributor.sendToServer(new ToggleMachineSoulSearchPacket(blockPos));
                return true;
            }

            if (isHoveredSubLevelToggle((int) mouseX, (int) mouseY)) {
                // Оптимистичное переключение локального состояния — мгновенный отклик в GUI.
                requireSubLevel = !requireSubLevel;
                menu.setRequireSubLevel(requireSubLevel);
                PacketDistributor.sendToServer(new ToggleMachineSoulSubLevelOnlyPacket(blockPos));
                return true;
            }

            int idx = getHoveredCard((int) mouseX, (int) mouseY);
            if (idx >= 0) {
                PacketDistributor.sendToServer(
                        new SwitchMachineSoulTabPacket(blockPos, CARDS[idx].tab()));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // ── Утилиты ───────────────────────────────────────────────────────────────

    /** Возвращает индекс карточки под координатами, или -1. */
    private int getHoveredCard(int mx, int my) {
        int lx = leftPos;
        int ty = topPos;
        int cardX = lx + PAD;
        int cardW = GUI_W - PAD * 2;
        for (int i = 0; i < CARDS.length; i++) {
            int cardY = ty + CARDS_Y + i * (CARD_H + CARD_GAP);
            if (mx >= cardX && mx < cardX + cardW && my >= cardY && my < cardY + CARD_H)
                return i;
        }
        return -1;
    }

    private static void drawBorder(GuiGraphics g, int x, int y, int w, int h, int col) {
        g.fill(x,         y,         x + w, y + 1,     col);
        g.fill(x,         y + h - 1, x + w, y + h,     col);
        g.fill(x,         y,         x + 1, y + h,     col);
        g.fill(x + w - 1, y,         x + w, y + h,     col);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}