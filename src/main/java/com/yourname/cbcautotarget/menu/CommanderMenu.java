package com.yourname.cbcautotarget.menu;

import com.yourname.cbcautotarget.ModBlockEntities;
import com.yourname.cbcautotarget.ModBlocks;
import com.yourname.cbcautotarget.ModMenus;
import com.yourname.cbcautotarget.blockentity.CommanderBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;

public class CommanderMenu extends AbstractContainerMenu {

    private final CommanderBlockEntity blockEntity;
    private final ContainerLevelAccess access;
    private final ContainerData data;

    // Кэш маски: обновляется сервером через ContainerData синхронизацию.
    private int filterMaskCache;

    // ── Клиентский конструктор ────────────────────────────────────────────────
    // Сервер пишет в buf: BlockPos + int filterMask + String allianceKey.

    public CommanderMenu(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
        this(containerId, playerInventory, createClientDummy(buf));
    }

    private static CommanderBlockEntity createClientDummy(FriendlyByteBuf buf) {
        BlockPos pos        = buf.readBlockPos();
        int      mask       = buf.readInt();
        String   allianceKey = buf.readUtf(64);
        CommanderBlockEntity dummy = new CommanderBlockEntity(
                ModBlockEntities.COMMANDER.get(),
                pos,
                ModBlocks.COMMANDER.get().defaultBlockState()
        );
        dummy.setFilterMaskClient(mask);
        dummy.setAllianceKey(allianceKey);
        return dummy;
    }

    // ── Серверный конструктор ─────────────────────────────────────────────────

    public CommanderMenu(int containerId, Inventory playerInventory, CommanderBlockEntity blockEntity) {
        super(ModMenus.COMMANDER_MENU.get(), containerId);
        this.blockEntity     = blockEntity;
        this.filterMaskCache = blockEntity.getFilterMask();
        this.access          = ContainerLevelAccess.create(
                blockEntity.getLevel(), blockEntity.getBlockPos());

        this.data = new ContainerData() {
            @Override public int get(int index)             { return index == 0 ? blockEntity.getFilterMask() : 0; }
            @Override public void set(int index, int value) { if (index == 0) filterMaskCache = value; }
            @Override public int getCount()                 { return 1; }
        };
        addDataSlots(this.data);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }

    @Override
    public boolean stillValid(Player player) {
        // Для dummy BE (клиент) level == null — пропускаем проверку расстояния.
        if (blockEntity.getLevel() == null) return true;
        return AbstractContainerMenu.stillValid(access, player, ModBlocks.COMMANDER.get());
    }

    public int getFilterMask()               { return filterMaskCache; }
    public CommanderBlockEntity getBlockEntity() { return blockEntity; }
}
