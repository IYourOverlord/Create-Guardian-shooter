package com.yourname.cbcautotarget.client;

import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.Tab;
import com.yourname.cbcautotarget.filter.CommanderFilterData;
import com.yourname.cbcautotarget.filter.TargetCategory;
import com.yourname.cbcautotarget.menu.MachineSoulTargetMenu;
import com.yourname.cbcautotarget.network.ToggleMachineSoulTargetPlayersPacket;
import com.yourname.cbcautotarget.network.UpdateMachineSoulCommanderFilterPacket;
import com.yourname.cbcautotarget.network.UpdateMachineSoulPlayerFilterPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Экран вкладки Target.
 *
 * Раньше кнопка "Filter..." открывала ОТДЕЛЬНЫЙ {@code Screen}
 * (MachineSoulTargetFilterScreen) поверх этого окна. Из-за того, как
 * устроен рендер-пайплайн Minecraft 1.21 (каждый Screen — это свой
 * стратум, и движок применяет блюр фона за экраном ДО первого стратума,
 * независимо от того, что делает {@code renderBackground} самого экрана),
 * любой второй {@code Screen} поверх первого визуально "проваливался" в
 * размытый фон, а виджеты (Checkbox/Button/EditBox), будучи ванильными
 * компонентами, рендерились уже поверх этого блюра — получались два
 * разных слоя, не совпадающих со стилем остальных вкладок.
 *
 * Исправление: фильтр целей больше не отдельный Screen. Это ВНУТРЕННИЙ
 * РЕЖИМ того же {@code MachineSoulTargetScreen} — при нажатии "Filter..."
 * этот же экран просто переключает набор своих виджетов (чекбоксы,
 * Manage Whitelist, поле Alliance Key, список) и меняет то, что рисует в
 * {@code renderContent}. Так как это один и тот же {@code Screen} с одним
 * рендер-стратумом, блюра между элементами быть не может — все элементы
 * гарантированно находятся на одном слое, как и на вкладке Vision.
 *
 * Геометрия основного режима (сверху вниз внутри контентной области, от
 * y=SAVE_BOTTOM+2=18):
 *   Hint text            : y=18,  до 4 строк, запас HINT_RESERVED_H=42px
 *   Переключатель        : y=60,  h=16
 *   Кнопка Filter...     : y=80,  h=16   (переключает в режим фильтра)
 *   INV_Y_BASE=100
 */
public class MachineSoulTargetScreen extends BaseMachineSoulScreen<MachineSoulTargetMenu> {

    private static final Logger LOGGER = LoggerFactory.getLogger("cbc_autotarget/TargetScreen");

    // ── Хинт ──────────────────────────────────────────────────────────────────
    private static final int HINT_Y        = SAVE_BOTTOM + 2;  // 18
    private static final int HINT_RESERVED_H = 42;

    // ── Переключатель «Таргет на игроков» ────────────────────────────────────
    private static final int TOGGLE_Y = HINT_Y + HINT_RESERVED_H;  // 60
    private static final int TOGGLE_H = 16;

    // ── Кнопка «Filter...» ────────────────────────────────────────────────────
    private static final int FILTER_BTN_Y = TOGGLE_Y + TOGGLE_H + 4;  // 80
    private static final int FILTER_BTN_H = 16;

    // Высота окна обычного вида вкладки Target. Инвентарь игрока здесь не
    // нужен (см. isInventoryHidden()), поэтому высота считается только по
    // реальному контенту — хинт + переключатель + кнопка Filter — без
    // запаса под слоты инвентаря/подпись "Inventory".
    private static final int GUI_H_TARGET = FILTER_BTN_Y + FILTER_BTN_H + 8;

    // ── Цвета переключателя ───────────────────────────────────────────────────
    private static final int COL_TOGGLE_ON_BG            = 0xFF153D15;
    private static final int COL_TOGGLE_ON_BORDER        = 0xFF44FF66;
    private static final int COL_TOGGLE_ON_BORDER_HOVER  = 0xFF66FF88;
    private static final int COL_TOGGLE_ON_TEXT          = 0xFFAAFFBB;
    private static final int COL_TOGGLE_OFF_BG           = 0xFF2A1212;
    private static final int COL_TOGGLE_OFF_BORDER       = 0xFFBB4444;
    private static final int COL_TOGGLE_OFF_BORDER_HOVER = 0xFFFF6666;
    private static final int COL_TOGGLE_OFF_TEXT         = 0xFFFFAAAA;
    private static final int COL_TOGGLE_DOT_ON           = 0xFF44FF66;
    private static final int COL_TOGGLE_DOT_OFF          = 0xFFFF5555;

