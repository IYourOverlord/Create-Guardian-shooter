package com.yourname.cbcautotarget.client;

import com.yourname.cbcautotarget.filter.TargetFilterData;
import com.yourname.cbcautotarget.filter.WhitelistMode;
import com.yourname.cbcautotarget.network.UpdateMachineSoulPlayerFilterPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Экран фильтра игроков для MachineSoul.
 *
 * Все абсолютные координаты рассчитываются в init() по образцу
 * CommanderWhitelistScreen (рабочая реализация в проекте).
 *
 * Структура панели (px/py — левый верхний угол):
 *
 *  py+ 0  ┌─────────────────────┐  шапка 16px
 *  py+16  ├─────────────────────┤  sep
 *  py+17  │ [☑] Whitelist       │  чекбокс 18px
 *  py+35  ├─────────────────────┤  sep
 *  py+36  │ [Ignore] [Escort]   │  кнопки режима 18px
 *  py+54  │  hint-строка        │  9px
 *  py+63  ├─────────────────────┤  sep
 *  py+64  │  список (прокрутка) │  переменная высота
 *  py+H-49├─────────────────────┤  sep
 *  py+H-48│ [input     ][Add]   │  18px
 *  py+H-26│ [   Done   ]        │  14px
 *  py+H   └─────────────────────┘
 *
 *  PANEL_W = 220, PANEL_H = min(screen_h - 20, 220)
 */
public class MachineSoulPlayerFilterScreen extends Screen {

    private static final int PANEL_W = 220;
    private static final int ITEM_H  = 20;

    // Смещения от py (фиксированные)
    private static final int OFF_SEP1      = 16;   // sep после шапки
    private static final int OFF_CHECKBOX  = 17;   // y чекбокса
    private static final int OFF_SEP2      = 35;   // sep после чекбокса
    private static final int OFF_MODBTN    = 36;   // y кнопок режима
    private static final int OFF_HINT      = 54;   // y hint-строки
    private static final int OFF_SEP3      = 63;   // sep перед списком
    private static final int OFF_LIST_TOP  = 64;   // y начала списка

    // Смещения от (py + PANEL_H) — снизу вверх
    private static final int BOT_SEP       = 49;   // sep над вводом
    private static final int BOT_INPUT     = 48;   // y поля ввода
    private static final int BOT_DONE      = 26;   // y кнопки Done

    // Ширины
    private static final int PAD           = 8;
    private static final int BTN_HALF_GAP  = 3;

    // ── Палитра (зеркалит BaseMachineSoulScreen) ──────────────────────────────
    private static final int COL_BG         = 0xF0060D06;
    private static final int COL_BORDER     = 0xFF1A4A1A;
    private static final int COL_HEADER_BG  = 0xFF0A1F0A;
    private static final int COL_SEP        = 0xFF2A6B2A;
    private static final int COL_LIST_BG    = 0xFF0B260B;
    private static final int COL_TITLE      = 0xFFCCFFCC;
    private static final int COL_TEXT_DIM   = 0xFF507050;
    private static final int COL_HINT_ON    = 0xFF7AAA7A;
    private static final int COL_COUNTER    = 0xFF3A6A3A;
    private static final int COL_ROW_EVEN   = 0xFF0E2E0E;
    private static final int COL_ROW_ODD    = 0xFF0A220A;
    private static final int COL_ROW_HOV    = 0xFF143814;
    private static final int COL_ROW_TEXT   = 0xFFAADDAA;

    // Ignore (янтарный)
    private static final int COL_IGN_BG    = 0xFF181408;
    private static final int COL_IGN_BG_H  = 0xFF252010;
    private static final int COL_IGN_BR    = 0xFF706020;
    private static final int COL_IGN_BR_A  = 0xFFCCBB33;
    private static final int COL_IGN_TX    = 0xFFCCBB66;
    private static final int COL_IGN_TX_A  = 0xFFFFEE88;

