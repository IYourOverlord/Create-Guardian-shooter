package com.yourname.cbcautotarget.client;

import com.yourname.cbcautotarget.network.UpdateWhitelistPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class PlayerWhitelistScreen extends Screen {

    private static final int PANEL_W     = 210;
    private static final int PANEL_H     = 210;
    private static final int ITEM_H      = 20;
    private static final int BG          = 0xD0101010;
    private static final int BORDER      = 0xFF555555;
    private static final int TITLE_CLR   = 0xFFFFFF;
    private static final int HINT_CLR    = 0xFF888888;
    private static final int LIST_BG     = 0xFF1A1A22;

    private final Screen   parent;
    private final BlockPos pos;
    private boolean        wlEnabled;
    private final List<String> list = new ArrayList<>();

    private NameList  nameList;
    private EditBox   input;
    private Button    toggleBtn;

    public PlayerWhitelistScreen(Screen parent, BlockPos pos,
                                 boolean wlEnabled, Set<String> initial) {
        super(Component.translatable("gui.cbc_autotarget.whitelist.title"));
        this.parent    = parent;
        this.pos       = pos;
        this.wlEnabled = wlEnabled;
        this.list.addAll(initial);
    }

    @Override
    protected void init() {
        int px = (width  - PANEL_W) / 2;
        int py = (height - PANEL_H) / 2;

        // ── Кнопка-переключатель ──────────────────────────────────────────
        toggleBtn = Button.builder(toggleLabel(), b -> onToggle())
                .pos(px + 8, py + 20).size(PANEL_W - 16, 14).build();
        addRenderableWidget(toggleBtn);

        // ── Список ────────────────────────────────────────────────────────
        int listTop = py + 40, listBot = py + PANEL_H - 46;
        nameList = new NameList(minecraft, PANEL_W - 16, listBot - listTop, listTop, ITEM_H, px + 8);
        nameList.refresh();
        addWidget(nameList);

        // ── Поле ввода + Add ──────────────────────────────────────────────
        input = new EditBox(font, px + 8, py + PANEL_H - 40, PANEL_W - 68, 14,
                Component.translatable("gui.cbc_autotarget.whitelist.input_hint"));
        input.setMaxLength(16);
        input.setHint(Component.translatable("gui.cbc_autotarget.whitelist.input_hint"));
        addRenderableWidget(input);

        addRenderableWidget(
                Button.builder(Component.translatable("gui.cbc_autotarget.whitelist.add"), b -> onAdd())
                        .pos(px + PANEL_W - 56, py + PANEL_H - 40).size(48, 14).build()
        );

        // ── Done ─────────────────────────────────────────────────────────
        addRenderableWidget(
                Button.builder(Component.translatable("gui.done"), b -> onClose())
                        .pos(px + (PANEL_W - 60) / 2, py + PANEL_H - 20).size(60, 14).build()
        );
    }

    // ── Действия ─────────────────────────────────────────────────────────────

    private void onToggle() {
        wlEnabled = !wlEnabled;
        toggleBtn.setMessage(toggleLabel());
        PacketDistributor.sendToServer(UpdateWhitelistPacket.setEnabled(pos, wlEnabled));
    }

    private void onAdd() {
        String name = input.getValue().trim();
        if (name.isEmpty() || name.length() > 16 || list.contains(name)) { input.setValue(""); return; }
        if (list.size() >= 50) return;
        list.add(name);
        nameList.refresh();
        input.setValue("");
        PacketDistributor.sendToServer(UpdateWhitelistPacket.add(pos, name));
    }

    private void onRemove(String name) {
        list.remove(name);
        nameList.refresh();
        PacketDistributor.sendToServer(UpdateWhitelistPacket.remove(pos, name));
    }

    private Component toggleLabel() {
        return wlEnabled
                ? Component.translatable("gui.cbc_autotarget.whitelist.enabled")
                : Component.translatable("gui.cbc_autotarget.whitelist.disabled");
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == 257 && input.isFocused()) { onAdd(); return true; }
        if (key == 256) { onClose(); return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override public void onClose() { Minecraft.getInstance().setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }

    // ── Рендер ───────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g, mx, my, pt);
        drawPanel(g);
        super.render(g, mx, my, pt);
        nameList.render(g, mx, my, pt);

        if (list.size() >= 50) {
            int px = (width - PANEL_W) / 2;
            int py = (height - PANEL_H) / 2;
            g.drawString(font, Component.translatable("gui.cbc_autotarget.whitelist.limit"),
                    px + 8, py + PANEL_H - 54, HINT_CLR, false);
        }
    }

    private void drawPanel(GuiGraphics g) {
        int x = (width  - PANEL_W) / 2;
        int y = (height - PANEL_H) / 2;
        g.fill(x, y, x + PANEL_W, y + PANEL_H, BG);
        g.fill(x,              y,              x + PANEL_W, y + 1,          BORDER);
        g.fill(x,              y+PANEL_H-1,    x + PANEL_W, y + PANEL_H,    BORDER);
        g.fill(x,              y,              x + 1,        y + PANEL_H,   BORDER);
        g.fill(x+PANEL_W-1,    y,              x + PANEL_W,  y + PANEL_H,   BORDER);
        g.fill(x+4, y+17, x+PANEL_W-4, y+18, BORDER);
        g.fill(x+4, y+36, x+PANEL_W-4, y+37, BORDER);
        g.fill(x+4, y+PANEL_H-46, x+PANEL_W-4, y+PANEL_H-45, BORDER);
        g.drawCenteredString(font, title, x + PANEL_W/2, y + 7, TITLE_CLR);

        // Фон списка
        int lx=x+8, ly=y+40, lw=PANEL_W-16, lh=PANEL_H-86;
        g.fill(lx, ly, lx+lw, ly+lh, LIST_BG);

        // Подпись "0 / 50"
        g.drawString(font, list.size() + " / 50",
                x + PANEL_W - 8 - font.width(list.size() + " / 50"),
                y + PANEL_H - 45, HINT_CLR, false);
    }

    // ════════════════════════════════════════════════════════════════════════
    private class NameList extends ObjectSelectionList<NameList.Entry> {
        NameList(Minecraft mc, int w, int h, int y, int ih, int x) {
            super(mc, w, h, y, ih);
            setX(x);
        }
        void refresh() {
            clearEntries();
            list.forEach(n -> addEntry(new Entry(n)));
        }
        @Override public int getRowWidth() { return PANEL_W - 16; }
        @Override protected int getScrollbarPosition() { return getX() + PANEL_W - 20; }

        class Entry extends ObjectSelectionList.Entry<Entry> {
            private final String name;
            private final Button btn;
            Entry(String name) {
                this.name = name;
                this.btn = Button.builder(
                                Component.translatable("gui.cbc_autotarget.whitelist.remove"),
                                b -> onRemove(name))
                        .size(48, 12).build();
            }
            @Override
            public void render(GuiGraphics g, int idx, int top, int left, int w, int h,
                               int mx, int my, boolean hov, float pt) {
                g.drawString(font, name, left + 4, top + (h-8)/2, 0xFFFFFF, false);
                btn.setX(left + w - 52);
                btn.setY(top + (h-12)/2);
                btn.render(g, mx, my, pt);
            }
            @Override public boolean mouseClicked(double mx, double my, int btn2) {
                return btn.mouseClicked(mx, my, btn2);
            }
            @Override public Component getNarration() { return Component.literal(name); }
        }
    }
}
