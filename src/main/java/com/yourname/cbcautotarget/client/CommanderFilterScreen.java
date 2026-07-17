package com.yourname.cbcautotarget.client;

import com.yourname.cbcautotarget.filter.TargetCategory;
import com.yourname.cbcautotarget.filter.TargetFilterData;
import com.yourname.cbcautotarget.menu.CommanderMenu;
import com.yourname.cbcautotarget.network.UpdateCommanderFilterPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

public class CommanderFilterScreen extends Screen {

    private static final int CHECK_STEP = 22;
    private static final int PANEL_W    = 190;
    private static final int HEADER_H   = 22;
    private static final int FOOTER_H   = 24;
    private static final int SIDE_PAD   = 12;

    private static final int BG        = 0xD0101010;
    private static final int BORDER    = 0xFF555555;
    private static final int TITLE_CLR = 0xFFFFFF;

    private final Screen           parent;
    private final BlockPos         commanderPos;
    private final TargetFilterData filterData;
    private int                    localMask;

    private final List<Checkbox>       checkboxes = new ArrayList<>();
    private final List<TargetCategory> categories = new ArrayList<>();
    private Button whitelistButton;

    public CommanderFilterScreen(Screen parent, CommanderMenu menu, int initialMask) {
        super(Component.translatable("gui.cbc_autotarget.filter.title"));
        this.parent       = parent;
        this.commanderPos = menu.getBlockEntity().getBlockPos();
        this.filterData   = menu.getBlockEntity().getFilterData();
        this.localMask    = initialMask;
    }

    private int panelHeight() {
        int cats = TargetCategory.values().length;
        return HEADER_H + cats * CHECK_STEP + 4 + 16 + FOOTER_H;
    }

    @Override
    protected void init() {
        super.init();
        checkboxes.clear();
        categories.clear();

        int px = (width  - PANEL_W)       / 2;
        int py = (height - panelHeight()) / 2;

        TargetCategory[] cats = TargetCategory.values();
        for (int i = 0; i < cats.length; i++) {
            TargetCategory cat = cats[i];
            Checkbox cb = Checkbox.builder(Component.translatable(cat.translationKey), font)
                    .pos(px + SIDE_PAD, py + HEADER_H + i * CHECK_STEP)
                    .selected((localMask & cat.mask()) != 0)
                    .build();
            cb.setTooltip(Tooltip.create(Component.translatable(cat.translationKey + ".tooltip")));
            addRenderableWidget(cb);
            checkboxes.add(cb);
            categories.add(cat);
        }

        int wlBtnY = py + HEADER_H + cats.length * CHECK_STEP + 4;
        whitelistButton = Button.builder(
                        Component.translatable("gui.cbc_autotarget.whitelist.manage"),
                        btn -> openWhitelistScreen())
                .pos(px + SIDE_PAD, wlBtnY)
                .size(PANEL_W - SIDE_PAD * 2, 16)
                .build();
        updateWhitelistBtn();
        addRenderableWidget(whitelistButton);

        int doneY = wlBtnY + 16 + 4;
        addRenderableWidget(
                Button.builder(Component.translatable("gui.done"), b -> onClose())
                        .pos(px + (PANEL_W - 60) / 2, doneY)
                        .size(60, 16)
                        .build()
        );
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        boolean[] before = new boolean[checkboxes.size()];
        for (int i = 0; i < checkboxes.size(); i++) before[i] = checkboxes.get(i).selected();
        boolean result = super.mouseClicked(mx, my, btn);
        for (int i = 0; i < checkboxes.size(); i++) {
            boolean after = checkboxes.get(i).selected();
            if (before[i] != after) onCheckboxToggled(categories.get(i), after);
        }
        return result;
    }

    private void onCheckboxToggled(TargetCategory cat, boolean enabled) {
        if (enabled) localMask |=  cat.mask();
        else         localMask &= ~cat.mask();
        PacketDistributor.sendToServer(new UpdateCommanderFilterPacket(commanderPos, localMask));
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
        // ИСПРАВЛЕНИЕ: передаём живую ссылку на filterData, а не снимок списка
        Minecraft.getInstance().setScreen(new CommanderWhitelistScreen(
                this, commanderPos, filterData
        ));
    }

    @Override public void onClose() { Minecraft.getInstance().setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == 256) { onClose(); return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g, mx, my, pt);
        drawPanel(g);
        super.render(g, mx, my, pt);
    }

    private void drawPanel(GuiGraphics g) {
        int ph = panelHeight();
        int x  = (width  - PANEL_W) / 2;
        int y  = (height - ph)      / 2;
        g.fill(x, y, x + PANEL_W, y + ph, BG);
        g.fill(x,              y,        x + PANEL_W, y + 1,       BORDER);
        g.fill(x,              y + ph-1, x + PANEL_W, y + ph,      BORDER);
        g.fill(x,              y,        x + 1,        y + ph,      BORDER);
        g.fill(x + PANEL_W-1,  y,        x + PANEL_W,  y + ph,     BORDER);
        g.fill(x + 4, y + HEADER_H - 1, x + PANEL_W - 4, y + HEADER_H, BORDER);
        g.drawCenteredString(font, title, x + PANEL_W / 2, y + 7, TITLE_CLR);
    }
}