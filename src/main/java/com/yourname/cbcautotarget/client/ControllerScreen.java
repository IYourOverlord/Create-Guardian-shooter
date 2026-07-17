package com.yourname.cbcautotarget.client;

import com.yourname.cbcautotarget.blockentity.ControllerBlockEntity;
import com.yourname.cbcautotarget.menu.ControllerMenu;
import com.yourname.cbcautotarget.network.ToggleRotationAxisPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

public class ControllerScreen extends AbstractContainerScreen<ControllerMenu> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath("cbc_autotarget", "textures/gui/controller.png");

    private static final int GUI_WIDTH  = 176;
    private static final int GUI_HEIGHT = 166;

    // Верхняя панель рисуется программно (не текстурой) над обычным окном
    // инвентаря — там же, где менюшка ControllerMenu освобождает место,
    // сдвигая все слоты вниз на ControllerMenu.TOP_PANEL_HEIGHT.
    private static final int TOP_PANEL_HEIGHT = ControllerMenu.TOP_PANEL_HEIGHT;
    private static final int COLOR_TOP_BG     = 0xFFC6C6C6; // тот же серый, что и текстура GUI
    private static final int COLOR_TOP_BORDER = 0xFF8B8B8B;

    // Кнопки H/V/Fire Trigger расположены в верхней панели, над инвентарём блока.
    private static final int BTN_H_REL_X = 60;
    private static final int BTN_V_REL_X = 98;
    private static final int BTN_FIRE_REL_X = 136;
    private static final int BTN_REL_Y   = 3;
    private static final int BTN_W       = 34;
    private static final int BTN_FIRE_W  = 34;
    private static final int BTN_H_SIZE  = 14;

    private Button btnHorizontal;
    private Button btnVertical;
    private Button btnFireTrigger;

    public ControllerScreen(ControllerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth  = GUI_WIDTH;
        imageHeight = GUI_HEIGHT + TOP_PANEL_HEIGHT;
    }

    @Override
    protected void init() {
        super.init();

        ControllerBlockEntity be = menu.getBlockEntity();

        // Кнопка H (горизонталь / yaw)
        btnHorizontal = Button.builder(
                buildLabel("H", be.isAllowHorizontal()),
                btn -> {
                    boolean newVal = !menu.getBlockEntity().isAllowHorizontal();
                    // Оптимистично обновляем состояние на клиенте для мгновенной реакции
                    menu.getBlockEntity().setAllowHorizontal(newVal);
                    btn.setMessage(buildLabel("H", newVal));
                    PacketDistributor.sendToServer(
                            new ToggleRotationAxisPacket(menu.getBlockEntity().getBlockPos(), true, newVal));
                })
                .pos(leftPos + BTN_H_REL_X, topPos + BTN_REL_Y)
                .size(BTN_W, BTN_H_SIZE)
                .build();

        // Кнопка V (вертикаль / pitch)
        btnVertical = Button.builder(
                buildLabel("V", be.isAllowVertical()),
                btn -> {
                    boolean newVal = !menu.getBlockEntity().isAllowVertical();
                    menu.getBlockEntity().setAllowVertical(newVal);
                    btn.setMessage(buildLabel("V", newVal));
                    PacketDistributor.sendToServer(
                            new ToggleRotationAxisPacket(menu.getBlockEntity().getBlockPos(), false, newVal));
                })
                .pos(leftPos + BTN_V_REL_X, topPos + BTN_REL_Y)
                .size(BTN_W, BTN_H_SIZE)
                .build();

        // Кнопка Fire Trigger — открывает мини-экран настройки частоты синхронного огня
        btnFireTrigger = Button.builder(
                Component.translatable("gui.cbc_autotarget.fire_trigger"),
                btn -> Minecraft.getInstance().setScreen(
                        new FireFrequencyScreen(this, menu.getBlockEntity())))
                .pos(leftPos + BTN_FIRE_REL_X, topPos + BTN_REL_Y)
                .size(BTN_FIRE_W, BTN_H_SIZE)
                .build();

        addRenderableWidget(btnHorizontal);
        addRenderableWidget(btnVertical);
        addRenderableWidget(btnFireTrigger);
    }

    /** Формирует подпись кнопки: "H ✔" или "H ✗" */
    private static Component buildLabel(String axis, boolean enabled) {
        return Component.literal(axis + (enabled ? " \u2714" : " \u2718"));
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        // Верхняя панель — рисуется программно, без текстуры
        graphics.fill(leftPos, topPos, leftPos + GUI_WIDTH, topPos + TOP_PANEL_HEIGHT, COLOR_TOP_BG);
        graphics.fill(leftPos, topPos + TOP_PANEL_HEIGHT - 1, leftPos + GUI_WIDTH, topPos + TOP_PANEL_HEIGHT, COLOR_TOP_BORDER);

        // Стандартное окно инвентаря — из текстуры, смещено вниз на высоту панели
        graphics.blit(TEXTURE, leftPos, topPos + TOP_PANEL_HEIGHT, 0, 0, GUI_WIDTH, GUI_HEIGHT);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);

        // Синхронизируем визуальное состояние кнопок с BlockEntity каждый кадр
        // (на случай если сервер прислал обновление)
        ControllerBlockEntity be = menu.getBlockEntity();
        if (btnHorizontal != null)
            btnHorizontal.setMessage(buildLabel("H", be.isAllowHorizontal()));
        if (btnVertical != null)
            btnVertical.setMessage(buildLabel("V", be.isAllowVertical()));
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 8, TOP_PANEL_HEIGHT + 6, 0x404040, false);
        graphics.drawString(font, playerInventoryTitle, 8, imageHeight - 94, 0x404040, false);
    }
}
