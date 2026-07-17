package com.yourname.cbcautotarget.menu;

import com.yourname.cbcautotarget.ModBlocks;
import com.yourname.cbcautotarget.ModMenus;
import com.yourname.cbcautotarget.blockentity.ControllerBlockEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.SlotItemHandler;

public class ControllerMenu extends AbstractContainerMenu {

    /**
     * Высота панели над стандартным окном инвентаря, где размещаются кнопки
     * H/V и Fire Trigger. Все слоты сдвинуты вниз на эту величину, чтобы
     * кнопки не перекрывали инвентарь и не были встроены в строку заголовка.
     */
    public static final int TOP_PANEL_HEIGHT = 20;

    private final ControllerBlockEntity blockEntity;
    private final ContainerLevelAccess access;

    public ControllerMenu(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
        this(containerId, playerInventory, getBlockEntity(playerInventory, buf));
    }

    private static ControllerBlockEntity getBlockEntity(Inventory inv, FriendlyByteBuf buf) {
        var pos = buf.readBlockPos();
        var be  = inv.player.level().getBlockEntity(pos);
        if (be instanceof ControllerBlockEntity c) return c;
        throw new IllegalStateException("No ControllerBlockEntity at " + pos);
    }

    public ControllerMenu(int containerId, Inventory playerInventory, ControllerBlockEntity blockEntity) {
        super(ModMenus.CONTROLLER_MENU.get(), containerId);
        this.blockEntity = blockEntity;
        this.access = ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos());

        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                addSlot(new SlotItemHandler(blockEntity.getInventory(), row * 9 + col,
                        8 + col * 18, TOP_PANEL_HEIGHT + 18 + row * 18));

        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                addSlot(new Slot(playerInventory, col + row * 9 + 9,
                        8 + col * 18, TOP_PANEL_HEIGHT + 84 + row * 18));

        for (int col = 0; col < 9; col++)
            addSlot(new Slot(playerInventory, col, 8 + col * 18, TOP_PANEL_HEIGHT + 142));
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            if (index < 27) {
                if (!moveItemStackTo(stack, 27, 63, true)) return ItemStack.EMPTY;
            } else {
                if (!moveItemStackTo(stack, 0, 27, false)) return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
            else slot.setChanged();
            if (stack.getCount() == result.getCount()) return ItemStack.EMPTY;
            slot.onTake(player, stack);
        }
        return result;
    }

    @Override
    public boolean stillValid(Player player) {
        // ContainerLevelAccess работает через evaluate — проверяем тир блока и дистанцию
        return access.evaluate((level, pos) ->
                level.getBlockState(pos).getBlock() instanceof com.yourname.cbcautotarget.block.ControllerBlock
                && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) < 64.0,
                true);
    }

    public ControllerBlockEntity getBlockEntity() { return blockEntity; }
}
