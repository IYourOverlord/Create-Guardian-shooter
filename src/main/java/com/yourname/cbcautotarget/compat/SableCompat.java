package com.yourname.cbcautotarget.compat;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
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