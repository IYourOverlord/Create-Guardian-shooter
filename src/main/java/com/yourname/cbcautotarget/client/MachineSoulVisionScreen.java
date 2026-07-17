package com.yourname.cbcautotarget.client;

import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.Tab;
import com.yourname.cbcautotarget.menu.MachineSoulVisionMenu;
import com.yourname.cbcautotarget.network.SaveMachineSoulVisionPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Optional;

/**
 * Экран Vision — компактная версия.
 *
 * Геометрия одного блока (без hint-строки):
 *   title(9) + 2 + bar(5) + 3 + buttons(11) + 2 + divider(1) + 3 = 36px
 * Три блока: 3 × 36 = 108px
 * + инфо-текст(~20) + отступы(~14) = ~34px
 * CONTENT_START = 18, INV_Y_BASE = 18 + 108 + 34 = 160
 */
public class MachineSoulVisionScreen extends BaseMachineSoulScreen<MachineSoulVisionMenu> {

    // GUI_H = INV_Y_BASE(160) + 3*18(54) + 4(gap) + 18(hotbar) + 4(pad) = 240
    private static final int GUI_H_VISION = MachineSoulVisionMenu.INV_Y_BASE + 54 + 4 + 18 + 4;

    // Компактные константы отступов
    private static final int GAP_TITLE_BAR  = 2;   // между заголовком и баром
    private static final int GAP_BAR_BTN    = 3;   // между баром и кнопками
    private static final int BTN_H          = 11;  // высота кнопок − / +
    private static final int GAP_BTN_DIV    = 2;   // между кнопками и разделителем
    private static final int GAP_DIV_NEXT   = 3;   // после разделителя
    private static final int BAR_H          = 5;   // высота прогресс-бара

    private int radiusValue;
    private int keepDistValue;
    private int standStillValue;

    // Сохраняем Y заголовков для tooltip hit-test
    private int radiusTitleY, keepDistTitleY, standStillTitleY;

    private static final int CONTENT_START = SAVE_BOTTOM + 2; // 18