    // Escort (зелёный)
    private static final int COL_ESC_BG    = 0xFF081510;
    private static final int COL_ESC_BG_H  = 0xFF102018;
    private static final int COL_ESC_BR    = 0xFF207845;
    private static final int COL_ESC_BR_A  = 0xFF44CC77;
    private static final int COL_ESC_TX    = 0xFF88CCAA;
    private static final int COL_ESC_TX_A  = 0xFFAAFFCC;

    // ── Состояние ─────────────────────────────────────────────────────────────
    private final Screen         parent;
    private final BlockPos       blockPos;
    private final TargetFilterData filterData;
    private WhitelistMode        mode;

    // Рассчитывается в init()
    private int panelX, panelY, panelH;
    // Абсолютные y-координаты (заполняются в init)
    private int absListTop, absListBot, absInputY, absDoneY;

    private Checkbox wlCheckbox;
    private NameList nameList;
    private EditBox  input;

    // ─────────────────────────────────────────────────────────────────────────

    public MachineSoulPlayerFilterScreen(Screen parent, BlockPos blockPos,
                                         TargetFilterData filterData, WhitelistMode mode) {
        super(Component.translatable("gui.cbc_autotarget.soul.player_filter.title"));
        this.parent     = parent;
        this.blockPos   = blockPos;
        this.filterData = filterData;
        this.mode       = mode;
    }

    public BlockPos getBlockPos() { return blockPos; }

    public void applySync(boolean wlEnabled, List<String> names, WhitelistMode newMode) {
        filterData.setWhitelistEnabled(wlEnabled);
        filterData.replaceWhitelist(names);
        this.mode = newMode;
        if (nameList != null) nameList.refresh();
        rebuildCheckbox();
    }

    // ── init ─────────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        // Высота панели: не выше экрана - 20px
        int screenH = minecraft.getWindow().getGuiScaledHeight();
        panelH = Math.min(220, screenH - 20);

        panelX = (width  - PANEL_W) / 2;
        panelY = (height - panelH)  / 2;

        // Абсолютные координаты
        absListTop  = panelY + OFF_LIST_TOP;
        absListBot  = panelY + panelH - BOT_SEP;
        absInputY   = panelY + panelH - BOT_INPUT;
        absDoneY    = panelY + panelH - BOT_DONE;

        // Чекбокс Whitelist
        wlCheckbox = Checkbox.builder(
                        Component.translatable("gui.cbc_autotarget.whitelist.enabled_label"), font)
                .pos(panelX + PAD, panelY + OFF_CHECKBOX)
                .selected(filterData.isWhitelistEnabled())
                .onValueChange((cb, val) -> onToggleWhitelist(val))
                .build();
        addRenderableWidget(wlCheckbox);

        // Список (ключевое: передаём АБСОЛЮТНЫЙ y и высоту)
        int listH = Math.max(ITEM_H, absListBot - absListTop);
        nameList = new NameList(minecraft,
                PANEL_W - PAD * 2,
                listH,
                absListTop,   // ← абсолютный Y, не относительный
                ITEM_H,
                panelX + PAD);
        nameList.refresh();
        addWidget(nameList);

        // Поле ввода
        int inputW = PANEL_W - PAD * 2 - 54;
        input = new EditBox(font,
                panelX + PAD, absInputY,
                inputW, 16,
                Component.translatable("gui.cbc_autotarget.whitelist.input_hint"));
        input.setMaxLength(16);
        input.setHint(Component.translatable("gui.cbc_autotarget.whitelist.input_hint"));
        addRenderableWidget(input);

        // Кнопка Add
        addRenderableWidget(
                Button.builder(Component.translatable("gui.cbc_autotarget.whitelist.add"), b -> onAdd())
                        .pos(panelX + PAD + inputW + 3, absInputY)
                        .size(51, 16)
                        .build()
        );

