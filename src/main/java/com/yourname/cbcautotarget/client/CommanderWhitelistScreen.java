package com.yourname.cbcautotarget.client;

import com.yourname.cbcautotarget.filter.TargetFilterData;
import com.yourname.cbcautotarget.network.UpdateCommanderWhitelistPacket;
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

public class CommanderWhitelistScreen extends Screen {

    private static final int PANEL_W  = 220;
    private static final int PANEL_H  = 220;
    private static final int ITEM_H   = 20;
    private static final int BG       = 0xD0101010;
    private static final int BORDER   = 0xFF555555;
    private static final int TITLE_C  = 0xFFFFFF;
    private static final int LIST_BG  = 0xFF1A1A22;
    private static final int HINT_C   = 0xFF888888;

    private final Screen           parent;
    private final BlockPos         pos;
    private final TargetFilterData filterData;

    private Checkbox wlCheckbox;
    private NameList nameList;
    private EditBox  input;

    public CommanderWhitelistScreen(Screen parent, BlockPos pos, TargetFilterData filterData) {
        super(Component.translatable("gui.cbc_autotarget.whitelist.title"));
        this.parent     = parent;
        this.pos        = pos;
        this.filterData = filterData;
    }

    @Override
    protected void init() {
        int px = (width  - PANEL_W) / 2;
        int py = (height - PANEL_H) / 2;

        wlCheckbox = Checkbox.builder(
                        Component.translatable("gui.cbc_autotarget.whitelist.enabled_label"), font)
                .pos(px + 6, py + 20)
                .selected(filterData.isWhitelistEnabled())
                .onValueChange((cb, val) -> onToggle(val))
                .build();
        addRenderableWidget(wlCheckbox);

        int listTop = py + 42;
        int listBot = py + PANEL_H - 50;
        nameList = new NameList(minecraft, PANEL_W - 16, listBot - listTop, listTop, ITEM_H, px + 8);
        nameList.refresh();
        addWidget(nameList);

        int inputY = py + PANEL_H - 44;
        input = new EditBox(font, px + 8, inputY, PANEL_W - 70, 16,
                Component.translatable("gui.cbc_autotarget.whitelist.input_hint"));
        input.setMaxLength(16);
        input.setHint(Component.translatable("gui.cbc_autotarget.whitelist.input_hint"));
        addRenderableWidget(input);

        addRenderableWidget(
                Button.builder(Component.translatable("gui.cbc_autotarget.whitelist.add"), b -> onAdd())
                        .pos(px + PANEL_W - 58, inputY).size(50, 16).build()
        );

        addRenderableWidget(
                Button.builder(Component.translatable("gui.done"), b -> onClose())
                        .pos(px + (PANEL_W - 60) / 2, py + PANEL_H - 22).size(60, 14).build()
        );
    }

    @Override
    public void tick() {
        super.tick();
        if (nameList != null) nameList.refresh();
        // Если сервер прислал SyncCommanderDataPacket уже после открытия экрана —
        // пересоздаём чекбокс с актуальным состоянием
        if (wlCheckbox != null && wlCheckbox.selected() != filterData.isWhitelistEnabled()) {
            int px = (width  - PANEL_W) / 2;
            int py = (height - PANEL_H) / 2;
            removeWidget(wlCheckbox);
            wlCheckbox = Checkbox.builder(
                            Component.translatable("gui.cbc_autotarget.whitelist.enabled_label"), font)
                    .pos(px + 6, py + 20)
                    .selected(filterData.isWhitelistEnabled())
                    .onValueChange((cb, val) -> onToggle(val))
                    .build();
            addRenderableWidget(wlCheckbox);
        }
    }

    private void onToggle(boolean enabled) {
        filterData.setWhitelistEnabled(enabled);
        PacketDistributor.sendToServer(UpdateCommanderWhitelistPacket.setEnabled(pos, enabled));
    }

