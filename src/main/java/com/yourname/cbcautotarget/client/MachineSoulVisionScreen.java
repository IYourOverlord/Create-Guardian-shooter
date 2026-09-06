package com.yourname.cbcautotarget.client;

import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.Tab;
import com.yourname.cbcautotarget.menu.MachineSoulVisionMenu;
import com.yourname.cbcautotarget.network.SaveMachineSoulVisionPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.network.PacketDistributor;

/** Vision settings with direct numeric entry and radial controls. */
public class MachineSoulVisionScreen extends BaseMachineSoulScreen<MachineSoulVisionMenu> {
    private static final int GUI_H = 150;
    private EditBox radiusBox, keepBox, standBox;
    private int selected = -1;

    public MachineSoulVisionScreen(MachineSoulVisionMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, Tab.VISION, menu.blockPos, GUI_H);
    }

    @Override protected int getInvYBase() { return 0; }
    @Override protected boolean isInventoryHidden() { return true; }
    @Override protected void renderSlot(GuiGraphics g, Slot slot) { }

    @Override protected void init() {
        super.init();
        radiusBox = box("Radius", menu.getDetectionRadius(), 150, 42);
        keepBox = box("Keep", menu.getKeepDistance(), 180, 72);
        standBox = box("Still", menu.getStandStillDistance(), 150, 102);
        addRenderableWidget(radiusBox);
        addRenderableWidget(keepBox);
        addRenderableWidget(standBox);
    }

    private EditBox box(String hint, int value, int x, int y) {
        EditBox b = new EditBox(font, leftPos + x, topPos + y, 52, 16, Component.literal(hint));
        b.setValue(Integer.toString(value));
        b.setFilter(v -> v.isEmpty() || v.matches("\\d{0,3}"));
        b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(hint + " (blocks)")));
        return b;
    }

    @Override protected void renderContent(GuiGraphics g, int lx, int ty, int mx, int my) {
        drawSaveButton(g, lx, ty, mx, my);
        drawRadialButton(g, lx + 135, ty + 42, "RADIUS", selected == 0, mx, my);
        drawRadialButton(g, lx + 175, ty + 72, "KEEP", selected == 1, mx, my);
        drawRadialButton(g, lx + 135, ty + 102, "STILL", selected == 2, mx, my);
        g.drawString(font, Component.literal("Vision"), lx + 52, ty + 132, COL_TEXT_DIM, false);
        if (mx >= lx + 135 && mx < lx + 195 && my >= ty + 32 && my < ty + 58)
            g.renderTooltip(font, Component.literal("Detection radius in blocks"), mx, my);
        else if (mx >= lx + 175 && mx < lx + 235 && my >= ty + 62 && my < ty + 88)
            g.renderTooltip(font, Component.literal("Distance to keep from a target"), mx, my);
        else if (mx >= lx + 135 && mx < lx + 195 && my >= ty + 92 && my < ty + 118)
            g.renderTooltip(font, Component.literal("Stand-still zone in blocks"), mx, my);
    }

    private void drawRadialButton(GuiGraphics g, int x, int y, String label, boolean active, int mx, int my) {
        boolean hov = mx >= x && mx < x + 60 && my >= y && my < y + 20;
        g.fill(x, y, x + 60, y + 20, active || hov ? COL_SAVE_HOVER_BG : COL_SAVE_BG);
        drawBorder(g, x, y, 60, 20, active || hov ? COL_ACCENT2 : COL_SAVE_BORDER);
        g.drawCenteredString(font, label, x + 30, y + 6, active || hov ? COL_ACCENT2 : COL_TEXT);
    }

    @Override public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            if (hit(mx, my, 135, 42)) { selected = 0; radiusBox.setFocused(true); return true; }
            if (hit(mx, my, 175, 72)) { selected = 1; keepBox.setFocused(true); return true; }
            if (hit(mx, my, 135, 102)) { selected = 2; standBox.setFocused(true); return true; }
        }
        return super.mouseClicked(mx, my, button);
    }
    private boolean hit(double x, double y, int bx, int by) {
        return x >= leftPos + bx && x < leftPos + bx + 60 && y >= topPos + by && y < topPos + by + 20;
    }

    private int value(EditBox b, int min, int max, int fallback) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(b.getValue()))); }
        catch (NumberFormatException e) { return fallback; }
    }

    @Override protected boolean onSaveClicked() {
        int r = value(radiusBox, MachineSoulBlockEntity.MIN_DETECTION_RADIUS, MachineSoulBlockEntity.MAX_DETECTION_RADIUS, menu.getDetectionRadius());
        int k = value(keepBox, MachineSoulBlockEntity.MIN_KEEP_DISTANCE, MachineSoulBlockEntity.MAX_KEEP_DISTANCE, menu.getKeepDistance());
        int s = value(standBox, MachineSoulBlockEntity.MIN_STAND_STILL_DISTANCE, MachineSoulBlockEntity.MAX_STAND_STILL_DISTANCE, menu.getStandStillDistance());
        PacketDistributor.sendToServer(new SaveMachineSoulVisionPacket(blockPos, r, k, s));
        onClose();
        return true;
    }
}
