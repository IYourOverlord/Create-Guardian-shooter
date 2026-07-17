package com.yourname.cbcautotarget.client;

import com.yourname.cbcautotarget.blockentity.ControllerBlockEntity;
import com.yourname.cbcautotarget.network.SetFireFrequencyPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Мини-экран настройки частоты синхронного огня (Fire Trigger).
 *
 * Частота — простое числовое значение 0-9999. 0 означает "выключено":
 * контроллер не рассылает и не принимает сигналы синхронного огня.
 * Любое другое значение объединяет контроллеры с таким же числом в
 * пределах 5 блоков друг от друга: как только один из них реально
 * стреляет по своей цели, все остальные с той же частотой тоже
 * открывают огонь, даже если сами ни на кого не навелись.
 */
public class FireFrequencyScreen extends Screen {

    private static final int PANEL_W  = 176;
    private static final int PANEL_H  = 84;
    private static final int HEADER_H = 22;
    private static final int SIDE_PAD = 12;

    private static final int BG        = 0xD0101010;
    private static final int BORDER    = 0xFF555555;
    private static final int TITLE_CLR = 0xFFFFFF;
    private static final int LABEL_CLR = 0xAAAAAA;

    private final Screen                parent;
    private final ControllerBlockEntity blockEntity;
    private final BlockPos              controllerPos;

    private EditBox freqField;
    private Button   applyButton;
    private Button   clearButton;

    public FireFrequencyScreen(Screen parent, ControllerBlockEntity blockEntity) {
        super(Component.translatable("gui.cbc_autotarget.fire_trigger.title"));
        this.parent        = parent;
        this.blockEntity   = blockEntity;
        this.controllerPos = blockEntity.getBlockPos();
    }

    @Override
    protected void init() {
        super.init();

        int px = (width  - PANEL_W) / 2;
        int py = (height - PANEL_H) / 2;

        int fieldY = py + HEADER_H + 16;
        int fieldW = PANEL_W - SIDE_PAD * 2;

        freqField = new EditBox(font,
                px + SIDE_PAD, fieldY,
                fieldW, 16,
                Component.translatable("gui.cbc_autotarget.fire_trigger.hint"));
        freqField.setMaxLength(4);
        freqField.setHint(Component.translatable("gui.cbc_autotarget.fire_trigger.hint"));
        // 0 отображаем как пустое поле — так яснее видно, что частота не задана
        int current = blockEntity.getFireFrequency();
        freqField.setValue(current > 0 ? String.valueOf(current) : "");
        // Разрешаем вводить только цифры
        freqField.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
        addRenderableWidget(freqField);
        setInitialFocus(freqField);

        int btnY = fieldY + 16 + 6;
        int btnW = (fieldW - 4) / 2;

        applyButton = Button.builder(
                        Component.translatable("gui.cbc_autotarget.fire_trigger.apply"),
                        btn -> onApply())
                .pos(px + SIDE_PAD, btnY)
                .size(btnW, 18)
                .build();
        addRenderableWidget(applyButton);

        clearButton = Button.builder(
                        Component.translatable("gui.cbc_autotarget.fire_trigger.clear"),
                        btn -> onClear())
                .pos(px + SIDE_PAD + btnW + 4, btnY)
                .size(btnW, 18)
                .build();
        addRenderableWidget(clearButton);
    }

    private void onApply() {
        String raw = freqField.getValue().strip();
        int freq = raw.isEmpty() ? 0 : clamp(parseOrZero(raw));
        blockEntity.setFireFrequency(freq);
        PacketDistributor.sendToServer(new SetFireFrequencyPacket(controllerPos, freq));
        freqField.setValue(freq > 0 ? String.valueOf(freq) : "");
    }

    private void onClear() {
        freqField.setValue("");
        blockEntity.setFireFrequency(0);
        PacketDistributor.sendToServer(new SetFireFrequencyPacket(controllerPos, 0));
    }

    private static int parseOrZero(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private static int clamp(int v) { return Math.max(0, Math.min(9999, v)); }

    @Override public void onClose() { Minecraft.getInstance().setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (freqField != null && freqField.isFocused()) {
            if (key == 256) { onClose(); return true; }   // ESC — закрыть
            if (key == 257) { onApply(); return true; }   // Enter — применить
            return freqField.keyPressed(key, scan, mods);
        }
        if (key == 256) { onClose(); return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g, mx, my, pt);
        drawPanel(g);
        super.render(g, mx, my, pt);

        int px = (width  - PANEL_W) / 2;
        int py = (height - PANEL_H) / 2;
        g.drawString(font,
                Component.translatable("gui.cbc_autotarget.fire_trigger.label"),
                px + SIDE_PAD, py + HEADER_H + 4, LABEL_CLR, false);
    }

    private void drawPanel(GuiGraphics g) {
        int x = (width  - PANEL_W) / 2;
        int y = (height - PANEL_H) / 2;
        g.fill(x, y, x + PANEL_W, y + PANEL_H, BG);
        g.fill(x,             y,              x + PANEL_W, y + 1,          BORDER);
        g.fill(x,             y + PANEL_H - 1, x + PANEL_W, y + PANEL_H,    BORDER);
        g.fill(x,             y,              x + 1,        y + PANEL_H,    BORDER);
        g.fill(x + PANEL_W-1, y,              x + PANEL_W,  y + PANEL_H,    BORDER);
        g.fill(x + 4, y + HEADER_H - 1, x + PANEL_W - 4, y + HEADER_H, BORDER);
        g.drawCenteredString(font, title, x + PANEL_W / 2, y + 7, TITLE_CLR);
    }
}