    // ── Цвета кнопки «Filter...» ──────────────────────────────────────────────
    private static final int COL_FILTER_BG        = 0xFF0A2A18;
    private static final int COL_FILTER_BG_HOV    = 0xFF133D22;
    private static final int COL_FILTER_BORDER     = 0xFF2A8A55;
    private static final int COL_FILTER_BORDER_HOV = 0xFF44CC77;
    private static final int COL_FILTER_TEXT       = 0xFF88FFBB;

    // ── Режим "Filter" (встроенная панель, бывший отдельный Screen) ─────────
    //
    // ENEMY_COMMANDERS ("Command Blocks") добавлена в список отображаемых
    // категорий: раньше в UI не было способа отключить нацеливание на
    // командные блоки (командеров), хотя маска и логика фильтрации по ней
    // уже существовали (используются ControllerBlockEntity) — Machine Soul
    // просто игнорировал этот флаг и всегда атаковал недружественных
    // командеров. Список "Friendly Commanders" ниже по-прежнему определяет,
    // КАКИЕ именно командеры считаются дружественными; этот чекбокс
    // определяет, атакует ли Machine Soul командеров вообще.
    private static final TargetCategory[] CATEGORIES = {
            TargetCategory.HOSTILE,
            TargetCategory.PASSIVE,
            TargetCategory.PLAYERS,
            TargetCategory.ENEMY_COMMANDERS
    };
    private static final int CHECK_STEP = 22;
    private static final int SECTION_TITLE_CLR = 0xFF88DDFF;
    private static final int CMDR_SECTION_TITLE_H = 12;
    private static final int CMDR_INPUT_ROW_H     = 18;
    private static final int CMDR_LIST_H          = 54;
    private static final int CMDR_LIST_ITEM_H     = 18;
    private static final int CMDR_GAP             = 4;
    private static final int COL_LIST_BG    = 0xFF071A12;
    private static final int COL_ROW_EVEN   = 0xFF0A2418;
    private static final int COL_ROW_ODD    = 0xFF081E14;
    private static final int COL_ROW_HOV    = 0xFF0F3020;
    private static final int COL_ROW_BADGE  = COL_ACCENT2;
    private static final int COL_COUNTER    = 0xFF2E7A4A;

    // Дополнительная высота GUI, когда открыт режим фильтра.
    // Инвентарь игрока в этом режиме скрыт (см. isInventoryHidden()), поэтому
    // высота окна считается только по контенту фильтра — без запаса под
    // слоты инвентаря/подпись "Inventory", которые здесь не нужны.
    private static final int FILTER_CONTENT_H =
            4 + CATEGORIES.length * CHECK_STEP + 4 + 16   // чекбоксы + Manage Whitelist
            + 6 + CMDR_SECTION_TITLE_H + CMDR_GAP          // sep + section title
            + CMDR_INPUT_ROW_H + CMDR_GAP
            + CMDR_LIST_H + CMDR_GAP
            + 8;                                           // нижний отступ панели
    private static final int GUI_H_FILTER =
            CONTENT_TOP + FILTER_CONTENT_H + 4;

    // Локальное (оптимистичное) состояние переключателя
    private boolean targetPlayers;

    // Состояние режима фильтра
    private boolean filterMode = false;
    private int localMask;
    private final List<Checkbox>       checkboxes = new ArrayList<>();
    private final List<TargetCategory> categories = new ArrayList<>();
    private Button whitelistButton;
    private EditBox commanderIdInput;
    private CommanderIdList commanderIdList;

