package com.yourname.cbcautotarget.compat;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SableCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("cbc_autotarget/SableCompat");

    private SableCompat() {}

    public static boolean isAvailable() {
        try {
            Class.forName("dev.ryanhcode.sable.sublevel.plot.LevelPlot");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Nullable
    public static ServerSubLevel getSubLevelForBlock(ServerLevel level, BlockPos pos) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            LOGGER.debug("[getSubLevelForBlock] No SubLevelContainer for level {}", level.dimension().location());
            return null;
        }

        ChunkPos cp = new ChunkPos(pos);
        int checked = 0;
        for (ServerSubLevel ssl : container.getAllSubLevels()) {
            checked++;
            if (ssl.isRemoved()) continue;
            LevelPlot plot = ssl.getPlot();
            if (plot != null && plot.contains(cp)) {
                LOGGER.debug("[getSubLevelForBlock] Found SubLevel for pos={}", pos);
                return ssl;
            }
        }
        LOGGER.debug("[getSubLevelForBlock] No SubLevel found for pos={} (checked {})", pos, checked);
        return null;
    }

    /**
     * Разрушает (без дропа) все блоки мода {@code namespace} на конструкции, которой принадлежит {@code soulPos}:
     * в её SubLevel и во всех SubLevel'ах, связанных с ним цепочкой блоков этого мода (моторы/суставы создают
     * собственные SubLevel'ы). Связь: NBT блока мода содержит UUID другого SubLevel'а (ожидаемый companion) —
     * Sable не хранит доступный реестр связей, поэтому граф строится по этим UUID и обходится целиком ДО разрушения.
     * Блок вне SubLevel — ничего не делает. ponytail: обходит только загруженные чанки плотов; для огромных
     * конструкций — растянуть по тикам.
     */
    public static int destroyBlocksOfNamespace(ServerLevel level, BlockPos soulPos, String namespace) {
        java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> ns = st ->
                namespace.equals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getNamespace());
        return destroyLinked(level, soulPos, ns, ns);
    }

    /** Разрушает всю конструкцию Soul (все связанные SubLevel'ы), кроме блоков из тега {@code structure_destroy_whitelist}. */
    public static int destroyStructure(ServerLevel level, BlockPos soulPos) {
        return destroyLinked(level, soulPos,
                st -> !st.isAir() && !st.is(com.yourname.cbcautotarget.CBCAutoTargetTags.STRUCTURE_DESTROY_WHITELIST),
                st -> "synaxis".equals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getNamespace()));
    }

    /** {@code destroy} — что разрушать; {@code link} — блоки, чей NBT определяет связи между SubLevel'ами. */
    private static int destroyLinked(ServerLevel level, BlockPos soulPos,
            java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> destroy,
            java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> link) {
        ServerSubLevel start = getSubLevelForBlock(level, soulPos);
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (start == null || container == null) return 0;
        java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> match = destroy.or(link);

        java.util.Map<java.util.UUID, java.util.List<BlockPos>> found = new java.util.HashMap<>();
        java.util.Map<java.util.UUID, java.util.Set<java.util.UUID>> refs = new java.util.HashMap<>();
        for (ServerSubLevel ssl : container.getAllSubLevels()) {
            if (ssl.isRemoved()) continue;
            java.util.List<BlockPos> list = new java.util.ArrayList<>();
            java.util.Set<java.util.UUID> ids = new java.util.HashSet<>();
            for (PlotChunkHolder holder : ssl.getPlot().getLoadedChunks()) {
                net.minecraft.world.level.chunk.LevelChunk chunk = holder.getChunk();
                net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    if (sections[i].hasOnlyAir() || !sections[i].maybeHas(match)) continue;
                    int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
                    for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                        if (!match.test(sections[i].getBlockState(x, y, z))) continue;
                        BlockPos p = new BlockPos(chunk.getPos().getMinBlockX() + x, baseY + y, chunk.getPos().getMinBlockZ() + z);
                        net.minecraft.world.level.block.state.BlockState st = sections[i].getBlockState(x, y, z);
                        if (destroy.test(st)) list.add(p);
                        if (!link.test(st)) continue;
                        BlockEntity be = chunk.getBlockEntity(p);
                        if (be != null) collectUuids(be.saveWithFullMetadata(level.registryAccess()), ids);
                    }
                }
            }
            found.put(ssl.getUniqueId(), list);
            refs.put(ssl.getUniqueId(), ids);
        }

        // неориентированный граф связей между SubLevel'ами
        java.util.Map<java.util.UUID, java.util.Set<java.util.UUID>> adj = new java.util.HashMap<>();
        refs.forEach((id, set) -> {
            for (java.util.UUID other : set) {
                if (other.equals(id) || !found.containsKey(other)) continue;
                adj.computeIfAbsent(id, k -> new java.util.HashSet<>()).add(other);
                adj.computeIfAbsent(other, k -> new java.util.HashSet<>()).add(id);
            }
        });
        java.util.Set<java.util.UUID> reached = new java.util.HashSet<>();
        java.util.ArrayDeque<java.util.UUID> queue = new java.util.ArrayDeque<>();
        queue.add(start.getUniqueId());
        while (!queue.isEmpty()) {
            java.util.UUID id = queue.poll();
            if (reached.add(id)) queue.addAll(adj.getOrDefault(id, java.util.Set.of()));
        }

        int count = 0;
        for (java.util.UUID id : reached) {
            java.util.List<BlockPos> list = found.getOrDefault(id, java.util.List.of());
            com.yourname.cbcautotarget.event.BlockDestroyQueue.add(level, list); // удаление размазано по тикам
            count += list.size();
        }
        LOGGER.info("[destroyLinked] {} blocks queued for destruction in {} linked SubLevel(s)", count, reached.size());
        return count;
    }

    /** Собирает все UUID из тега: int[4] (ванильный формат) и строки вида UUID. */
    private static void collectUuids(net.minecraft.nbt.Tag tag, java.util.Set<java.util.UUID> out) {
        if (tag instanceof net.minecraft.nbt.CompoundTag c) {
            for (String k : c.getAllKeys()) collectUuids(c.get(k), out);
        } else if (tag instanceof net.minecraft.nbt.ListTag l) {
            for (net.minecraft.nbt.Tag t : l) collectUuids(t, out);
        } else if (tag instanceof net.minecraft.nbt.IntArrayTag ia && ia.getAsIntArray().length == 4) {
            out.add(net.minecraft.core.UUIDUtil.uuidFromIntArray(ia.getAsIntArray()));
        } else if (tag instanceof net.minecraft.nbt.StringTag st && st.getAsString().length() == 36) {
            try { out.add(java.util.UUID.fromString(st.getAsString())); } catch (IllegalArgumentException ignored) { /* не UUID */ }
        }
    }

    @Nullable
    public static BlockEntity findBlockEntityInSubLevels(ServerLevel mainLevel, BlockPos localPos) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(mainLevel);
        if (container == null) {
            LOGGER.warn("[findBE] No SubLevelContainer in level {}", mainLevel.dimension().location());
            return null;
        }

        int checked = 0;
        for (ServerSubLevel ssl : container.getAllSubLevels()) {
            checked++;
            if (ssl.isRemoved()) continue;
            try {
                BlockEntity be = ssl.getPlot().getEmbeddedLevelAccessor().getBlockEntity(localPos);
                if (be != null) {
                    LOGGER.info("[findBE] Found {} at localPos={} in SubLevel #{}", be.getClass().getSimpleName(), localPos, checked);
                    return be;
                }
            } catch (Exception e) {
                LOGGER.warn("[findBE] Exception in SubLevel #{}: {}", checked, e.getMessage());
            }
        }
        LOGGER.warn("[findBE] Not found for localPos={} (checked {} SubLevels)", localPos, checked);
        return null;
    }

    public static Vec3 toWorldVelocity(ServerSubLevel subLevel, Vec3 localVel) {
        org.joml.Vector3d v = new org.joml.Vector3d(localVel.x, localVel.y, localVel.z);
        subLevel.logicalPose().transformNormal(v);
        return new Vec3(v.x, v.y, v.z);
    }

    public static Vec3 toWorldPos(ServerSubLevel subLevel, Vec3 localPos) {
        return subLevel.logicalPose().transformPosition(localPos);
    }

    public static Vec3 getShipVelocity(ServerSubLevel subLevel) {
        var vel = subLevel.latestLinearVelocity;
        return new Vec3(vel.x / 20.0, vel.y / 20.0, vel.z / 20.0);
    }

    private static Vec3 worldToLocal(ServerSubLevel ssl, Vec3 world) {
        var pose = ssl.logicalPose();
        Vec3 translation = pose.transformPosition(Vec3.ZERO);
        double dx = world.x - translation.x;
        double dy = world.y - translation.y;
        double dz = world.z - translation.z;
        org.joml.Vector3d colX = new org.joml.Vector3d(1, 0, 0);
        org.joml.Vector3d colY = new org.joml.Vector3d(0, 1, 0);
        org.joml.Vector3d colZ = new org.joml.Vector3d(0, 0, 1);
        pose.transformNormal(colX);
        pose.transformNormal(colY);
        pose.transformNormal(colZ);
        return new Vec3(
                colX.x * dx + colX.y * dy + colX.z * dz,
                colY.x * dx + colY.y * dy + colY.z * dz,
                colZ.x * dx + colZ.y * dy + colZ.z * dz);
    }

    public static java.util.List<SubLevelCommanderEntry> findCommandersInAllSubLevels(
            ServerLevel mainLevel, Vec3 worldCenter, int radius) {
        java.util.List<SubLevelCommanderEntry> result = new java.util.ArrayList<>();
        ServerSubLevelContainer container = SubLevelContainer.getContainer(mainLevel);
        if (container == null) return result;
        double radiusSq = (double) radius * radius;
        for (ServerSubLevel ssl : container.getAllSubLevels()) {
            if (ssl.isRemoved()) continue;
            try {
                // ИСПРАВЛЕНО: раньше здесь вызывался общий
                // CommanderBlockEntity.findCommandersInRadius(subWorld, ...) —
                // но этот метод при searchLevel=subWorld (конкретный корабль,
                // не главный мир) в СВОЕЙ ветке "1. Командеры в главном уровне"
                // трактует subWorld КАК ЕСЛИ БЫ он был главным миром: берёт
                // cmd.getBlockPos() как уже мировые координаты и сравнивает
                // их напрямую с worldOrigin/worldCenter — тогда как это на
                // самом деле ЛОКАЛЬНЫЕ координаты внутри subWorld, требующие
                // конвертации через toWorldPos(ssl, ...). Из-за этого
                // командеры на этом корабле либо не находились (дистанция
                // получалась огромной), либо находились только случайно,
                // если корабль стоял близко к origin мира. Теперь запрашиваем
                // командеров этого SubLevel'а НАПРЯМУЮ через
                // getCommandersInDimension и конвертируем сами — без
                // прохождения через findCommandersInRadius, который
                // спроектирован для searchLevel=главный мир, а не для
                // конкретного корабля.
                Level subWorld = ssl.getLevel();
                for (com.yourname.cbcautotarget.blockentity.CommanderBlockEntity cmd :
                        com.yourname.cbcautotarget.blockentity.CommanderBlockEntity
                                .getCommandersInDimension(subWorld.dimension())) {
                    if (cmd.isRemoved()) continue;
                    Vec3 worldPos = toWorldPos(ssl, Vec3.atCenterOf(cmd.getBlockPos()));
                    if (worldPos.distanceToSqr(worldCenter) <= radiusSq) {
                        result.add(new SubLevelCommanderEntry(ssl, cmd, cmd.getBlockPos(), worldPos));
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("[findCommandersInAllSubLevels] Exception in SubLevel: {}", e.getMessage());
            }
        }
        return result;
    }

    /**
     * Ищет все живые сущности указанного типа во всех sublevel'ах, находящихся в радиусе
     * от worldCenter. Аналог findCommandersInAllSubLevels для entity.
     * Используется контроллером для обнаружения целей на чужих sublevel-кораблях
     * (блоки корабля не являются частью mainLevel, поэтому стандартный AABB-скан их не находит).
     */
    public static <T extends LivingEntity> java.util.List<SubLevelEntityEntry<T>>
            findLivingEntitiesInAllSubLevels(ServerLevel mainLevel, Vec3 worldCenter, int radius,
                                              Class<T> entityClass,
                                              java.util.function.Predicate<T> filter) {
        java.util.List<SubLevelEntityEntry<T>> result = new java.util.ArrayList<>();
        ServerSubLevelContainer container = SubLevelContainer.getContainer(mainLevel);
        if (container == null) return result;
        double radiusSq = (double) radius * radius;
        AABB box = new AABB(
                worldCenter.x - radius, worldCenter.y - radius, worldCenter.z - radius,
                worldCenter.x + radius, worldCenter.y + radius, worldCenter.z + radius);
        for (ServerSubLevel ssl : container.getAllSubLevels()) {
            if (ssl.isRemoved()) continue;
            try {
                Level subWorld = ssl.getLevel();
                // Для AABB-скана нужен локальный бокс: преобразуем worldCenter в локальные координаты
                Vec3 localCenter = worldToLocal(ssl, worldCenter);
                AABB localBox = new AABB(
                        localCenter.x - radius, localCenter.y - radius, localCenter.z - radius,
                        localCenter.x + radius, localCenter.y + radius, localCenter.z + radius);
                for (T entity : subWorld.getEntitiesOfClass(entityClass, localBox, e -> e.isAlive() && filter.test(e))) {
                    // Переводим локальную позицию entity в мировые координаты для проверки дистанции
                    Vec3 worldPos = toWorldPos(ssl, entity.position());
                    if (worldPos.distanceToSqr(worldCenter) <= radiusSq) {
                        result.add(new SubLevelEntityEntry<>(ssl, entity, worldPos));
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("[findLivingEntitiesInAllSubLevels] Exception in SubLevel: {}", e.getMessage());
            }
        }
        return result;
    }

    public record SubLevelEntityEntry<T extends net.minecraft.world.entity.Entity>(
            ServerSubLevel subLevel,
            T entity,
            Vec3 worldPos) {}

    public record SubLevelCommanderEntry(
            ServerSubLevel subLevel,
            com.yourname.cbcautotarget.blockentity.CommanderBlockEntity commander,
            BlockPos localPos,
            Vec3 worldPos) {}

    /**
     * Ищет BE по localPos перебирая ВСЕ уровни сервера.
     * Пропускает уровни без SubLevelContainer (они вернут null из findBlockEntityInSubLevels).
     */
    @Nullable
    public static BlockEntity findBEInAnyLevel(net.minecraft.server.MinecraftServer server, BlockPos localPos) {
        for (ServerLevel level : server.getAllLevels()) {
            // Пропускаем уровни без контейнера SubLevel'ов — там блока быть не может
            if (SubLevelContainer.getContainer(level) == null) continue;
            BlockEntity be = findBlockEntityInSubLevels(level, localPos);
            if (be != null) return be;
        }
        return null;
    }

}