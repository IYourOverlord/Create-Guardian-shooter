package com.yourname.cbcautotarget.client;

import com.yourname.cbcautotarget.filter.TargetCategory;
import com.yourname.cbcautotarget.filter.TargetFilterData;
import com.yourname.cbcautotarget.menu.ControllerMenu;
import com.yourname.cbcautotarget.network.UpdateFilterPacket;
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

public class TargetFilterScreen extends Screen {

    private static final int PANEL_W   = 170;
    private static final int PANEL_H   = 138;   // +22 для строки whitelist
    private static final int CHECK_STEP = 20;
    private static final int BG        = 0xD0101010;
    private static final int BORDER    = 0xFF555555;
    private static final int TITLE_CLR = 0xFFFFFF;

    private final Screen          parent;
    private final BlockPos        controllerPos;
    private final TargetFilterData filterData;
    private int                   localMask;

    private final List<Checkbox>       checkboxes = new ArrayList<>();
    private final List<TargetCategory> categories = new ArrayList<>();
    private Button whitelistButton;

    public TargetFilterScreen(Screen parent, ControllerMenu menu, int initialMask) {
        super(Component.translatable("gui.cbc_autotarget.filter.title"));
        this.parent        = parent;
        this.controllerPos = menu.getBlockEntity().getBlockPos();
        this.filterData    = menu.getBlockEntity().getFilterData();
        this.localMask     = initialMask;
    }

    @Override
    protected void init() {
        super.init();
        checkboxes.clear();
        categories.clear();

        int px = (width  - PANEL_W) / 2;
        int py = (height - PANEL_H) / 2;

        TargetCategory[] cats = TargetCategory.values();
        for (int i = 0; i < cats.length; i++) {
            TargetCategory cat = cats[i];
            Checkbox cb = Checkbox.builder(Component.translatable(cat.translationKey), font)
                    .pos(px + 12, py + 22 + i * CHECK_STEP)
                    .selected((localMask & cat.mask()) != 0)
                    .build();
            cb.setTooltip(Tooltip.create(Component.translatable(cat.translationKey + ".tooltip")));
            addRenderableWidget(cb);
            checkboxes.add(cb);
            categories.add(cat);
        }

        // Кнопка "Manage Whitelist..." — под последним чекбоксом
        int btnY = py + 22 + cats.length * CHECK_STEP + 2;
        whitelistButton = Button.builder(
                        Component.translatable("gui.cbc_autotarget.whitelist.manage"),
                        btn -> openWhitelistScreen())
                .pos(px + 12, btnY).size(PANEL_W - 24, 14).build();
        updateWhitelistBtn();
        addRenderableWidget(whitelistButton);

        addRenderableWidget(
                Button.builder(Component.translatable("gui.done"), b -> onClose())
                        .pos(px + (PANEL_W - 60) / 2, py + PANEL_H - 20).size(60, 14).build()
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
        PacketDistributor.sendToServer(new UpdateFilterPacket(controllerPos, localMask));
        updateWhitelistBtn();
    }

    private void updateWhitelistBtn() {
        if (whitelistButton == null) return;
        boolean playersOn = (localMask & TargetCategory.PLAYERS.mask()) != 0;
        whitelistButton.active = playersOn;
        whitelistButton.setTooltip(playersOn ? null
                : Tooltip.create(Component.translatable("gui.cbc_autotarget.whitelist.disabled_hint")));
    }

    private void openWhitelistScreen() {
        Minecraft.getInstance().setScreen(new PlayerWhitelistScreen(
                this, controllerPos,
                filterData.isWhitelistEnabled(),
                filterData.getWhitelist()
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
        int x = (width  - PANEL_W) / 2;
        int y = (height - PANEL_H) / 2;
        g.fill(x, y, x+PANEL_W, y+PANEL_H, BG);
        g.fill(x,            y,             x+PANEL_W, y+1,         BORDER);
        g.fill(x,            y+PANEL_H-1,   x+PANEL_W, y+PANEL_H,   BORDER);
        g.fill(x,            y,             x+1,        y+PANEL_H,  BORDER);
        g.fill(x+PANEL_W-1,  y,             x+PANEL_W,  y+PANEL_H,  BORDER);
        g.fill(x+4, y+17, x+PANEL_W-4, y+18, BORDER);
        g.drawCenteredString(font, title, x+PANEL_W/2, y+7, TITLE_CLR);
    }
}