    private void onAdd() {
        String name = input.getValue().trim();
        if (name.isEmpty() || name.length() > 16) { input.setValue(""); return; }
        if (filterData.getWhitelist().contains(name)) { input.setValue(""); return; }
        if (filterData.getWhitelist().size() >= 50) return;
        filterData.addToWhitelist(name);
        if (nameList != null) nameList.refresh();
        input.setValue("");
        PacketDistributor.sendToServer(UpdateCommanderWhitelistPacket.add(pos, name));
    }

    private void onRemove(String name) {
        filterData.removeFromWhitelist(name);
        if (nameList != null) nameList.refresh();
        PacketDistributor.sendToServer(UpdateCommanderWhitelistPacket.remove(pos, name));
    }

    // ИСПРАВЛЕНИЕ: EditBox в фокусе — перехватываем ВСЕ клавиши,
    // E не попадает в super и не закрывает UI
    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (input != null && input.isFocused()) {
            if (key == 256) { onClose(); return true; }
            if (key == 257) { onAdd(); return true; }
            return input.keyPressed(key, scan, mods);
        }
        if (key == 256) { onClose(); return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override public void onClose() { Minecraft.getInstance().setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g, mx, my, pt);
        drawPanel(g);
        super.render(g, mx, my, pt);
        if (nameList != null) nameList.render(g, mx, my, pt);
    }

    private void drawPanel(GuiGraphics g) {
        int x = (width  - PANEL_W) / 2;
        int y = (height - PANEL_H) / 2;
        g.fill(x, y, x + PANEL_W, y + PANEL_H, BG);
        g.fill(x,             y,             x + PANEL_W, y + 1,           BORDER);
        g.fill(x,             y + PANEL_H-1, x + PANEL_W, y + PANEL_H,     BORDER);
        g.fill(x,             y,             x + 1,        y + PANEL_H,     BORDER);
        g.fill(x + PANEL_W-1, y,             x + PANEL_W,  y + PANEL_H,    BORDER);
        g.fill(x + 4, y + 17, x + PANEL_W - 4, y + 18, BORDER);
        g.fill(x + 4, y + 40, x + PANEL_W - 4, y + 41, BORDER);
        g.fill(x + 4, y + PANEL_H - 50, x + PANEL_W - 4, y + PANEL_H - 49, BORDER);
        g.drawCenteredString(font, title, x + PANEL_W / 2, y + 6, TITLE_C);
        String counter = filterData.getWhitelist().size() + " / 50";
        g.drawString(font, counter, x + PANEL_W - 8 - font.width(counter),
                y + PANEL_H - 49, HINT_C, false);
        g.fill(x + 8, y + 42, x + PANEL_W - 8, y + PANEL_H - 50, LIST_BG);
    }

    private class NameList extends ObjectSelectionList<NameList.Entry> {
        NameList(Minecraft mc, int w, int h, int y, int ih, int x) {
            super(mc, w, h, y, ih);
            setX(x);
        }
        void refresh() {
            clearEntries();
            new ArrayList<>(filterData.getWhitelist()).forEach(n -> addEntry(new Entry(n)));
        }
        @Override public int getRowWidth()             { return PANEL_W - 16; }
        @Override protected int getScrollbarPosition() { return getX() + PANEL_W - 22; }

        class Entry extends ObjectSelectionList.Entry<Entry> {
            private final String name;
            private final Button btn;
            Entry(String name) {
                this.name = name;
                this.btn  = Button.builder(
                                Component.translatable("gui.cbc_autotarget.whitelist.remove"),
                                b -> onRemove(name))
                        .size(50, 12).build();
            }
            @Override
            public void render(GuiGraphics g, int idx, int top, int left,
                               int w, int h, int mx, int my, boolean hov, float pt) {
                g.drawString(font, name, left + 4, top + (h - 8) / 2, 0xFFFFFF, false);
                btn.setX(left + w - 54);
                btn.setY(top + (h - 12) / 2);
                btn.render(g, mx, my, pt);
            }
            @Override public boolean mouseClicked(double mx, double my, int b) {
                return btn.mouseClicked(mx, my, b);
            }
            @Override public Component getNarration() { return Component.literal(name); }
        }
    }
}