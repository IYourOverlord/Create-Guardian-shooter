package com.yourname.cbcautotarget.client;

import com.yourname.cbcautotarget.menu.CommanderMenu;
import com.yourname.cbcautotarget.network.ToggleCommanderPacket;
import com.yourname.cbcautotarget.network.UpdateCommanderKeyPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

public class CommanderScreen extends AbstractContainerScreen<CommanderMenu> {

    private static final int GUI_WIDTH  = 240;
    private static final int GUI_HEIGHT = 100;

    private static final int PAD     = 8;
    private static final int BTN_H   = 20;
    private static final int APPLY_W = 52;
    private static final int GAP     = 3;

    private static final int BTN_W = (GUI_WIDTH - PAD * 2 - GAP * 2) / 3;

    private static final int COLOR_BG     = 0xC0000000;
    private static final int COLOR_BORDER = 0xFF444444;
    private static final int COLOR_LABEL  = 0xAAAAAA;
    private static final int COLOR_TITLE  = 0xFFFFFF;

    private Button  filterButton;
    private Button  activateButton;
    private Button  deactivateButton;
    private Button  applyKeyButton;
    private EditBox keyField;
    private int     lastKnownMask;

    public CommanderScreen(CommanderMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth     = GUI_WIDTH;
        imageHeight    = GUI_HEIGHT;
        lastKnownMask  = menu.getFilterMask();
    }

    @Override
    protected void init() {
        super.init();

        int row1Y = topPos + PAD;

        filterButton = Button.builder(
                        Component.translatable("gui.cbc_autotarget.filter.open"),
                        btn -> Minecraft.getInstance().setScreen(
                                new CommanderFilterScreen(this, menu, lastKnownMask)))
                .pos(leftPos + PAD, row1Y)
                .size(BTN_W, BTN_H)
                .build();
        addRenderableWidget(filterButton);

        activateButton = Button.builder(
                        Component.translatable("gui.cbc_autotarget.activate"),
                        btn -> onActivate())
                .pos(leftPos + PAD + BTN_W + GAP, row1Y)
                .size(BTN_W, BTN_H)
                .build();
        addRenderableWidget(activateButton);

        deactivateButton = Button.builder(
                        Component.translatable("gui.cbc_autotarget.deactivate"),
                        btn -> onDeactivate())
                .pos(leftPos + PAD + (BTN_W + GAP) * 2, row1Y)
                .size(BTN_W, BTN_H)
                .build();
        addRenderableWidget(deactivateButton);

        int labelY = topPos + PAD + BTN_H + 4 + 4;
        int fieldY = labelY + font.lineHeight + 3;
        int fieldW = GUI_WIDTH - PAD * 2 - APPLY_W - GAP;

        keyField = new EditBox(font,
                leftPos + PAD, fieldY,
                fieldW, BTN_H,
                Component.translatable("gui.cbc_autotarget.alliance_key.hint"));
        keyField.setMaxLength(64);
        keyField.setHint(Component.translatable("gui.cbc_autotarget.alliance_key.hint"));
        keyField.setValue(menu.getBlockEntity().getAllianceKey());
        addRenderableWidget(keyField);

        applyKeyButton = Button.builder(
                        Component.translatable("gui.cbc_autotarget.alliance_key.apply"),
                        btn -> onApplyKey())
                .pos(leftPos + PAD + fieldW + GAP, fieldY)
                .size(APPLY_W, BTN_H)
                .build();
        addRenderableWidget(applyKeyButton);
    }

    private void onActivate() {
        PacketDistributor.sendToServer(
                new ToggleCommanderPacket(menu.getBlockEntity().getBlockPos(), true));
    }

    private void onDeactivate() {
        PacketDistributor.sendToServer(
                new ToggleCommanderPacket(menu.getBlockEntity().getBlockPos(), false));
    }

    private void onApplyKey() {
        String key = keyField.getValue().strip();
        PacketDistributor.sendToServer(
                new UpdateCommanderKeyPacket(menu.getBlockEntity().getBlockPos(), key));
        menu.getBlockEntity().setAllianceKey(key);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.fill(leftPos, topPos, leftPos + GUI_WIDTH, topPos + GUI_HEIGHT, COLOR_BG);

        g.fill(leftPos,                 topPos,                  leftPos + GUI_WIDTH, topPos + 1,           COLOR_BORDER);
        g.fill(leftPos,                 topPos + GUI_HEIGHT - 1, leftPos + GUI_WIDTH, topPos + GUI_HEIGHT,  COLOR_BORDER);
        g.fill(leftPos,                 topPos,                  leftPos + 1,         topPos + GUI_HEIGHT,  COLOR_BORDER);
        g.fill(leftPos + GUI_WIDTH - 1, topPos,                  leftPos + GUI_WIDTH, topPos + GUI_HEIGHT,  COLOR_BORDER);

        int divY = topPos + PAD + BTN_H + 4;
        g.fill(leftPos + 4, divY, leftPos + GUI_WIDTH - 4, divY + 1, COLOR_BORDER);

        int labelY = divY + 5;
        g.drawString(font,
                Component.translatable("gui.cbc_autotarget.alliance_key.label"),
                leftPos + PAD, labelY, COLOR_LABEL, false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        lastKnownMask = menu.getFilterMask();
        renderBackground(g, mouseX, mouseY, partialTick);
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, title, PAD, -10, COLOR_TITLE, false);
    }
    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (keyField != null && keyField.isFocused()) {
            if (key == 256) { onClose(); return true; }   // ESC — закрыть
            if (key == 257) { onApplyKey(); return true; } // Enter — применить
            return keyField.keyPressed(key, scan, mods);   // всё остальное в поле
        }
        if (key == 256) { onClose(); return true; }
        return super.keyPressed(key, scan, mods);
    }
}