package com.yourname.cbcautotarget.client;

import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.Tab;
import com.yourname.cbcautotarget.menu.MachineSoulNpcMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** NPC: пока только страж и переход назад в главное меню — без контента. */
public class MachineSoulNpcScreen extends BaseMachineSoulScreen<MachineSoulNpcMenu> {
    private static final int GUI_H = 150;

    public MachineSoulNpcScreen(MachineSoulNpcMenu m, Inventory i, Component t) {
        super(m, i, t, Tab.NPC, m.blockPos, GUI_H);
    }

    @Override protected int getInvYBase() { return 0; }
    @Override protected boolean isInventoryHidden() { return true; }
    @Override protected void renderContent(GuiGraphics g, int lx, int ty, int mx, int my) { }
    @Override protected void drawSaveButton(GuiGraphics g, int lx, int ty, int mx, int my) { }
    @Override protected boolean isSaveHovered(int mx, int my) { return false; }
    @Override protected boolean onSaveClicked() { return false; }
}
