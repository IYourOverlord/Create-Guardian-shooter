package com.yourname.cbcautotarget.client;

import com.yourname.cbcautotarget.filter.CommanderFilterData;
import com.yourname.cbcautotarget.filter.TargetCategory;
import com.yourname.cbcautotarget.filter.TargetFilterData;
import com.yourname.cbcautotarget.filter.WhitelistMode;
import com.yourname.cbcautotarget.network.UpdateMachineSoulCommanderFilterPacket;
import com.yourname.cbcautotarget.network.UpdateMachineSoulPlayerFilterPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Экран фильтра целей Machine Soul.
 *
 * Раньше это был отдельный {@link Screen}, который Minecraft рисовал поверх
 * основного окна Machine Soul вместе со стандартным затемняющим/блюрящим
 * фоном ({@link #renderBackground}). Из-за этого на экране было два
 * визуально разных, независимых слоя: размытый фон-скриншот позади и
 * маленькая тёмно-серая ванильная панель поверх него — стиль совершенно не
 * совпадал с остальными вкладками (Vision/Move/Action), которые рисуются
 * внутри одного общего окна {@link BaseMachineSoulScreen}.
 *
 * Исправление: этот экран больше не рисует ванильный фон и не является
 * отдельным «попапом». Он повторяет ГЕОМЕТРИЮ и ПАЛИТРУ главного окна
 * Machine Soul (GUI_W=200, та же зелёная тема, та же шапка с [←] и
 * названием по центру) и рисуется в той же самой позиции экрана
 * (leftPos/topPos), которую занимало окно вкладки Target. Визуально это
 * теперь выглядит как ещё одна вкладка того же окна, а не как всплывающее
 * окно поверх него.
 *
 * Содержит:
 *  - чекбоксы категорий целей (HOSTILE / PASSIVE / PLAYERS)
 *  - кнопку "Manage Whitelist..." (список конкретных игроков)
 *  - встроенное поле фильтра дружественных командеров: строка ввода
 *    ID + кнопка "Add" + компактный прокручиваемый список уже добавленных
 *    ID с кнопками удаления. Любой обнаруженный командер, чей ID НЕ в этом
 *    списке, становится целью Machine Soul.
 *
 * Компоновка сверху вниз:
 *   шапка ([←] Target Filter)
 *   чекбоксы (HOSTILE/PASSIVE/PLAYERS)
 *   [Manage Whitelist...]
 *   разделитель + заголовок "Friendly Commanders"
 *   [ABCD____][Add]
 *   компактный список добавленных ID (прокрутка)
 *   [Done]
 */
public class MachineSoulTargetFilterScreen extends Screen {

    // Категории, доступные Machine Soul (ENEMY_COMMANDERS исключена — вражда
    // с командерами управляется отдельным списком "дружественных" ID ниже).
    private static final TargetCategory[] CATEGORIES = {
            TargetCategory.HOSTILE,
            TargetCategory.PASSIVE,
            TargetCategory.PLAYERS
    };

    private static final int CHECK_STEP = 22;

    // ── Геометрия и палитра — совпадают с BaseMachineSoulScreen ──────────────
    private static final int GUI_W      = BaseMachineSoulScreen.GUI_W;   // 200
    private static final int PAD        = BaseMachineSoulScreen.PAD;    // 6
    private static final int HEADER_H   = BaseMachineSoulScreen.HEADER_H; // 16
    private static final int BTN_Y      = BaseMachineSoulScreen.BTN_Y;
    private static final int BTN_H      = BaseMachineSoulScreen.BTN_H;
    private static final int BACK_W     = BaseMachineSoulScreen.BACK_W;
    private static final int BACK_X     = BaseMachineSoulScreen.BACK_X;
    private static final int CONTENT_TOP = BaseMachineSoulScreen.CONTENT_TOP;
    private static final int FOOTER_H   = 24;
    private static final int SIDE_PAD   = PAD;

    private static final int COL_BG            = BaseMachineSoulScreen.COL_BG;
    private static final int COL_BORDER        = BaseMachineSoulScreen.COL_BORDER;
    private static final int COL_HEADER_BG     = BaseMachineSoulScreen.COL_HEADER_BG;
    private static final int COL_HEADER_SEP    = BaseMachineSoulScreen.COL_HEADER_SEP;
    private static final int COL_CONTENT_BG    = BaseMachineSoulScreen.COL_CONTENT_BG;
    private static final int COL_TITLE         = BaseMachineSoulScreen.COL_TITLE;
    private static final int COL_TEXT_DIM      = BaseMachineSoulScreen.COL_TEXT_DIM;
    private static final int COL_ACCENT        = BaseMachineSoulScreen.COL_ACCENT;
    private static final int COL_ACCENT2       = BaseMachineSoulScreen.COL_ACCENT2;
    private static final int COL_BACK_BG       = BaseMachineSoulScreen.COL_BACK_BG;
    private static final int COL_BACK_BORDER   = BaseMachineSoulScreen.COL_BACK_BORDER;
    private static final int COL_BACK_HOVER_BG = BaseMachineSoulScreen.COL_BACK_HOVER_BG;
    private static final int SECTION_TITLE_CLR = 0xFF88DDFF; // акцент секции командеров — в тон общей теме

    // ── Секция "Friendly Commanders" ─────────────────────────────────────────
    private static final int CMDR_SECTION_TITLE_H = 12;
    private static final int CMDR_INPUT_ROW_H     = 18;
    private static final int CMDR_LIST_H          = 54;  // ~3 строки видно, дальше скролл
    private static final int CMDR_LIST_ITEM_H     = 18;
    private static final int CMDR_GAP             = 4;

    private static final int COL_LIST_BG    = 0xFF071A12;
    private static final int COL_ROW_EVEN   = 0xFF0A2418;
    private static final int COL_ROW_ODD    = 0xFF081E14;
    private static final int COL_ROW_HOV    = 0xFF0F3020;
    private static final int COL_ROW_TEXT   = BaseMachineSoulScreen.COL_TEXT;
    private static final int COL_ROW_BADGE  = COL_ACCENT2;
    private static final int COL_COUNTER    = 0xFF2E7A4A;

    private final Screen              parent;
    private final BlockPos            soulPos;
    private final TargetFilterData    filterData;
    private final WhitelistMode       whitelistMode;
    private final CommanderFilterData commanderFilterData;
    private int                       localMask;

    /** Позиция окна — совпадает с окном родительской вкладки Target, чтобы
     *  панель рисовалась ровно на том же месте и не "прыгала". */
    private final int parentLeftPos;
    private final int parentTopPos;

    private final List<Checkbox>       checkboxes = new ArrayList<>();
    private final List<TargetCategory> categories = new ArrayList<>();
    private Button whitelistButton;

    private EditBox   commanderIdInput;
    private CommanderIdList commanderIdList;

    private int px, py; // левый верхний угол панели (кэш из init())

    public MachineSoulTargetFilterScreen(Screen parent, BlockPos soulPos,
                                          TargetFilterData filterData, WhitelistMode whitelistMode,
                                          CommanderFilterData commanderFilterData) {
        this(parent, soulPos, filterData, whitelistMode, commanderFilterData, -1, -1);
    }

    public MachineSoulTargetFilterScreen(Screen parent, BlockPos soulPos,
                                          TargetFilterData filterData, WhitelistMode whitelistMode,
                                          CommanderFilterData commanderFilterData,
                                          int parentLeftPos, int parentTopPos) {
        super(Component.translatable("gui.cbc_autotarget.filter.title"));
        this.parent              = parent;
        this.soulPos              = soulPos;
        this.filterData           = filterData;
        this.whitelistMode        = whitelistMode;
        this.commanderFilterData  = commanderFilterData;
        this.localMask            = filterData.getMask();
        this.parentLeftPos        = parentLeftPos;
        this.parentTopPos         = parentTopPos;
    }

    public BlockPos getBlockPos() { return soulPos; }

    /** Применяет маску, полученную от сервера (SyncMachineSoulPlayerFilterPacket). */
    public void applyMaskSync(int mask) {
        this.localMask = mask;
        filterData.setMask(mask);
        rebuildCheckboxes();
        updateWhitelistBtn();
    }

    /** Применяет обновлённый список дружественных ID, полученный от сервера. */
    public void applyCommanderFilterSync(List<String> friendlyIds) {
        commanderFilterData.replaceFriendlyIds(friendlyIds);
        if (commanderIdList != null) commanderIdList.refresh();
    }

    /** Пересоздаёт чекбоксы с текущим localMask (Checkbox не даёт программно менять selected). */
    private void rebuildCheckboxes() {
        if (checkboxes.isEmpty()) return; // init() ещё не вызван
        for (int i = 0; i < categories.size(); i++) {
            Checkbox old = checkboxes.get(i);
            boolean should = (localMask & categories.get(i).mask()) != 0;
            if (old.selected() == should) continue;
            removeWidget(old);
            TargetCategory cat = categories.get(i);
            Checkbox cb = Checkbox.builder(Component.translatable(cat.translationKey), font)
                    .pos(px + SIDE_PAD, py + CONTENT_TOP + 4 + i * CHECK_STEP)
                    .selected(should)
                    .build();
            cb.setTooltip(Tooltip.create(Component.translatable(cat.translationKey + ".tooltip")));
            addRenderableWidget(cb);
            checkboxes.set(i, cb);
        }
    }

    private int panelHeight() {
        int cats = CATEGORIES.length;
        int base = CONTENT_TOP + 4 + cats * CHECK_STEP + 4 + 16 + FOOTER_H; // чекбоксы + Manage Whitelist
        int cmdrSection = 6 /*sep*/ + CMDR_SECTION_TITLE_H + CMDR_GAP
                + CMDR_INPUT_ROW_H + CMDR_GAP
                + CMDR_LIST_H + CMDR_GAP;
        return base + cmdrSection;
    }

    // ── Вертикальные смещения секции командеров (считаются от py) ────────────

    private int cmdrSepY()   { return py + CONTENT_TOP + 4 + CATEGORIES.length * CHECK_STEP + 4 + 16 + 4; }
    private int cmdrTitleY() { return cmdrSepY() + 5; }
    private int cmdrInputY() { return cmdrTitleY() + CMDR_SECTION_TITLE_H + CMDR_GAP; }
    private int cmdrListY()  { return cmdrInputY() + CMDR_INPUT_ROW_H + CMDR_GAP; }
    private int doneY()      { return cmdrListY() + CMDR_LIST_H + CMDR_GAP; }

    @Override
    protected void init() {
        super.init();
        checkboxes.clear();
        categories.clear();

        // Если родитель передал свою позицию (leftPos/topPos окна Target) —
        // рисуем ровно там же, чтобы панель не "прыгала" при переходе между
        // вкладками. Иначе (на случай вызова без родительского окна) —
        // центрируем как раньше.
        px = parentLeftPos >= 0 ? parentLeftPos : (width  - GUI_W)       / 2;
        py = parentTopPos  >= 0 ? parentTopPos  : (height - panelHeight()) / 2;

        for (int i = 0; i < CATEGORIES.length; i++) {
            TargetCategory cat = CATEGORIES[i];
            Checkbox cb = Checkbox.builder(Component.translatable(cat.translationKey), font)
                    .pos(px + SIDE_PAD, py + CONTENT_TOP + 4 + i * CHECK_STEP)
                    .selected((localMask & cat.mask()) != 0)
                    .build();
            cb.setTooltip(Tooltip.create(Component.translatable(cat.translationKey + ".tooltip")));
            addRenderableWidget(cb);
            checkboxes.add(cb);
            categories.add(cat);
        }

        int wlBtnY = py + CONTENT_TOP + 4 + CATEGORIES.length * CHECK_STEP + 4;
        whitelistButton = Button.builder(
                        Component.translatable("gui.cbc_autotarget.whitelist.manage"),
                        btn -> openWhitelistScreen())
                .pos(px + SIDE_PAD, wlBtnY)
                .size(GUI_W - SIDE_PAD * 2, 16)
                .build();
        updateWhitelistBtn();
        addRenderableWidget(whitelistButton);

        // ── Секция "Friendly Commanders" ─────────────────────────────────────
        int inputW = GUI_W - SIDE_PAD * 2 - 50 - 3;
        commanderIdInput = new EditBox(font,
                px + SIDE_PAD, cmdrInputY(),
                inputW, 16,
                Component.translatable("gui.cbc_autotarget.soul.commander_filter.input_hint"));
        commanderIdInput.setMaxLength(CommanderFilterData.MAX_KEY_LENGTH);
        commanderIdInput.setHint(Component.translatable("gui.cbc_autotarget.soul.commander_filter.input_hint"));
        addRenderableWidget(commanderIdInput);

        int addBtnX = px + SIDE_PAD + inputW + 3;
        int addBtnW = (GUI_W - SIDE_PAD * 2) - inputW - 3;
        addRenderableWidget(
                Button.builder(Component.translatable("gui.cbc_autotarget.whitelist.add"), b -> onAddCommanderId())
                        .pos(addBtnX, cmdrInputY())
                        .size(addBtnW, 16)
                        .build()
        );

        commanderIdList = new CommanderIdList(minecraft,
                GUI_W - SIDE_PAD * 2,
                CMDR_LIST_H,
                cmdrListY(),
                CMDR_LIST_ITEM_H,
                px + SIDE_PAD);
        commanderIdList.refresh();
        addWidget(commanderIdList);

        int doneW = 60;
        addRenderableWidget(
                Button.builder(Component.translatable("gui.done"), b -> onClose())
                        .pos(px + (GUI_W - doneW) / 2, doneY())
                        .size(doneW, 16)
                        .build()
        );
    }

    // ── Категории (чекбоксы) ─────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn == 0 && isBackHovered((int) mx, (int) my)) {
            onClose();
            return true;
        }
        boolean[] before = new boolean[checkboxes.size()];
        for (int i = 0; i < checkboxes.size(); i++) before[i] = checkboxes.get(i).selected();
        if (commanderIdList != null && commanderIdList.mouseClicked(mx, my, btn)) return true;
        boolean result = super.mouseClicked(mx, my, btn);
        for (int i = 0; i < checkboxes.size(); i++) {
            boolean after = checkboxes.get(i).selected();
            if (before[i] != after) onCheckboxToggled(categories.get(i), after);
        }
        return result;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (commanderIdList != null && my >= cmdrListY() && my <= cmdrListY() + CMDR_LIST_H) {
            return commanderIdList.mouseScrolled(mx, my, dx, dy);
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    private void onCheckboxToggled(TargetCategory cat, boolean enabled) {
        if (enabled) localMask |=  cat.mask();
        else         localMask &= ~cat.mask();
        filterData.setMask(localMask);
        PacketDistributor.sendToServer(UpdateMachineSoulPlayerFilterPacket.setMask(soulPos, localMask));
        updateWhitelistBtn();
    }

    private void updateWhitelistBtn() {
        if (whitelistButton == null) return;
        boolean on = (localMask & TargetCategory.PLAYERS.mask()) != 0;
        whitelistButton.active = on;
        whitelistButton.setTooltip(on ? null
                : Tooltip.create(Component.translatable("gui.cbc_autotarget.whitelist.disabled_hint")));
    }

    private void openWhitelistScreen() {
        Minecraft.getInstance().setScreen(new MachineSoulPlayerFilterScreen(
                this, soulPos, filterData, whitelistMode
        ));
    }

    // ── Фильтр дружественных командеров ──────────────────────────────────────

    private void onAddCommanderId() {
        String id = CommanderFilterData.normalize(commanderIdInput.getValue());
        if (id.isEmpty() || id.length() > CommanderFilterData.MAX_KEY_LENGTH) return;
        if (commanderFilterData.getFriendlyIds().contains(id)) { commanderIdInput.setValue(""); return; }
        if (commanderFilterData.getFriendlyIds().size() >= CommanderFilterData.MAX_FRIENDLY_SIZE) return;
        commanderFilterData.addFriendly(id);
        if (commanderIdList != null) commanderIdList.refresh();
        commanderIdInput.setValue("");
        PacketDistributor.sendToServer(UpdateMachineSoulCommanderFilterPacket.add(soulPos, id));
    }

    private void onRemoveCommanderId(String id) {
        commanderFilterData.removeFriendly(id);
        if (commanderIdList != null) commanderIdList.refresh();
        PacketDistributor.sendToServer(UpdateMachineSoulCommanderFilterPacket.remove(soulPos, id));
    }

    @Override public void onClose() { Minecraft.getInstance().setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (commanderIdInput != null && commanderIdInput.isFocused()) {
            if (key == 256) { onClose(); return true; }
            if (key == 257) { onAddCommanderId(); return true; }
            return commanderIdInput.keyPressed(key, scan, mods);
        }
        if (key == 256) { onClose(); return true; }
        return super.keyPressed(key, scan, mods);
    }

    // ── Рендер ────────────────────────────────────────────────────────────────
    //
    // Ключевое отличие от прежней версии: НЕ вызываем renderBackground(...).
    // Ванильный Screen.renderBackground рисует полупрозрачный/размытый фон
    // поверх игрового мира — именно он и создавал "второй слой" с блюром.
    // Мы полностью его пропускаем: под панелью остаётся то же самое, что
    // было видно на вкладке Target (мир виден только там, где не перекрыт
    // самой панелью — как и в главном окне Machine Soul).

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        // Без renderBackground(g, mx, my, pt) — никакого блюра/затемнения мира.
        drawPanel(g, mx, my);
        if (commanderIdList != null) commanderIdList.render(g, mx, my, pt);
        super.render(g, mx, my, pt);
    }

    private void drawPanel(GuiGraphics g, int mx, int my) {
        int ph = panelHeight();
        int x  = px, y = py;

        // Фон и рамка — единый стиль с BaseMachineSoulScreen.
        g.fill(x, y, x + GUI_W, y + ph, COL_BG);
        BaseMachineSoulScreen.drawBorder(g, x, y, GUI_W, ph, COL_BORDER);

        // Шапка: фон + разделитель — как в главном окне.
        g.fill(x + 1, y + 1, x + GUI_W - 1, y + HEADER_H, COL_HEADER_BG);
        g.fill(x + 1, y + HEADER_H, x + GUI_W - 1, y + HEADER_H + 1, COL_HEADER_SEP);

        // Кнопка "←" — возврат на вкладку Target без сохранения доп. состояния.
        drawBackButton(g, x, y, mx, my);

        // Заголовок по центру шапки.
        String tabTitle = "🎯  " + title.getString();
        int titleX = x + (GUI_W - font.width(tabTitle)) / 2;
        int titleY = y + BTN_Y + (BTN_H - font.lineHeight) / 2;
        g.drawString(font, tabTitle, titleX, titleY, COL_TITLE, false);

        // Контентная область — тот же фон, что и на остальных вкладках.
        int contentBottom = y + ph - 4;
        g.fill(x + 1, y + CONTENT_TOP, x + GUI_W - 1, contentBottom, COL_CONTENT_BG);

        // Разделитель + заголовок секции командеров
        int sepY = cmdrSepY();
        g.fill(x + 4, sepY, x + GUI_W - 4, sepY + 1, COL_HEADER_SEP);
        String sectionTitle = Component.translatable("gui.cbc_autotarget.soul.commander_filter.section").getString();
        g.drawString(font, sectionTitle, x + SIDE_PAD, cmdrTitleY(), SECTION_TITLE_CLR, false);

        // Счётчик справа от заголовка секции
        String cnt = commanderFilterData.getFriendlyIds().size() + "/" + CommanderFilterData.MAX_FRIENDLY_SIZE;
        g.drawString(font, cnt, x + GUI_W - SIDE_PAD - font.width(cnt), cmdrTitleY(), COL_COUNTER, false);

        // Фон списка
        g.fill(x + SIDE_PAD, cmdrListY(), x + GUI_W - SIDE_PAD, cmdrListY() + CMDR_LIST_H, COL_LIST_BG);
        if (commanderFilterData.getFriendlyIds().isEmpty()) {
            String empty = Component.translatable("gui.cbc_autotarget.soul.commander_filter.empty").getString();
            g.drawCenteredString(font, empty, x + GUI_W / 2, cmdrListY() + CMDR_LIST_H / 2 - 4, COL_TEXT_DIM);
        }
    }

    private void drawBackButton(GuiGraphics g, int lx, int ty, int mx, int my) {
        boolean hov = isBackHovered(mx, my);
        int bx = lx + BACK_X;
        int by = ty + BTN_Y;

        g.fill(bx, by, bx + BACK_W, by + BTN_H, hov ? COL_BACK_HOVER_BG : COL_BACK_BG);
        BaseMachineSoulScreen.drawBorder(g, bx, by, BACK_W, BTN_H, hov ? COL_ACCENT : COL_BACK_BORDER);

        String arrow = "←";
        int aw = font.width(arrow);
        g.drawString(font, arrow,
                bx + (BACK_W - aw) / 2,
                by + (BTN_H - font.lineHeight) / 2,
                hov ? COL_ACCENT2 : COL_ACCENT, false);
    }

    private boolean isBackHovered(int mx, int my) {
        return mx >= px + BACK_X && mx < px + BACK_X + BACK_W
                && my >= py + BTN_Y && my < py + BTN_Y + BTN_H;
    }

    // ── Компактный список дружественных ID ───────────────────────────────────

    private class CommanderIdList extends ObjectSelectionList<CommanderIdList.Entry> {

        CommanderIdList(Minecraft mc, int w, int h, int y, int ih, int x) {
            super(mc, w, h, y, ih);
            setX(x);
        }

        void refresh() {
            clearEntries();
            new ArrayList<>(commanderFilterData.getFriendlyIds()).forEach(n -> addEntry(new Entry(n)));
        }

        @Override public int getRowWidth() { return GUI_W - SIDE_PAD * 2; }
        @Override protected int getScrollbarPosition() { return px + GUI_W - SIDE_PAD - 5; }

        @Override
        public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            if (getItemCount() == 0) return; // пустая подсказка рисуется в drawPanel

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
            private final String id;
            private final Button removeBtn;

            Entry(String id) {
                this.id = id;
                this.removeBtn = Button.builder(
                        Component.translatable("gui.cbc_autotarget.whitelist.remove"),
                        b -> onRemoveCommanderId(id))
                        .size(40, CMDR_LIST_ITEM_H - 4).build();
            }

            @Override
            public void render(GuiGraphics g, int idx, int top, int left,
                               int w, int h, int mx, int my, boolean hov, float pt) {
                g.fill(left, top, left + w, top + h,
                        idx % 2 == 0 ? COL_ROW_EVEN : COL_ROW_ODD);
                if (hov) g.fill(left, top, left + w, top + h, COL_ROW_HOV);

                int textX     = left + 4;
                int badgeW    = font.width("#");
                int availableW = (left + w - 44) - (textX + badgeW + 2); // до кнопки Remove

                // Alliance Key может быть длиной до 64 символов — обрезаем
                // с многоточием, чтобы не наезжать на кнопку удаления;
                // полное значение видно во всплывающей подсказке при наведении.
                String display = id;
                if (font.width(display) > availableW) {
                    display = font.plainSubstrByWidth(display, Math.max(0, availableW - font.width("…"))) + "…";
                }

                g.drawString(font, "#", textX, top + (h - font.lineHeight) / 2, COL_ROW_BADGE, false);
                g.drawString(font, display, textX + badgeW + 2, top + (h - font.lineHeight) / 2, COL_ROW_TEXT, false);

                if (hov && !display.equals(id) && mx >= left && mx < left + w && my >= top && my < top + h) {
                    g.renderTooltip(font, Component.literal(id), mx, my);
                }

                removeBtn.setX(left + w - 42);
                removeBtn.setY(top + 2);
                removeBtn.render(g, mx, my, pt);
            }

            @Override public boolean mouseClicked(double mx, double my, int b) {
                return removeBtn.mouseClicked(mx, my, b);
            }
            @Override public Component getNarration() { return Component.literal(id); }
        }
    }
}