    public MachineSoulVisionScreen(MachineSoulVisionMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, Tab.VISION, menu.blockPos, GUI_H_VISION);
        radiusValue     = menu.getDetectionRadius();
        keepDistValue   = menu.getKeepDistance();
        standStillValue = menu.getStandStillDistance();
    }

    @Override protected int getInvYBase() { return MachineSoulVisionMenu.INV_Y_BASE; }
    @Override protected int getInvX()    { return MachineSoulVisionMenu.INV_X; }

    @Override
    protected void renderContent(GuiGraphics g, int lx, int ty, int mx, int my) {
        drawSaveButton(g, lx, ty, mx, my);

        int y = ty + CONTENT_START;
        int barX = lx + PAD;
        int barW = GUI_W - PAD * 2;

        // ── Блок 1: Detection Radius ──────────────────────────────────────────
        radiusTitleY = y;
        g.drawString(font,
                Component.translatable("gui.cbc_autotarget.soul.tab.vision.radius_label"),
                lx + PAD, y, COL_TEXT, false);
        y += font.lineHeight + GAP_TITLE_BAR;

        g.fill(barX, y, barX + barW, y + BAR_H, COL_RADIUS_BAR);
        float tR = (float)(radiusValue - MachineSoulBlockEntity.MIN_DETECTION_RADIUS)
                / (MachineSoulBlockEntity.MAX_DETECTION_RADIUS - MachineSoulBlockEntity.MIN_DETECTION_RADIUS);
        int fillR = Math.max(0, (int)(barW * tR));
        if (fillR > 0) g.fill(barX, y, barX + fillR, y + BAR_H, COL_RADIUS_FILL);
        drawBorder(g, barX - 1, y - 1, barW + 2, BAR_H + 2, COL_SLOT_FR);
        y += BAR_H + GAP_BAR_BTN;

        drawMiniButton(g, lx + PAD,      y, "−", mx, my);
        drawMiniButton(g, lx + PAD + 22, y, "+", mx, my);
        String valR = radiusValue + " "
                + Component.translatable("gui.cbc_autotarget.soul.tab.vision.blocks").getString();
        g.drawString(font, valR, lx + PAD + 48, y + 2, COL_ACCENT2, false);
        y += BTN_H + GAP_BTN_DIV;

        g.fill(lx + PAD, y, lx + GUI_W - PAD, y + 1, COL_BORDER);
        y += 1 + GAP_DIV_NEXT;

        // ── Блок 2: Keep Distance ─────────────────────────────────────────────
        keepDistTitleY = y;
        g.drawString(font,
                Component.translatable("gui.cbc_autotarget.soul.tab.vision.keep_distance_label"),
                lx + PAD, y, COL_TEXT, false);
        y += font.lineHeight + GAP_TITLE_BAR;

        g.fill(barX, y, barX + barW, y + BAR_H, COL_RADIUS_BAR);
        if (keepDistValue > 0) {
            float tK = (float)(keepDistValue - MachineSoulBlockEntity.MIN_KEEP_DISTANCE)
                    / (MachineSoulBlockEntity.MAX_KEEP_DISTANCE - MachineSoulBlockEntity.MIN_KEEP_DISTANCE);
            int fillK = Math.max(1, (int)(barW * tK));
            g.fill(barX, y, barX + fillK, y + BAR_H, 0xFF3399BB);
        }
        drawBorder(g, barX - 1, y - 1, barW + 2, BAR_H + 2, COL_SLOT_FR);
        y += BAR_H + GAP_BAR_BTN;

        drawMiniButton(g, lx + PAD,      y, "−", mx, my);
        drawMiniButton(g, lx + PAD + 22, y, "+", mx, my);
        String valK = keepDistValue == 0
                ? Component.translatable("gui.cbc_autotarget.soul.tab.vision.keep_distance_off").getString()
                : keepDistValue + " "
                + Component.translatable("gui.cbc_autotarget.soul.tab.vision.blocks").getString();
        g.drawString(font, valK, lx + PAD + 48, y + 2,
                keepDistValue == 0 ? COL_TEXT_DIM : 0xFF44BBCC, false);
        y += BTN_H + GAP_BTN_DIV;

        g.fill(lx + PAD, y, lx + GUI_W - PAD, y + 1, COL_BORDER);
        y += 1 + GAP_DIV_NEXT;

        // ── Блок 3: Stand Still Zone ──────────────────────────────────────────
        standStillTitleY = y;
        g.drawString(font,
                Component.translatable("gui.cbc_autotarget.soul.tab.vision.stand_still_label"),
                lx + PAD, y, COL_TEXT, false);
        y += font.lineHeight + GAP_TITLE_BAR;

        g.fill(barX, y, barX + barW, y + BAR_H, COL_RADIUS_BAR);
        if (standStillValue > 0) {
            float tS = (float) standStillValue / MachineSoulBlockEntity.MAX_STAND_STILL_DISTANCE;
            int fillS = Math.max(1, (int)(barW * tS));
            g.fill(barX, y, barX + fillS, y + BAR_H, 0xFF88AA44);
        }
        drawBorder(g, barX - 1, y - 1, barW + 2, BAR_H + 2, COL_SLOT_FR);
        y += BAR_H + GAP_BAR_BTN;

        drawMiniButton(g, lx + PAD,      y, "−", mx, my);
        drawMiniButton(g, lx + PAD + 22, y, "+", mx, my);
        String valS = standStillValue == 0
                ? Component.translatable("gui.cbc_autotarget.soul.tab.vision.stand_still_off").getString()
                : standStillValue + " "
                + Component.translatable("gui.cbc_autotarget.soul.tab.vision.blocks").getString();
        g.drawString(font, valS, lx + PAD + 48, y + 2,
                standStillValue == 0 ? COL_TEXT_DIM : 0xFF99BB55, false);
        y += BTN_H + GAP_BTN_DIV;

        g.fill(lx + PAD, y, lx + GUI_W - PAD, y + 1, COL_BORDER);
        y += 1 + GAP_DIV_NEXT;

        // Инфо-текст
        drawWrappedText(g, lx + PAD, y, GUI_W - PAD * 2,
                Component.translatable("gui.cbc_autotarget.soul.tab.vision.info_movement").getString(),
                COL_TEXT_DIM);

        // Tooltips при наведении на заголовки
        if (my >= radiusTitleY && my < radiusTitleY + font.lineHeight
                && mx >= lx + PAD && mx < lx + GUI_W - PAD) {
            g.renderTooltip(font, List.of(Component.translatable("gui.cbc_autotarget.soul.tab.vision.radius_hint")),
                    Optional.empty(), (int) mx, (int) my);
        } else if (my >= keepDistTitleY && my < keepDistTitleY + font.lineHeight
                && mx >= lx + PAD && mx < lx + GUI_W - PAD) {
            g.renderTooltip(font, List.of(Component.translatable("gui.cbc_autotarget.soul.tab.vision.keep_distance_hint")),
                    Optional.empty(), (int) mx, (int) my);
        } else if (my >= standStillTitleY && my < standStillTitleY + font.lineHeight
                && mx >= lx + PAD && mx < lx + GUI_W - PAD) {
            g.renderTooltip(font, List.of(Component.translatable("gui.cbc_autotarget.soul.tab.vision.stand_still_hint")),
                    Optional.empty(), (int) mx, (int) my);
        }
    }

    private void drawMiniButton(GuiGraphics g, int x, int y, String label, int mx, int my) {
        boolean hov = mx >= x && mx < x + 20 && my >= y && my < y + BTN_H;
        g.fill(x, y, x + 20, y + BTN_H, hov ? COL_SAVE_HOVER_BG : COL_SAVE_BG);
        drawBorder(g, x, y, 20, BTN_H, COL_SAVE_BORDER);
        int tw = font.width(label);
        g.drawString(font, label, x + (20 - tw) / 2, y + (BTN_H - font.lineHeight) / 2,
                COL_SAVE_TEXT, false);
    }

    // Высота одного блока (без hint): title + GAP_TITLE_BAR + BAR_H + GAP_BAR_BTN + BTN_H + GAP_BTN_DIV + divider(1) + GAP_DIV_NEXT
    private int blockH() {
        return font.lineHeight + GAP_TITLE_BAR + BAR_H + GAP_BAR_BTN + BTN_H + GAP_BTN_DIV + 1 + GAP_DIV_NEXT;
    }

    private int getRadiusBtnY() {
        return topPos + CONTENT_START + font.lineHeight + GAP_TITLE_BAR + BAR_H + GAP_BAR_BTN;
    }

    private int getKeepDistBtnY() {
        return getRadiusBtnY() + BTN_H + GAP_BTN_DIV + 1 + GAP_DIV_NEXT
                + font.lineHeight + GAP_TITLE_BAR + BAR_H + GAP_BAR_BTN;
    }

    private int getStandStillBtnY() {
        return getKeepDistBtnY() + BTN_H + GAP_BTN_DIV + 1 + GAP_DIV_NEXT
                + font.lineHeight + GAP_TITLE_BAR + BAR_H + GAP_BAR_BTN;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int bx0 = leftPos + PAD;
        int bx1 = bx0 + 22;

        int ryBtn = getRadiusBtnY();
        if (my >= ryBtn && my < ryBtn + BTN_H) {
            if (mx >= bx0 && mx < bx0 + 20) { changeRadius(-5); return true; }
            if (mx >= bx1 && mx < bx1 + 20) { changeRadius(+5); return true; }
        }

        int kyBtn = getKeepDistBtnY();
        if (my >= kyBtn && my < kyBtn + BTN_H) {
            if (mx >= bx0 && mx < bx0 + 20) { changeKeepDist(-1); return true; }
            if (mx >= bx1 && mx < bx1 + 20) { changeKeepDist(+1); return true; }
        }

        int syBtn = getStandStillBtnY();
        if (my >= syBtn && my < syBtn + BTN_H) {
            if (mx >= bx0 && mx < bx0 + 20) { changeStandStill(-1); return true; }
            if (mx >= bx1 && mx < bx1 + 20) { changeStandStill(+1); return true; }
        }

        return super.mouseClicked(mx, my, button);
    }

    private void changeRadius(int delta) {
        radiusValue = Math.max(MachineSoulBlockEntity.MIN_DETECTION_RADIUS,
                Math.min(MachineSoulBlockEntity.MAX_DETECTION_RADIUS, radiusValue + delta));
        menu.setDetectionRadius(radiusValue);
    }

    private void changeKeepDist(int delta) {
        keepDistValue = Math.max(MachineSoulBlockEntity.MIN_KEEP_DISTANCE,
                Math.min(MachineSoulBlockEntity.MAX_KEEP_DISTANCE, keepDistValue + delta));
        menu.setKeepDistance(keepDistValue);
    }

    private void changeStandStill(int delta) {
        standStillValue = Math.max(MachineSoulBlockEntity.MIN_STAND_STILL_DISTANCE,
                Math.min(MachineSoulBlockEntity.MAX_STAND_STILL_DISTANCE, standStillValue + delta));
        menu.setStandStillDistance(standStillValue);
    }

    @Override
    protected boolean onSaveClicked() {
        PacketDistributor.sendToServer(new SaveMachineSoulVisionPacket(blockPos, radiusValue, keepDistValue, standStillValue));
        onClose();
        return true;
    }
}