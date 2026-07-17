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
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class MachineSoulMenu extends AbstractContainerMenu {

    public static final int FREQ_SLOTS = CommandRole.values().length * 2;

    public final MachineSoulBlockEntity blockEntity;
    private final ContainerLevelAccess access;
    private final SimpleContainer freqContainer;

    public final BlockPos blockPos;

    /** Радиус обнаружения — хранится в меню для передачи клиенту и обратно. */
    private int detectionRadius;

    // ── Размеры GUI (должны совпадать с MachineSoulScreen) ────────────────────
    // GUI_H = 234, TAB_H = 18, CONTENT_TOP = 19, PAD = 8, ROW_H = 18
    // SLOT_SIZE = 18

    // ── Координаты freq-слотов (X) ────────────────────────────────────────────
    // Слоты расположены в правой части контентной области.
    // FREQ_SLOT_X0 и X1 — относительно leftPos.
    public static final int FREQ_SLOT_X0 = 144;   // первый слот
    public static final int FREQ_SLOT_X1 = 164;   // второй слот (+20 = SLOT_SIZE+2)

    // ── Y-координаты freq-слотов ──────────────────────────────────────────────
    // TAB_H(18) + separator(1) = CONTENT_TOP(19)
    // Подсказка занимает HEADER_H(10) пикселей.
    // Строки начинаются с FIRST_ROW_Y = 29.
    // Каждая строка ROW_H = 18 пикселей.
    // Слот внутри строки: rowY + SLOT_OFFSET_Y(0) = rowY + (18-18)/2

    public static final int CONTENT_TOP   = 19;   // TAB_H(18) + separator(1)
    public static final int HEADER_H      = 10;   // высота hint над списком
    public static final int FIRST_ROW_Y   = CONTENT_TOP + HEADER_H; // 29

    public static final int ROW_H         = 18;
    public static final int SLOT_OFFSET_Y = 0;    // (ROW_H - SLOT_SIZE) / 2 = (18-18)/2

    /**
     * Возвращает Y начала строки (rowY) для данной роли — относительно topPos.
     * Screen рисует фон строки от rowY до rowY+ROW_H.
     * Слот находится на rowY + SLOT_OFFSET_Y.
     */
    public static int getRowY(CommandRole role) {
        return switch (role.tab()) {
            // Movement: 6 ролей подряд → ordinal 0..5
            case MOVEMENT -> FIRST_ROW_Y + role.ordinal() * ROW_H;
            // FIRE — единственная роль Action-вкладки, первая строка
            case ACTION   -> FIRST_ROW_Y;
            case VISION   -> 0; // нет слотов
            case TARGET   -> 0; // нет слотов (Target — только переключатель)
        };
    }

    /**
     * Возвращает Y слота — относительно topPos.
     * Это значение передаётся в GhostSlot и используется движком для рендера предмета.
     */
    public static int getSlotScreenY(CommandRole role) {
        return getRowY(role) + SLOT_OFFSET_Y;
    }

    // ── Инвентарь игрока ──────────────────────────────────────────────────────
    // Movement last row bottom = FIRST_ROW_Y(29) + 6*ROW_H(18) = 137
    // INV_Y_BASE = 137 + gap(4) + fontH(9) + gap(4) = 154
    // Hotbar bottom = 154 + 58 + 18 = 230; GUI_H = 230 + 4 = 234
    public static final int INV_X      = 8;
    public static final int INV_Y_BASE = 154;

    // ── Клиентский конструктор ────────────────────────────────────────────────

    public MachineSoulMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buf) {
        this(containerId, playerInventory, createClientDummy(buf, playerInventory));
    }

    private static MachineSoulBlockEntity createClientDummy(RegistryFriendlyByteBuf buf,
                                                            Inventory inv) {
        BlockPos pos = buf.readBlockPos();
        int radius   = buf.readInt();
        MachineSoulBlockEntity dummy = new MachineSoulBlockEntity(
                ModBlockEntities.MACHINE_SOUL.get(),
                pos,
                ModBlocks.MACHINE_SOUL.get().defaultBlockState()
        );
        dummy.setDetectionRadius(radius);
        for (CommandRole role : CommandRole.values()) {
            ItemStack freq0 = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            ItemStack freq1 = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            dummy.assignSlot(role, freq0, freq1);
        }
        return dummy;
    }

    // ── Серверный конструктор ─────────────────────────────────────────────────

    public MachineSoulMenu(int containerId, Inventory playerInventory,
                           MachineSoulBlockEntity blockEntity) {
        super(ModMenus.MACHINE_SOUL_MENU.get(), containerId);
        this.blockEntity     = blockEntity;
        this.blockPos        = blockEntity.getBlockPos();
        this.detectionRadius = blockEntity.getDetectionRadius();
        this.access          = ContainerLevelAccess.create(
                blockEntity.getLevel(), blockEntity.getBlockPos());

        this.freqContainer = new SimpleContainer(FREQ_SLOTS);
        loadFreqFromBE();

        addFreqSlots();
        addPlayerInventory(playerInventory);
    }

    // ── Ghost Slot ────────────────────────────────────────────────────────────

    private static class GhostSlot extends Slot {
        public GhostSlot(SimpleContainer container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override public boolean mayPickup(Player player) { return false; }
        @Override public boolean mayPlace(ItemStack stack) { return true; }
        @Override public int getMaxStackSize() { return 1; }

        @Override
        public void set(ItemStack stack) {
            if (stack.isEmpty()) {
                super.set(ItemStack.EMPTY);
            } else {
                ItemStack ghost = stack.copy();
                ghost.setCount(1);
                super.set(ghost);
            }
        }
    }

    // ── Перехват кликов по Ghost-слотам ──────────────────────────────────────

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (slotId >= 0 && slotId < FREQ_SLOTS) {
            Slot slot = slots.get(slotId);
            ItemStack carried = getCarried();
            if (carried.isEmpty() || clickType == ClickType.PICKUP && button == 1) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.set(carried);
            }
            return;
        }
        super.clicked(slotId, button, clickType, player);
    }

    // ── Инициализация слотов ──────────────────────────────────────────────────

    private void addFreqSlots() {
        CommandRole[] roles = CommandRole.values();
        for (CommandRole role : roles) {
            int y = getSlotScreenY(role);
            int roleIdx = role.ordinal();
            addSlot(new GhostSlot(freqContainer, roleIdx * 2,     FREQ_SLOT_X0, y));
            addSlot(new GhostSlot(freqContainer, roleIdx * 2 + 1, FREQ_SLOT_X1, y));
        }
    }

    private void addPlayerInventory(Inventory inv) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inv, col + row * 9 + 9,
                        INV_X + col * 18, INV_Y_BASE + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inv, col, INV_X + col * 18, INV_Y_BASE + 58));
        }
    }

    // ── Загрузка данных из BE ─────────────────────────────────────────────────

    private void loadFreqFromBE() {
        CommandRole[] roles = CommandRole.values();
        for (int i = 0; i < roles.length; i++) {
            CommandSlot slot = blockEntity.getSlot(roles[i]);
            freqContainer.setItem(i * 2,     slot.freq0.copy());
            freqContainer.setItem(i * 2 + 1, slot.freq1.copy());
        }
    }

    // ── Публичные геттеры ─────────────────────────────────────────────────────

    public MachineSoulBlockEntity getBlockEntity() { return blockEntity; }
    public CommandSlot getSlot(CommandRole role)   { return blockEntity.getSlot(role); }
    public int getDetectionRadius()                { return detectionRadius; }
    public void setDetectionRadius(int r)          { this.detectionRadius = r; }

    public ItemStack getBufferItem(CommandRole role, int freqIdx) {
        return freqContainer.getItem(role.ordinal() * 2 + freqIdx);
    }

    public void setBufferItem(int slotId, ItemStack stack) {
        if (slotId < 0 || slotId >= FREQ_SLOTS) return;
        ItemStack ghost = stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
        if (!ghost.isEmpty()) ghost.setCount(1);
        freqContainer.setItem(slotId, ghost);
        slots.get(slotId).setChanged();
    }

    public static int freqSlotIndex(CommandRole role, int freqIdx) {
        return role.ordinal() * 2 + freqIdx;
    }

    // ── Закрытие / shift-клик ─────────────────────────────────────────────────

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (blockEntity.getLevel() != null && !blockEntity.getLevel().isClientSide()) {
            blockEntity.onMenuClosed(player);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < FREQ_SLOTS) return ItemStack.EMPTY;
        Slot invSlot = slots.get(index);
        if (!invSlot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = invSlot.getItem();
        for (int i = 0; i < FREQ_SLOTS; i++) {
            Slot ghost = slots.get(i);
            if (!ghost.hasItem()) {
                ghost.set(stack);
                return ItemStack.EMPTY;
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        if (blockEntity.getLevel() == null) return true;
        return blockEntity.getLevel().getBlockState(blockEntity.getBlockPos())
                .is(ModBlocks.MACHINE_SOUL.get())
                || com.yourname.cbcautotarget.compat.SableCompat.isAvailable();
    }
}