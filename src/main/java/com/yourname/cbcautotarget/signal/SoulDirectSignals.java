package com.yourname.cbcautotarget.signal;

import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.CommandRole;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Реестр прямых редстоун-выходов Machine Soul на стороны произвольных блоков.
 * Читается из MixinBlockStateBase.getSignal: блок-потребитель видит 15 на своей грани,
 * пока хотя бы одна роль Soul активна и привязана к этой грани.
 */
public final class SoulDirectSignals {

    public static final int STRENGTH = 15;

    public record Face(long pos, int dir) {
        public static Face of(BlockPos pos, Direction dir) {
            return new Face(pos.asLong(), dir.get3DDataValue());
        }
    }

    private record Emitter(long soulPos, CommandRole role) { }

    private static final class LevelState {
        final Map<Emitter, List<Face>> emitters = new HashMap<>();
        final Map<Face, Integer> counts = new ConcurrentHashMap<>();
        final Map<Long, Set<Face>> bound = new ConcurrentHashMap<>();
    }

    private static final Map<Level, LevelState> STATES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final AtomicInteger ACTIVE_FACES = new AtomicInteger();

    private SoulDirectSignals() { }

    public static boolean isActive(Level level, BlockPos soulPos, CommandRole role) {
        LevelState state = STATES.get(level);
        return state != null && state.emitters.containsKey(new Emitter(soulPos.asLong(), role));
    }

    public static void activate(Level level, BlockPos soulPos, CommandRole role, List<Face> faces) {
        if (faces.isEmpty()) return;
        LevelState state = STATES.computeIfAbsent(level, l -> new LevelState());
        if (state.emitters.putIfAbsent(new Emitter(soulPos.asLong(), role), List.copyOf(faces)) != null) return;
        for (Face face : faces) state.counts.merge(face, 1, Integer::sum);
        ACTIVE_FACES.addAndGet(faces.size());
        for (Face face : faces) notifyFace(level, face);
    }

    public static void deactivate(Level level, BlockPos soulPos, CommandRole role) {
        LevelState state = STATES.get(level);
        if (state == null) return;
        List<Face> faces = state.emitters.remove(new Emitter(soulPos.asLong(), role));
        if (faces == null) return;
        for (Face face : faces) state.counts.computeIfPresent(face, (f, n) -> n > 1 ? n - 1 : null);
        ACTIVE_FACES.addAndGet(-faces.size());
        for (Face face : faces) notifyFace(level, face);
    }

    /** Полный список привязанных граней Soul (независимо от активности роли). */
    public static void setBindings(Level level, BlockPos soulPos, Set<Face> faces) {
        LevelState state = STATES.computeIfAbsent(level, l -> new LevelState());
        if (faces.isEmpty()) state.bound.remove(soulPos.asLong());
        else state.bound.put(soulPos.asLong(), Set.copyOf(faces));
    }

    public static void clearBindings(Level level, BlockPos soulPos) {
        LevelState state = STATES.get(level);
        if (state != null) state.bound.remove(soulPos.asLong());
    }

    /** Позиции всех Soul, у которых эта грань привязана хотя бы к одной роли. */
    public static List<Long> soulsBoundTo(Level level, Face face) {
        LevelState state = STATES.get(level);
        List<Long> result = new ArrayList<>();
        if (state == null) return result;
        state.bound.forEach((soul, faces) -> { if (faces.contains(face)) result.add(soul); });
        return result;
    }

    /** consumerPos — блок-потребитель, face — его грань, с которой запрашивается сигнал. */
    public static int getSignal(Level level, BlockPos consumerPos, Direction face) {
        if (ACTIVE_FACES.get() == 0) return 0;
        LevelState state = STATES.get(level);
        if (state == null) return 0;
        return state.counts.containsKey(Face.of(consumerPos, face)) ? STRENGTH : 0;
    }

    private static void notifyFace(Level level, Face face) {
        BlockPos updated = BlockPos.of(face.pos()).relative(Direction.from3DDataValue(face.dir()));
        if (!level.hasChunkAt(updated)) return;
        level.updateNeighborsAt(updated, level.getBlockState(updated).getBlock());
    }
}
