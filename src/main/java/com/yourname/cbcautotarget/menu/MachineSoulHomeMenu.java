package com.yourname.cbcautotarget.menu;

import com.yourname.cbcautotarget.ModMenus;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Меню главной страницы (хаба) Machine Soul.
 * Не содержит слотов — только BlockPos для навигации и текущее состояние
 * флагов "Поиск цели" и "Только на физической конструкции" (для кнопок
 * на главной странице).
 */
public class MachineSoulHomeMenu extends AbstractContainerMenu {

    public final BlockPos blockPos;
    private boolean targetSearchActive;
    private boolean requireSubLevel;

    // ── Клиентский конструктор (через сеть) ──────────────────────────────────
    public MachineSoulHomeMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        this(id, buf.readBlockPos(), buf.readBoolean(), buf.readBoolean());
    }

    // ── Серверный конструктор ─────────────────────────────────────────────────
    public MachineSoulHomeMenu(int id, Inventory inv, MachineSoulBlockEntity be) {
        this(id, be.getBlockPos(), be.isTargetSearchActive(), be.isRequireSubLevel());
    }

    /** Конструктор для совместимости — без BlockEntity (флаги по умолчанию: поиск включён, sub-level-режим выключен). */
    public MachineSoulHomeMenu(int id, Inventory inv, BlockPos pos) {
        this(id, pos, true, false);
    }

    private MachineSoulHomeMenu(int id, BlockPos pos, boolean searchActive, boolean requireSubLevel) {
        super(ModMenus.MACHINE_SOUL_HOME.get(), id);
        this.blockPos = pos;
        this.targetSearchActive = searchActive;
        this.requireSubLevel = requireSubLevel;
        // Инвентарь игрока не добавляем — на главной странице нет слотов
    }

    public boolean isTargetSearchActive() { return targetSearchActive; }
    public void setTargetSearchActive(boolean active) { this.targetSearchActive = active; }

    public boolean isRequireSubLevel() { return requireSubLevel; }
    public void setRequireSubLevel(boolean require) { this.requireSubLevel = require; }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
