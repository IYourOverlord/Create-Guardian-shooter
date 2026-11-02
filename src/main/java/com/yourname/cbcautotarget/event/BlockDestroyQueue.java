package com.yourname.cbcautotarget.event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayDeque;
import java.util.Collection;

/**
 * Очередь удаления блоков (STRUCTURE/SYNAXIS DESTROY): за тик тратится не больше {@link #BUDGET_NS},
 * блоки заменяются на воздух без обновления соседей, дропа и звука/частиц (по пакету на блок).
 */
@EventBusSubscriber(modid = "cbc_autotarget", bus = EventBusSubscriber.Bus.GAME)
public final class BlockDestroyQueue {
    private static final long BUDGET_NS = 2_000_000L; // ponytail: ~2 мс/тик; при необходимости вынести в конфиг
    private static final ArrayDeque<Entry> QUEUE = new ArrayDeque<>();

    private record Entry(ServerLevel level, BlockPos pos) {}

    private BlockDestroyQueue() {}

    public static void add(ServerLevel level, Collection<BlockPos> positions) {
        for (BlockPos p : positions) QUEUE.add(new Entry(level, p));
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        long end = System.nanoTime() + BUDGET_NS;
        Entry e;
        while ((e = QUEUE.poll()) != null) {
            if (e.level.isLoaded(e.pos)) {
                e.level.removeBlockEntity(e.pos); // иначе onRemove контейнеров выбросит содержимое
                e.level.setBlock(e.pos, Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
            }
            if (System.nanoTime() > end) break;
        }
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) { QUEUE.clear(); }
}
