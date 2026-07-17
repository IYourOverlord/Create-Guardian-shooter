package com.yourname.cbcautotarget.menu;

import com.yourname.cbcautotarget.ModBlockEntities;
import com.yourname.cbcautotarget.ModBlocks;
import com.yourname.cbcautotarget.ModMenus;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.CommandRole;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.CommandSlot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Меню вкладки Move — 6 пар freq-слотов (Forward/Backward/Left/Right/Up/Down)
 * + инвентарь игрока.
 *
 * Геометрия (все Y относительно topPos):
 *   CONTENT_TOP=17, SAVE_BOTTOM=16, hint y=18 (1 строка, заканчивается на 27)
 *   FIRST_ROW_Y=28, ROW_H=18, SLOT_OFFSET=0
 *   FREQ_SLOT_X0=144, FREQ_SLOT_X1=164
 *   6 строк × 18 = 108px → last row bottom = 136
 *   INV_Y_BASE = 136 + 19 = 155
 */
public class MachineSoulMoveMenu extends AbstractContainerMenu {

    public static final CommandRole[] MOVE_ROLES = {
            CommandRole.MOVE_FORWARD, CommandRole.MOVE_BACKWARD,
            CommandRole.MOVE_LEFT,    CommandRole.MOVE_RIGHT,
            CommandRole.MOVE_UP,      CommandRole.MOVE_DOWN
    };

    public static final int FREQ_SLOTS  = MOVE_ROLES.length * 2; // 12
    public static final int FREQ_X0     = 144;
    public static final int FREQ_X1     = 164;
    // Hint-строка: y=18..27 (1 строка). Строки ролей начинаются с 28.
    public static final int FIRST_ROW_Y = 28;
    public static final int ROW_H       = 18;
    public static final int SLOT_OFFSET = 0;   // (18-18)/2
    public static final int INV_X       = 8;
    // last_row_bottom = 28 + 6*18 = 136; INV_Y_BASE = 136+6+9+4 = 155
    public static final int INV_Y_BASE  = 155;

    public final MachineSoulBlockEntity blockEntity;
    public final BlockPos blockPos;
    private final SimpleContainer freqContainer;

    // ── Клиентский конструктор ────────────────────────────────────────────────
    public MachineSoulMoveMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        this(id, inv, readDummy(buf));
    }

    private static MachineSoulBlockEntity readDummy(RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        MachineSoulBlockEntity dummy = new MachineSoulBlockEntity(
                ModBlockEntities.MACHINE_SOUL.get(), pos,
                ModBlocks.MACHINE_SOUL.get().defaultBlockState());
        for (CommandRole role : MOVE_ROLES) {
            ItemStack f0 = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            ItemStack f1 = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            dummy.assignSlot(role, f0, f1);
        }
        return dummy;
    }

    // ── Серверный конструктор ─────────────────────────────────────────────────
    public MachineSoulMoveMenu(int id, Inventory inv, MachineSoulBlockEntity be) {
        super(ModMenus.MACHINE_SOUL_MOVE.get(), id);
        this.blockEntity    = be;
        this.blockPos       = be.getBlockPos();
        this.freqContainer  = new SimpleContainer(FREQ_SLOTS);
        loadFromBE();
        addFreqSlots();
        addPlayerInventory(inv);
    }

    private void loadFromBE() {
        for (int i = 0; i < MOVE_ROLES.length; i++) {
            CommandSlot s = blockEntity.getSlot(MOVE_ROLES[i]);
            freqContainer.setItem(i * 2,     s.freq0.copy());
            freqContainer.setItem(i * 2 + 1, s.freq1.copy());
        }
    }

    /** Y слота для роли (относительно topPos). */
    public static int slotY(int roleIndex) {
        return FIRST_ROW_Y + roleIndex * ROW_H + SLOT_OFFSET;
    }

    /** Y строки (rowY) для roleIndex. */
    public static int rowY(int roleIndex) {
        return FIRST_ROW_Y + roleIndex * ROW_H;
    }

    private void addFreqSlots() {
        for (int i = 0; i < MOVE_ROLES.length; i++) {
            int y = slotY(i);
            addSlot(new GhostSlot(freqContainer, i * 2,     FREQ_X0, y));
            addSlot(new GhostSlot(freqContainer, i * 2 + 1, FREQ_X1, y));
        }
    }

    private void addPlayerInventory(Inventory inv) {
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                addSlot(new Slot(inv, col + row * 9 + 9, INV_X + col * 18, INV_Y_BASE + row * 18));
        for (int col = 0; col < 9; col++)
            addSlot(new Slot(inv, col, INV_X + col * 18, INV_Y_BASE + 58));
    }

    // ── Публичные методы ──────────────────────────────────────────────────────

    public ItemStack getFreqItem(int slotId) {
        return freqContainer.getItem(slotId);
    }

    public void setFreqItem(int slotId, ItemStack stack) {
        if (slotId < 0 || slotId >= FREQ_SLOTS) return;
        ItemStack g = stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
        if (!g.isEmpty()) g.setCount(1);
        freqContainer.setItem(slotId, g);
        slots.get(slotId).setChanged();
    }

    // ── Ghost перехват ────────────────────────────────────────────────────────

    @Override
    public void clicked(int slotId, int button, ClickType type, Player player) {
        if (slotId >= 0 && slotId < FREQ_SLOTS) {
            ItemStack carried = getCarried();
            if (carried.isEmpty() || type == ClickType.PICKUP && button == 1)
                slots.get(slotId).set(ItemStack.EMPTY);
            else
                slots.get(slotId).set(carried);
            return;
        }
        super.clicked(slotId, button, type, player);
    }

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        if (index < FREQ_SLOTS) return ItemStack.EMPTY;
        Slot s = slots.get(index);
        if (!s.hasItem()) return ItemStack.EMPTY;
        for (int i = 0; i < FREQ_SLOTS; i++) {
            if (!slots.get(i).hasItem()) { slots.get(i).set(s.getItem()); return ItemStack.EMPTY; }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (blockEntity.getLevel() != null && !blockEntity.getLevel().isClientSide())
            blockEntity.onMenuClosed(player);
    }

    @Override
    public boolean stillValid(Player p) {
        if (blockEntity.getLevel() == null) return true;
        return blockEntity.getLevel().getBlockState(blockPos).is(ModBlocks.MACHINE_SOUL.get())
                || com.yourname.cbcautotarget.compat.SableCompat.isAvailable();
    }

    // ── GhostSlot ─────────────────────────────────────────────────────────────

    private static class GhostSlot extends Slot {
        GhostSlot(SimpleContainer c, int index, int x, int y) { super(c, index, x, y); }
        @Override public boolean mayPickup(Player p) { return false; }
        @Override public boolean mayPlace(ItemStack s) { return true; }
        @Override public int getMaxStackSize() { return 1; }
        @Override public void set(ItemStack stack) {
            if (stack.isEmpty()) { super.set(ItemStack.EMPTY); return; }
            ItemStack g = stack.copy(); g.setCount(1); super.set(g);
        }
    }
}