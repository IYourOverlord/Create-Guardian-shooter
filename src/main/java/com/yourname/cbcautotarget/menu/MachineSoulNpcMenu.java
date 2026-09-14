package com.yourname.cbcautotarget.menu;

import com.yourname.cbcautotarget.ModBlockEntities;
import com.yourname.cbcautotarget.ModBlocks;
import com.yourname.cbcautotarget.ModMenus;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Меню вкладки NPC — пока без слотов и без данных, только каркас навигации
 * (назад / выход). Контент будет добавлен позже.
 */
public class MachineSoulNpcMenu extends AbstractContainerMenu {

    public final MachineSoulBlockEntity blockEntity;
    public final BlockPos blockPos;

    // ── Клиентский конструктор ────────────────────────────────────────────────
    public MachineSoulNpcMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        this(id, inv, readDummy(buf));
    }

    private static MachineSoulBlockEntity readDummy(RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        return new MachineSoulBlockEntity(
                ModBlockEntities.MACHINE_SOUL.get(), pos,
                ModBlocks.MACHINE_SOUL.get().defaultBlockState());
    }

    // ── Серверный конструктор ─────────────────────────────────────────────────
    public MachineSoulNpcMenu(int id, Inventory inv, MachineSoulBlockEntity be) {
        super(ModMenus.MACHINE_SOUL_NPC.get(), id);
        this.blockEntity = be;
        this.blockPos    = be.getBlockPos();
    }

    @Override public ItemStack quickMoveStack(Player p, int i) { return ItemStack.EMPTY; }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (blockEntity.getLevel() != null && !blockEntity.getLevel().isClientSide())
            blockEntity.onMenuClosed(player);
    }

    @Override public boolean stillValid(Player p) {
        if (blockEntity.getLevel() == null) return true;
        return blockEntity.getLevel().getBlockState(blockPos).is(ModBlocks.MACHINE_SOUL.get())
                || com.yourname.cbcautotarget.compat.SableCompat.isAvailable();
    }
}
