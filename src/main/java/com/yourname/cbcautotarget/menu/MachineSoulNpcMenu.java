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
 * Меню вкладки NPC — пока без слотов, только каркас навигации (назад / выход)
 * и состояние кнопки гироскопической стабилизации Sable.
 */
public class MachineSoulNpcMenu extends AbstractContainerMenu {

    public final MachineSoulBlockEntity blockEntity;
    public final BlockPos blockPos;
    private boolean gyroStabilizationActive;
    private boolean creativeLocked;

    // ── Клиентский конструктор ────────────────────────────────────────────────
    public MachineSoulNpcMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        this(id, inv, readDummy(buf));
    }

    private static MachineSoulBlockEntity readDummy(RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        boolean gyro = buf.readBoolean();
        // creativeLocked добавлен в протокол позже — читаем только если байт есть
        // (защита от рассинхрона клиент/сервер при частичном обновлении)
        boolean locked = buf.isReadable() && buf.readBoolean();
        MachineSoulBlockEntity dummy = new MachineSoulBlockEntity(
                ModBlockEntities.MACHINE_SOUL.get(), pos,
                ModBlocks.MACHINE_SOUL.get().defaultBlockState());
        dummy.setGyroStabilizationActive(gyro);
        dummy.setCreativeLocked(locked);
        return dummy;
    }

    // ── Серверный конструктор ─────────────────────────────────────────────────
    public MachineSoulNpcMenu(int id, Inventory inv, MachineSoulBlockEntity be) {
        super(ModMenus.MACHINE_SOUL_NPC.get(), id);
        this.blockEntity = be;
        this.blockPos    = be.getBlockPos();
        this.gyroStabilizationActive = be.isGyroStabilizationActive();
        this.creativeLocked = be.isCreativeLocked();
    }

    public boolean isGyroStabilizationActive() { return gyroStabilizationActive; }
    public void setGyroStabilizationActive(boolean active) { this.gyroStabilizationActive = active; }

    public boolean isCreativeLocked() { return creativeLocked; }
    public void setCreativeLocked(boolean locked) { this.creativeLocked = locked; }

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