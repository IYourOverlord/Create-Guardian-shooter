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
 * Меню вкладки Action — 1 пара freq-слотов (Fire) + инвентарь игрока.
 *
 * Геометрия:
 *   SAVE_BOTTOM=16. Hint: y=18, под текст зарезервировано до 5 строк
 *   (HINT_RESERVED_H=52, см. MachineSoulActionScreen) — рассчитано по самому
 *   длинному переводу хинта среди всех языков локализации, заканчивается на 70.
 *   FIRST_ROW_Y=70, ROW_H=18, SLOT_OFFSET=0
 *   FREQ_X0=144, FREQ_X1=164
 *   INV_Y_BASE=92
 */
public class MachineSoulActionMenu extends AbstractContainerMenu {

    public static final int FREQ_SLOTS  = 2;
    public static final int FREQ_X0     = 218;
    public static final int FREQ_X1     = 240;
    // Hint: y=18, до 5 строк (52px) с запасом под самый длинный перевод среди
    // всех языков локализации → заканчивается на 70. FIRE строка начинается с 70.
    public static final int FIRST_ROW_Y = 70;
    public static final int ROW_H       = 18;
    public static final int SLOT_OFFSET = 0;
    public static final int INV_X       = 8;
    // FIRE_ROW_BOTTOM=88; INV_Y_BASE=88+4=92
    public static final int INV_Y_BASE  = 155;

    // FIRE — единственная строка на этой вкладке
    public static final int FIRE_ROW_Y  = 78;
    public static final int FIRE_SLOT_Y = FIRE_ROW_Y + SLOT_OFFSET;

    public final MachineSoulBlockEntity blockEntity;
    public final BlockPos blockPos;
    private final SimpleContainer freqContainer;

    // ── Клиентский конструктор ────────────────────────────────────────────────
    public MachineSoulActionMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        this(id, inv, readDummy(buf));
    }

    private static MachineSoulBlockEntity readDummy(RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        MachineSoulBlockEntity dummy = new MachineSoulBlockEntity(
                ModBlockEntities.MACHINE_SOUL.get(), pos,
                ModBlocks.MACHINE_SOUL.get().defaultBlockState());
        ItemStack f0 = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        ItemStack f1 = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        dummy.assignSlot(CommandRole.FIRE, f0, f1);
        return dummy;
    }

    // ── Серверный конструктор ─────────────────────────────────────────────────
    public MachineSoulActionMenu(int id, Inventory inv, MachineSoulBlockEntity be) {
        super(ModMenus.MACHINE_SOUL_ACTION.get(), id);
        this.blockEntity   = be;
        this.blockPos      = be.getBlockPos();
        this.freqContainer = new SimpleContainer(FREQ_SLOTS);
        loadFromBE();
        addFreqSlots();
        addPlayerInventory(inv);
    }

    private void loadFromBE() {
        CommandSlot s = blockEntity.getSlot(CommandRole.FIRE);
        freqContainer.setItem(0, s.freq0.copy());
        freqContainer.setItem(1, s.freq1.copy());
    }

    private void addFreqSlots() {
        addSlot(new GhostSlot(freqContainer, 0, FREQ_X0, FIRE_SLOT_Y));
        addSlot(new GhostSlot(freqContainer, 1, FREQ_X1, FIRE_SLOT_Y));
    }

    private void addPlayerInventory(Inventory inv) {
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                addSlot(new Slot(inv, col + row * 9 + 9, INV_X + col * 18, INV_Y_BASE + row * 18));
        for (int col = 0; col < 9; col++)
            addSlot(new Slot(inv, col, INV_X + col * 18, INV_Y_BASE + 58));
    }

    public ItemStack getFreqItem(int slotId) { return freqContainer.getItem(slotId); }

    public void setFreqItem(int slotId, ItemStack stack) {
        if (slotId < 0 || slotId >= FREQ_SLOTS) return;
        ItemStack g = stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
        if (!g.isEmpty()) g.setCount(1);
        freqContainer.setItem(slotId, g);
        slots.get(slotId).setChanged();
    }

    @Override
    public void clicked(int slotId, int button, ClickType type, Player player) {
        if (slotId >= 0 && slotId < FREQ_SLOTS) {
            ItemStack carried = getCarried();
            if (carried.isEmpty() || type == ClickType.PICKUP && button == 1)
                setFreqItem(slotId, ItemStack.EMPTY);
            else
                setFreqItem(slotId, carried);
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
            if (!slots.get(i).hasItem()) { setFreqItem(i, s.getItem()); return ItemStack.EMPTY; }
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