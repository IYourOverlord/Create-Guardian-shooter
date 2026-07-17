package com.yourname.cbcautotarget.blockentity;

import com.yourname.cbcautotarget.block.CartridgeCollectorBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

public class CartridgeCollectorBlockEntity extends BlockEntity {

    // Раз в 5 секунд = 100 тиков
    private static final int COLLECT_INTERVAL = 100;
    // Радиус сбора гильз
    private static final int COLLECT_RADIUS = 10;
    // Максимум стопок в хранилище (36 — как инвентарь игрока)
    private static final int INVENTORY_SIZE = 36;

    /**
     * ID предметов которые собираем.
     * Используем строки чтобы не создавать жёсткую зависимость на класс CBC в compile-time.
     */
    private static final String[] TARGET_ITEM_IDS = {
            "createbigcannons:big_cartridge",
            "createbigcannons:empty_autocannon_cartridge"
    };

    private final List<ItemStack> inventory = new ArrayList<>();
    private int collectTimer = 0;

    public CartridgeCollectorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  CartridgeCollectorBlockEntity be) {
        if (++be.collectTimer < COLLECT_INTERVAL) return;
        be.collectTimer = 0;
        be.collectNearbyCartridges(level, pos, state);
    }

    private void collectNearbyCartridges(Level level, BlockPos pos, BlockState state) {
        AABB searchBox = new AABB(pos).inflate(COLLECT_RADIUS);

        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, searchBox,
                e -> e.isAlive() && isTargetItem(e.getItem()));

        if (items.isEmpty()) return;

        boolean changed = false;
        for (ItemEntity itemEntity : items) {
            if (!itemEntity.isAlive()) continue; // мог быть убит предыдущей итерацией
            ItemStack stack = itemEntity.getItem(); // живая ссылка
            int before = stack.getCount();
            ItemStack remaining = addToInventory(stack.copy());
            int absorbed = before - remaining.getCount();
            if (absorbed <= 0) continue; // инвентарь полон, ничего не взяли

            changed = true;
            if (remaining.isEmpty()) {
                // Забрали всё — убираем entity из мира
                itemEntity.discard();
            } else {
                // Забрали частично — уменьшаем стак в entity
                itemEntity.setItem(remaining);
            }
        }

        if (changed) {
            setChanged();
            // Обновляем BlockState (FULL = есть хотя бы один предмет).
            // Флаги: 1=соседи, 2=клиентский пакет, 32=обновить рендер блока на клиенте.
            boolean nowFull = !inventory.isEmpty();
            boolean wasFull = state.getValue(CartridgeCollectorBlock.FULL);
            if (nowFull != wasFull) {
                level.setBlock(pos, state.setValue(CartridgeCollectorBlock.FULL, nowFull), 1 | 2 | 32);
            }
        }
    }

    /**
     * Добавляет стек в инвентарь, объединяя со стопками того же предмета.
     * Возвращает остаток (что не влезло).
     */
    private ItemStack addToInventory(ItemStack incoming) {
        // Сначала пробуем добавить к существующим стопкам того же типа
        for (ItemStack stored : inventory) {
            if (stored.getCount() >= stored.getMaxStackSize()) continue;
            if (!ItemStack.isSameItem(stored, incoming)) continue;

            int canAdd = stored.getMaxStackSize() - stored.getCount();
            int toAdd  = Math.min(canAdd, incoming.getCount());
            stored.grow(toAdd);
            incoming.shrink(toAdd);
            if (incoming.isEmpty()) return ItemStack.EMPTY;
        }
        // Затем создаём новые слоты
        while (!incoming.isEmpty() && inventory.size() < INVENTORY_SIZE) {
            int toAdd = Math.min(incoming.getMaxStackSize(), incoming.getCount());
            ItemStack newStack = incoming.copyWithCount(toAdd);
            inventory.add(newStack);
            incoming.shrink(toAdd);
        }
        return incoming.isEmpty() ? ItemStack.EMPTY : incoming;
    }

      private static boolean isTargetItem(ItemStack stack) {
        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem());
        if (id == null) return false;
        String idStr = id.toString();
        for (String target : TARGET_ITEM_IDS) {
            if (target.equals(idStr)) return true;
        }
        return false;
    }

    // ── Взаимодействие ───────────────────────────────────────────────────────

    /**
     * ПКМ игрока — выбросить всё содержимое прямо к нему в руки (в инвентарь).
     * Что не влезло — падает на пол.
     */
    public void dumpContents(Player player) {
        if (inventory.isEmpty()) {
            player.displayClientMessage(
                    Component.translatable("block.cbc_autotarget.cartridge_collector.empty"), true);
            return;
        }

        List<ItemStack> remaining = new ArrayList<>();
        for (ItemStack stack : inventory) {
            if (!player.getInventory().add(stack)) {
                remaining.add(stack);
            }
        }
        inventory.clear();
        inventory.addAll(remaining);

        setChanged();
        if (level != null) {
            boolean nowFull = !inventory.isEmpty();
            BlockState state = getBlockState();
            if (state.getValue(CartridgeCollectorBlock.FULL) != nowFull) {
                level.setBlock(worldPosition, state.setValue(CartridgeCollectorBlock.FULL, nowFull), 1 | 2 | 32);
            }
        }

        int count = inventory.stream().mapToInt(ItemStack::getCount).sum();
        if (count > 0) {
            player.displayClientMessage(
                    Component.translatable("block.cbc_autotarget.cartridge_collector.partial", count), true);
        }
    }

    /**
     * Вызывается при разрушении блока — всё выпадает на землю.
     */
    public void dropAllItems(Level level, BlockPos pos) {
        for (ItemStack stack : inventory) {
            double x = pos.getX() + 0.5 + (level.random.nextDouble() - 0.5) * 0.4;
            double y = pos.getY() + 0.5;
            double z = pos.getZ() + 0.5 + (level.random.nextDouble() - 0.5) * 0.4;
            ItemEntity ie = new ItemEntity(level, x, y, z, stack.copy());
            ie.setDefaultPickUpDelay();
            level.addFreshEntity(ie);
        }
        inventory.clear();
    }

    // ── Геттеры ───────────────────────────────────────────────────────────────

    public List<ItemStack> getInventory() { return inventory; }

    public int getTotalCount() {
        return inventory.stream().mapToInt(ItemStack::getCount).sum();
    }

    // ── NBT ──────────────────────────────────────────────────────────────────

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        net.minecraft.nbt.ListTag listTag = new net.minecraft.nbt.ListTag();
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty()) {
                listTag.add(stack.save(registries));
            }
        }
        tag.put("Inventory", listTag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        inventory.clear();
        if (tag.contains("Inventory", net.minecraft.nbt.Tag.TAG_LIST)) {
            net.minecraft.nbt.ListTag listTag = tag.getList("Inventory",
                    net.minecraft.nbt.Tag.TAG_COMPOUND);
            for (int i = 0; i < listTag.size(); i++) {
                ItemStack.parse(registries, listTag.getCompound(i))
                        .ifPresent(inventory::add);
            }
        }
    }
}