        // Кнопка Done
        int doneW = 64;
        addRenderableWidget(
                Button.builder(Component.translatable("gui.done"), b -> onClose())
                        .pos(panelX + (PANEL_W - doneW) / 2, absDoneY)
                        .size(doneW, 14)
                        .build()
        );
    }

    private void rebuildCheckbox() {
        if (wlCheckbox == null) return;
        if (wlCheckbox.selected() == filterData.isWhitelistEnabled()) return;
        removeWidget(wlCheckbox);
        wlCheckbox = Checkbox.builder(
                        Component.translatable("gui.cbc_autotarget.whitelist.enabled_label"), font)
                .pos(panelX + PAD, panelY + OFF_CHECKBOX)
                .selected(filterData.isWhitelistEnabled())
                .onValueChange((cb, val) -> onToggleWhitelist(val))
                .build();
        addRenderableWidget(wlCheckbox);
    }

    @Override public void tick() {
        super.tick();
        if (nameList != null) nameList.refresh();
        rebuildCheckbox();
    }

    // ── Действия ──────────────────────────────────────────────────────────────

    private void onToggleWhitelist(boolean enabled) {
        filterData.setWhitelistEnabled(enabled);
        PacketDistributor.sendToServer(UpdateMachineSoulPlayerFilterPacket.setEnabled(blockPos, enabled));
    }

    /**
     * Переключение режима. Кнопки взаимоисключающие:
     * нажатие активной — сброс в TARGET; нажатие другой — сброс текущей и активация новой.
     */
    private void onSetMode(WhitelistMode clicked) {
        WhitelistMode next = (mode == clicked) ? WhitelistMode.TARGET : clicked;
        this.mode = next;
        PacketDistributor.sendToServer(UpdateMachineSoulPlayerFilterPacket.setMode(blockPos, next));
    }

    private void onAdd() {
        String name = input.getValue().trim();
        if (name.isEmpty() || name.length() > 16) { input.setValue(""); return; }
        if (filterData.getWhitelist().contains(name)) { input.setValue(""); return; }
        if (filterData.getWhitelist().size() >= TargetFilterData.MAX_WHITELIST_SIZE) return;
        filterData.addToWhitelist(name);
        if (nameList != null) nameList.refresh();
        input.setValue("");
        PacketDistributor.sendToServer(UpdateMachineSoulPlayerFilterPacket.add(blockPos, name));
    }

    private void onRemove(String name) {
        filterData.removeFromWhitelist(name);
        if (nameList != null) nameList.refresh();
        PacketDistributor.sendToServer(UpdateMachineSoulPlayerFilterPacket.remove(blockPos, name));
    }

    // ── Ввод ─────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn == 0 && handleModeBtnClick(mx, my)) return true;
        if (nameList != null && nameList.mouseClicked(mx, my, btn)) return true;
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (nameList != null
                && my >= absListTop && my <= absListBot) {
            return nameList.mouseScrolled(mx, my, dx, dy);
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    private boolean handleModeBtnClick(double mx, double my) {
        int btnY  = panelY + OFF_MODBTN;
        int btnH  = 18;
        int halfW = (PANEL_W - PAD * 2 - BTN_HALF_GAP) / 2;
        int x1    = panelX + PAD;
        int x2    = x1 + halfW + BTN_HALF_GAP;

        if (my >= btnY && my < btnY + btnH) {
            if (mx >= x1 && mx < x1 + halfW) { onSetMode(WhitelistMode.IGNORE); return true; }
            if (mx >= x2 && mx < x2 + halfW) { onSetMode(WhitelistMode.FOLLOW); return true; }
        }
        return false;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (input != null && input.isFocused()) {
            if (key == 256) { onClose(); return true; }
            if (key == 257) { onAdd();   return true; }
            return input.keyPressed(key, scan, mods);
        }
        if (key == 256) { onClose(); return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override public void onClose()          { Minecraft.getInstance().setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }

    // ── Рендер ───────────────────────────────────────────────────────────────

    @Override
    public void renderBlurredBackground(float pt) { /* no-op: отключаем системный blur-шейдер */ }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0xC0101010); // затемнение фона без системного blur
        drawPanel(g, mx, my);          // фон панели, рамка, разделители (без кнопок режима)
        if (nameList != null) nameList.render(g, mx, my, pt);
        super.render(g, mx, my, pt);   // чекбокс, поле ввода, Add, Done
        drawModeButtons(g, mx, my);    // Ignore/Escort — поверх списка, самый верхний слой
    }

    private void drawPanel(GuiGraphics g, int mx, int my) {
        int x = panelX, y = panelY, w = PANEL_W, h = panelH;

        // Фон
        g.fill(x, y, x + w, y + h, COL_BG);

        // Рамка
        g.fill(x,         y,         x + w,     y + 1,     COL_BORDER);
        g.fill(x,         y + h - 1, x + w,     y + h,     COL_BORDER);
        g.fill(x,         y,         x + 1,     y + h,     COL_BORDER);
        g.fill(x + w - 1, y,         x + w,     y + h,     COL_BORDER);

        // Шапка
        g.fill(x + 1, y + 1, x + w - 1, y + OFF_SEP1, COL_HEADER_BG);
        g.drawCenteredString(font, title, x + w / 2, y + 4, COL_TITLE);

        // Разделители
        hline(g, x, y + OFF_SEP1, w);  // после шапки
        hline(g, x, y + OFF_SEP2, w);  // после чекбокса
        hline(g, x, y + OFF_SEP3, w);  // перед списком
        hline(g, x, y + h - BOT_SEP, w); // перед вводом


        // Hint
        String raw = switch (mode) {
            case TARGET -> Component.translatable("gui.cbc_autotarget.soul.player_filter.hint.target").getString();
            case IGNORE -> Component.translatable("gui.cbc_autotarget.soul.player_filter.hint.ignore").getString();
            case FOLLOW -> Component.translatable("gui.cbc_autotarget.soul.player_filter.hint.follow").getString();
        };
        int maxW = w - PAD * 2;
        if (font.width(raw) > maxW) raw = font.plainSubstrByWidth(raw, maxW - font.width("…")) + "…";
        int hintCol = (mode == WhitelistMode.TARGET) ? COL_TEXT_DIM : COL_HINT_ON;
        g.drawCenteredString(font, raw, x + w / 2, y + OFF_HINT + 1, hintCol);

        // Фон списка
        g.fill(x + PAD, absListTop, x + w - PAD, absListBot, COL_LIST_BG);

        // Счётчик
        String cnt = filterData.getWhitelist().size() + " / " + TargetFilterData.MAX_WHITELIST_SIZE;
        g.drawString(font, cnt, x + w - PAD - font.width(cnt), absListBot - font.lineHeight - 1, COL_COUNTER, false);
    }

    private void drawModeButtons(GuiGraphics g, int mx, int my) {
        int btnY  = panelY + OFF_MODBTN;
        int btnH  = 18;
        int halfW = (PANEL_W - PAD * 2 - BTN_HALF_GAP) / 2;
        int x1    = panelX + PAD;
        int x2    = x1 + halfW + BTN_HALF_GAP;

        boolean hovIgn = mx >= x1 && mx < x1 + halfW && my >= btnY && my < btnY + btnH;
        boolean hovEsc = mx >= x2 && mx < x2 + halfW && my >= btnY && my < btnY + btnH;
        boolean actIgn = (mode == WhitelistMode.IGNORE);
        boolean actEsc = (mode == WhitelistMode.FOLLOW);

        // — Ignore —
        int bgI = (actIgn || hovIgn) ? COL_IGN_BG_H : COL_IGN_BG;
        int brI = (actIgn || hovIgn) ? COL_IGN_BR_A  : COL_IGN_BR;
        int txI = actIgn             ? COL_IGN_TX_A  : COL_IGN_TX;
        g.fill(x1, btnY, x1 + halfW, btnY + btnH, bgI);
        border(g, x1, btnY, halfW, btnH, brI);
        // Левая полоска-индикатор активности
        if (actIgn) g.fill(x1 + 1, btnY + 1, x1 + 3, btnY + btnH - 1, COL_IGN_BR_A);
        String lI = (actIgn ? "\u25CF " : "\u25CB ")
                + Component.translatable("gui.cbc_autotarget.soul.player_filter.ignore").getString();
        g.drawCenteredString(font, lI, x1 + halfW / 2, btnY + (btnH - font.lineHeight) / 2, txI);

        // — Escort —
        int bgE = (actEsc || hovEsc) ? COL_ESC_BG_H : COL_ESC_BG;
        int brE = (actEsc || hovEsc) ? COL_ESC_BR_A  : COL_ESC_BR;
        int txE = actEsc             ? COL_ESC_TX_A  : COL_ESC_TX;
        g.fill(x2, btnY, x2 + halfW, btnY + btnH, bgE);
        border(g, x2, btnY, halfW, btnH, brE);
        if (actEsc) g.fill(x2 + 1, btnY + 1, x2 + 3, btnY + btnH - 1, COL_ESC_BR_A);
        String lE = (actEsc ? "\u25CF " : "\u25CB ")
                + Component.translatable("gui.cbc_autotarget.soul.player_filter.follow").getString();
        g.drawCenteredString(font, lE, x2 + halfW / 2, btnY + (btnH - font.lineHeight) / 2, txE);
    }

    private void hline(GuiGraphics g, int x, int y, int w) {
        g.fill(x + 4, y, x + w - 4, y + 1, COL_SEP);
    }

    private void border(GuiGraphics g, int x, int y, int w, int h, int c) {
        g.fill(x,         y,         x + w,     y + 1,     c);
        g.fill(x,         y + h - 1, x + w,     y + h,     c);
        g.fill(x,         y,         x + 1,     y + h,     c);
        g.fill(x + w - 1, y,         x + w,     y + h,     c);
    }

    // ── Список ────────────────────────────────────────────────────────────────

    private class NameList extends ObjectSelectionList<NameList.Entry> {

        NameList(Minecraft mc, int w, int h, int y, int ih, int x) {
            super(mc, w, h, y, ih);   // y — абсолютный Y (верх видимой области)
            setX(x);
        }

        void refresh() {
            clearEntries();
            new ArrayList<>(filterData.getWhitelist()).forEach(n -> addEntry(new Entry(n)));
        }

        @Override public int getRowWidth() { return PANEL_W - PAD * 2; }
        @Override protected int getScrollbarPosition() { return panelX + PANEL_W - PAD - 5; }

        // Полностью свой рендер строк — без встроенного блюра/затемнения списка.
        @Override
        public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            int rowLeft  = getRowLeft();
            int rowWidth = getRowWidth();
            int top      = getY();

            g.enableScissor(getX(), getY(), getX() + getWidth(), getY() + getHeight());

            int count = getItemCount();
            for (int i = 0; i < count; i++) {
                int rowTop = top + i * itemHeight - (int) getScrollAmount();
                if (rowTop + itemHeight < getY() || rowTop > getY() + getHeight()) continue;
                Entry e = getEntry(i);
                boolean hov = isMouseOver(mx, my) && getEntryAtPosition(mx, my) == e;
                e.render(g, i, rowTop, rowLeft, rowWidth, itemHeight, mx, my, hov, pt);
            }

            g.disableScissor();
        }

        class Entry extends ObjectSelectionList.Entry<Entry> {
            private final String name;
            private final Button removeBtn;

            Entry(String n) {
                this.name = n;
                this.removeBtn = Button.builder(
                        Component.translatable("gui.cbc_autotarget.whitelist.remove"),
                        b -> onRemove(n))
                        .size(46, ITEM_H - 6).build();
            }

            @Override
            public void render(GuiGraphics g, int idx, int top, int left,
                               int w, int h, int mx, int my, boolean hov, float pt) {
                g.fill(left, top, left + w, top + h,
                        idx % 2 == 0 ? COL_ROW_EVEN : COL_ROW_ODD);
                if (hov) g.fill(left, top, left + w, top + h, COL_ROW_HOV);
                g.drawString(font, name, left + 4, top + (h - font.lineHeight) / 2, COL_ROW_TEXT, true);
                removeBtn.setX(left + w - 48);
                removeBtn.setY(top + 3);
                removeBtn.render(g, mx, my, pt);
            }

            @Override public boolean mouseClicked(double mx, double my, int b) {
                return removeBtn.mouseClicked(mx, my, b);
            }
            @Override public Component getNarration() { return Component.literal(name); }
        }
    }
}