    public MachineSoulTargetScreen(MachineSoulTargetMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, Tab.TARGET, menu.blockPos, GUI_H_TARGET);
        this.targetPlayers = menu.isTargetPlayers();
        this.localMask     = menu.getPlayerFilterData().getMask();
        LOGGER.info("[TargetScreen] CONSTRUCTED pos={} initialLocalMask={} targetPlayers={} (read from menu.getPlayerFilterData())",
                menu.blockPos, this.localMask, this.targetPlayers);
    }

    @Override protected int getInvYBase() {
        return filterMode ? (CONTENT_TOP + FILTER_CONTENT_H) : MachineSoulTargetMenu.INV_Y_BASE;
    }
    @Override protected int getInvX() { return MachineSoulTargetMenu.INV_X; }

    /**
     * Инвентарь игрока не нужен ни в обычном виде вкладки Target, ни в
     * режиме фильтра — эта вкладка целиком посвящена настройке целей, а не
     * работе с предметами. Слоты инвентаря принадлежат серверному меню и не
     * могут быть удалены без рассинхронизации индексов между клиентом и
     * сервером, поэтому их не рисуют ({@link #renderSlot}) и не реагируют
     * на клики ({@link #slotClicked}) — визуально и функционально их как
     * будто нет, независимо от {@link #filterMode}.
     */
    @Override protected boolean isInventoryHidden() { return true; }

    @Override
    protected void renderSlot(GuiGraphics g, Slot slot) {
        // Слоты инвентаря никогда не нужны на этой вкладке (см. isInventoryHidden()).
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton,
                               ClickType type) {
        // Слоты инвентаря отключены на всей вкладке Target (см. isInventoryHidden()).
    }

    /** Применяет маску, полученную от сервера (SyncMachineSoulPlayerFilterPacket). */
    public void applyMaskSync(int mask) {
        int oldMask = this.localMask;
        this.localMask = mask;
        menu.getPlayerFilterData().setMask(mask);
        LOGGER.info("[TargetScreen] applyMaskSync pos={} oldLocalMask={} newLocalMask={} filterMode={} -> {}",
                blockPos, oldMask, mask, filterMode, filterMode ? "rebuilding widgets" : "widgets NOT rebuilt (filterMode=false)");
        if (filterMode) rebuildWidgetsForMode();
    }

    /** Применяет обновлённый список дружественных ID, полученный от сервера. */
    public void applyCommanderFilterSync(List<String> friendlyIds) {
        menu.getCommanderFilterData().replaceFriendlyIds(friendlyIds);
        if (commanderIdList != null) commanderIdList.refresh();
    }

    // ── Переключение между обычным видом и видом фильтра ─────────────────────

    private void enterFilterMode() {
        filterMode = true;
        LOGGER.info("[TargetScreen] enterFilterMode pos={} localMask={} (menu.mask={})",
                blockPos, localMask, menu.getPlayerFilterData().getMask());
        rebuildWidgetsForMode();
    }

    private void exitFilterMode() {
        filterMode = false;
        LOGGER.info("[TargetScreen] exitFilterMode pos={} localMask={} (menu.mask={})",
                blockPos, localMask, menu.getPlayerFilterData().getMask());
        rebuildWidgetsForMode();
    }

    /** Пересобирает набор виджетов текущего окна под активный режим. */
    private void rebuildWidgetsForMode() {
        this.imageHeight = filterMode ? GUI_H_FILTER : GUI_H_TARGET;
        this.init(minecraft, width, height);
    }

    @Override
    protected void init() {
        super.init();
        checkboxes.clear();
        categories.clear();

        if (!filterMode) return;

        LOGGER.info("[TargetScreen] init() building filterMode checkboxes pos={} localMask={} (menu.mask={})",
                blockPos, localMask, menu.getPlayerFilterData().getMask());

        int lx = leftPos, ty = topPos;
        int y0 = ty + CONTENT_TOP + 4;

        for (int i = 0; i < CATEGORIES.length; i++) {
            TargetCategory cat = CATEGORIES[i];
            boolean selected = (localMask & cat.mask()) != 0;
            Checkbox cb = Checkbox.builder(Component.translatable(cat.translationKey), font)
                    .pos(lx + PAD, y0 + i * CHECK_STEP)
                    .selected(selected)
                    .build();
            cb.setTooltip(Tooltip.create(Component.translatable(cat.translationKey + ".tooltip")));
            addRenderableWidget(cb);
            checkboxes.add(cb);
            categories.add(cat);
            LOGGER.info("[TargetScreen] init() checkbox[{}]={} selected={}", i, cat, selected);
        }

        int wlBtnY = y0 + CATEGORIES.length * CHECK_STEP + 4;
        whitelistButton = Button.builder(
                        Component.translatable("gui.cbc_autotarget.whitelist.manage"),
                        btn -> openWhitelistScreen())
                .pos(lx + PAD, wlBtnY)
                .size(GUI_W - PAD * 2, 16)
                .build();
        updateWhitelistBtn();
        addRenderableWidget(whitelistButton);

        int inputW = GUI_W - PAD * 2 - 50 - 3;
        commanderIdInput = new EditBox(font,
                lx + PAD, cmdrInputY(),
                inputW, 16,
                Component.translatable("gui.cbc_autotarget.soul.commander_filter.input_hint"));
        commanderIdInput.setMaxLength(CommanderFilterData.MAX_KEY_LENGTH);
        commanderIdInput.setHint(Component.translatable("gui.cbc_autotarget.soul.commander_filter.input_hint"));
        addRenderableWidget(commanderIdInput);

        int addBtnX = lx + PAD + inputW + 3;
        int addBtnW = (GUI_W - PAD * 2) - inputW - 3;
        addRenderableWidget(
                Button.builder(Component.translatable("gui.cbc_autotarget.whitelist.add"), b -> onAddCommanderId())
                        .pos(addBtnX, cmdrInputY())
                        .size(addBtnW, 16)
                        .build()
        );

        commanderIdList = new CommanderIdList(minecraft,
                GUI_W - PAD * 2,
                CMDR_LIST_H,
                cmdrListY(),
                CMDR_LIST_ITEM_H,
                lx + PAD);
        commanderIdList.refresh();
        addWidget(commanderIdList);
    }

    // Вертикальные смещения секции командеров (в абсолютных экранных координатах)
    private int cmdrSepY()   { return topPos + CONTENT_TOP + 4 + CATEGORIES.length * CHECK_STEP + 4 + 16 + 4; }
    private int cmdrTitleY() { return cmdrSepY() + 5; }
    private int cmdrInputY() { return cmdrTitleY() + CMDR_SECTION_TITLE_H + CMDR_GAP; }
    private int cmdrListY()  { return cmdrInputY() + CMDR_INPUT_ROW_H + CMDR_GAP; }

    @Override
    protected void renderContent(GuiGraphics g, int lx, int ty, int mx, int my) {
        if (filterMode) {
            renderFilterContent(g, lx, ty, mx, my);
            return;
        }

        // Hint
        drawWrappedText(g, lx + PAD, ty + HINT_Y, GUI_W - PAD * 2,
                Component.translatable("gui.cbc_autotarget.soul.tab.target.hint").getString(),
                COL_TEXT_DIM);

        // Переключатель
        drawTargetToggle(g, lx, ty, mx, my);

        // Кнопка «Filter...»
        drawFilterButton(g, lx, ty, mx, my);
    }

    /** Название вкладки в шапке — переопределяем на "Target Filter" в режиме фильтра. */
    @Override
    protected String getTabTitle() {
        return filterMode
                ? "🎯  " + Component.translatable("gui.cbc_autotarget.filter.title").getString()
                : super.getTabTitle();
    }

    private void renderFilterContent(GuiGraphics g, int lx, int ty, int mx, int my) {
        // Разделитель + заголовок секции командеров
        int sepY = cmdrSepY();
        g.fill(lx + 4, sepY, lx + GUI_W - 4, sepY + 1, COL_HEADER_SEP);
        String sectionTitle = Component.translatable("gui.cbc_autotarget.soul.commander_filter.section").getString();
        g.drawString(font, sectionTitle, lx + PAD, cmdrTitleY(), SECTION_TITLE_CLR, false);

        String cnt = menu.getCommanderFilterData().getFriendlyIds().size() + "/" + CommanderFilterData.MAX_FRIENDLY_SIZE;
        g.drawString(font, cnt, lx + GUI_W - PAD - font.width(cnt), cmdrTitleY(), COL_COUNTER, false);

        g.fill(lx + PAD, cmdrListY(), lx + GUI_W - PAD, cmdrListY() + CMDR_LIST_H, COL_LIST_BG);
        if (menu.getCommanderFilterData().getFriendlyIds().isEmpty()) {
            String empty = Component.translatable("gui.cbc_autotarget.soul.commander_filter.empty").getString();
            g.drawCenteredString(font, empty, lx + GUI_W / 2, cmdrListY() + CMDR_LIST_H / 2 - 4, COL_TEXT_DIM);
        }

        if (commanderIdList != null) commanderIdList.render(g, mx, my, 0);
    }

    // ── Переключатель ──────────────────────────────────────────────────────────

    private void drawTargetToggle(GuiGraphics g, int lx, int ty, int mx, int my) {
        int x = lx + PAD;
        int y = ty + TOGGLE_Y;
        int w = GUI_W - PAD * 2;
        int h = TOGGLE_H;

        boolean hov = isToggleHovered(mx, my);

        int bg     = targetPlayers ? COL_TOGGLE_ON_BG  : COL_TOGGLE_OFF_BG;
        int border = targetPlayers
                ? (hov ? COL_TOGGLE_ON_BORDER_HOVER  : COL_TOGGLE_ON_BORDER)
                : (hov ? COL_TOGGLE_OFF_BORDER_HOVER : COL_TOGGLE_OFF_BORDER);
        int textCol = targetPlayers ? COL_TOGGLE_ON_TEXT : COL_TOGGLE_OFF_TEXT;

        g.fill(x, y, x + w, y + h, bg);
        drawBorder(g, x, y, w, h, border);

        int dotR  = 3;
        int dotCx = x + 10;
        int dotCy = y + h / 2;
        g.fill(dotCx - dotR, dotCy - dotR, dotCx + dotR, dotCy + dotR,
                targetPlayers ? COL_TOGGLE_DOT_ON : COL_TOGGLE_DOT_OFF);

        String label = Component.translatable("gui.cbc_autotarget.soul.tab.target.toggle").getString()
                + ": "
                + (targetPlayers
                ? Component.translatable("gui.cbc_autotarget.soul.tab.target.toggle.on").getString()
                : Component.translatable("gui.cbc_autotarget.soul.tab.target.toggle.off").getString());
        g.drawString(font, label, x + 10 + dotR + 6, y + (h - font.lineHeight) / 2, textCol, false);
    }

    private boolean isToggleHovered(int mx, int my) {
        int x = leftPos + PAD;
        int y = topPos + TOGGLE_Y;
        int w = GUI_W - PAD * 2;
        int h = TOGGLE_H;
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ── Кнопка фильтра (категории + вайтлист игроков + командеры) ───────────────

    private void drawFilterButton(GuiGraphics g, int lx, int ty, int mx, int my) {
        int x = lx + PAD;
        int y = ty + FILTER_BTN_Y;
        int w = GUI_W - PAD * 2;
        int h = FILTER_BTN_H;

        boolean hov = isFilterBtnHovered(mx, my);

        int bg     = hov ? COL_FILTER_BG_HOV : COL_FILTER_BG;
        int border = hov ? COL_FILTER_BORDER_HOV : COL_FILTER_BORDER;

        g.fill(x, y, x + w, y + h, bg);
        drawBorder(g, x, y, w, h, border);

        // Иконка ▶ слева
        int iconX = x + 8;
        int iconY = y + (h - font.lineHeight) / 2;
        g.drawString(font, "▶", iconX, iconY, COL_FILTER_TEXT, false);

        // Текст
        String label = Component.translatable("gui.cbc_autotarget.soul.tab.target.player_filter").getString();
        int textX = iconX + font.width("▶") + 4;
        g.drawString(font, label, textX, iconY, COL_FILTER_TEXT, false);

        // Счётчик вайтлиста справа
        var filter = menu.getPlayerFilterData();
        if (filter.isWhitelistEnabled()) {
            String counter = filter.getWhitelist().size() + " names";
            g.drawString(font, counter, x + w - font.width(counter) - 6, iconY, COL_TEXT_DIM, false);
        } else {
            String status = Component.translatable("gui.cbc_autotarget.soul.tab.target.player_filter.all").getString();
            g.drawString(font, status, x + w - font.width(status) - 6, iconY, COL_TEXT_DIM, false);
        }
    }

    private boolean isFilterBtnHovered(int mx, int my) {
        int x = leftPos + PAD;
        int y = topPos + FILTER_BTN_Y;
        int w = GUI_W - PAD * 2;
        int h = FILTER_BTN_H;
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ── Клики ─────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && filterMode && isBackHovered((int) mx, (int) my)) {
            exitFilterMode();
            return true;
        }
        if (button == 0 && !filterMode) {
            if (isToggleHovered((int) mx, (int) my)) {
                targetPlayers = !targetPlayers;
                menu.setTargetPlayers(targetPlayers);
                PacketDistributor.sendToServer(new ToggleMachineSoulTargetPlayersPacket(blockPos));
                return true;
            }
            if (isFilterBtnHovered((int) mx, (int) my)) {
                enterFilterMode();
                return true;
            }
        }

        if (filterMode) {
            boolean[] before = new boolean[checkboxes.size()];
            for (int i = 0; i < checkboxes.size(); i++) before[i] = checkboxes.get(i).selected();
            if (commanderIdList != null && commanderIdList.mouseClicked(mx, my, button)) return true;
            boolean result = super.mouseClicked(mx, my, button);
            for (int i = 0; i < checkboxes.size(); i++) {
                boolean after = checkboxes.get(i).selected();
                if (before[i] != after) onCheckboxToggled(categories.get(i), after);
            }
            return result;
        }

        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (filterMode && commanderIdList != null && my >= cmdrListY() && my <= cmdrListY() + CMDR_LIST_H) {
            return commanderIdList.mouseScrolled(mx, my, dx, dy);
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    private void onCheckboxToggled(TargetCategory cat, boolean enabled) {
        int maskBefore = localMask;
        if (enabled) localMask |=  cat.mask();
        else         localMask &= ~cat.mask();
        menu.getPlayerFilterData().setMask(localMask);
        LOGGER.info("[TargetScreen] CHECKBOX TOGGLED pos={} category={} enabled={} maskBefore={} maskAfter={} -> sending UpdateMachineSoulPlayerFilterPacket.setMask",
                blockPos, cat, enabled, maskBefore, localMask);
        PacketDistributor.sendToServer(UpdateMachineSoulPlayerFilterPacket.setMask(blockPos, localMask));
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
                this, blockPos, menu.getPlayerFilterData(), menu.getWhitelistMode()
        ));
    }

    private void onAddCommanderId() {
        var commanderFilterData = menu.getCommanderFilterData();
        String id = CommanderFilterData.normalize(commanderIdInput.getValue());
        if (id.isEmpty() || id.length() > CommanderFilterData.MAX_KEY_LENGTH) return;
        if (commanderFilterData.getFriendlyIds().contains(id)) { commanderIdInput.setValue(""); return; }
        if (commanderFilterData.getFriendlyIds().size() >= CommanderFilterData.MAX_FRIENDLY_SIZE) return;
        commanderFilterData.addFriendly(id);
        if (commanderIdList != null) commanderIdList.refresh();
        commanderIdInput.setValue("");
        PacketDistributor.sendToServer(UpdateMachineSoulCommanderFilterPacket.add(blockPos, id));
    }

    private void onRemoveCommanderId(String id) {
        var commanderFilterData = menu.getCommanderFilterData();
        commanderFilterData.removeFriendly(id);
        if (commanderIdList != null) commanderIdList.refresh();
        PacketDistributor.sendToServer(UpdateMachineSoulCommanderFilterPacket.remove(blockPos, id));
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (filterMode && commanderIdInput != null && commanderIdInput.isFocused()) {
            if (key == 256) { exitFilterMode(); return true; }
            if (key == 257) { onAddCommanderId(); return true; }
            return commanderIdInput.keyPressed(key, scan, mods);
        }
        if (filterMode && key == 256) { exitFilterMode(); return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    protected boolean onSaveClicked() {
        onClose();
        return true;
    }

    // ── Компактный список дружественных ID ───────────────────────────────────

    private class CommanderIdList extends ObjectSelectionList<CommanderIdList.Entry> {

        CommanderIdList(Minecraft mc, int w, int h, int y, int ih, int x) {
            super(mc, w, h, y, ih);
            setX(x);
        }

        void refresh() {
            clearEntries();
            new ArrayList<>(menu.getCommanderFilterData().getFriendlyIds()).forEach(n -> addEntry(new Entry(n)));
        }

        @Override public int getRowWidth() { return GUI_W - PAD * 2; }
        @Override protected int getScrollbarPosition() { return leftPos + GUI_W - PAD - 5; }

        @Override
        public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            if (getItemCount() == 0) return; // пустая подсказка рисуется в renderFilterContent

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

                String display = id;
                if (font.width(display) > availableW) {
                    display = font.plainSubstrByWidth(display, Math.max(0, availableW - font.width("…"))) + "…";
                }

                g.drawString(font, "#", textX, top + (h - font.lineHeight) / 2, COL_ROW_BADGE, false);
                g.drawString(font, display, textX + badgeW + 2, top + (h - font.lineHeight) / 2, COL_TEXT, false);

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
