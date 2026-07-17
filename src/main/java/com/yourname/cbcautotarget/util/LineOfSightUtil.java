package com.yourname.cbcautotarget.util;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

public final class LineOfSightUtil {

    private LineOfSightUtil() {}

    public static boolean hasLineOfSightFromSubLevel(ServerSubLevel subLevel, Vec3 worldMuzzle, Vec3 worldTarget) {
        // Обратное преобразование: мировые → локальные координаты sublevel
        Vec3 localMuzzle  = worldToLocal(subLevel, worldMuzzle);
        Vec3 localTarget  = worldToLocal(subLevel, worldTarget);
        Level subLevelWorld = subLevel.getLevel();
        return hasLineOfSight(subLevelWorld, localMuzzle, localTarget);
    }

    public static boolean hasLineOfSightToEntityFromSubLevel(ServerSubLevel subLevel,
                                                             Vec3 worldMuzzle,
                                                             Entity target) {
        float h = target.getBbHeight();
        Vec3 wFeet   = target.position().add(0, 0.1,     0);
        Vec3 wCenter = target.position().add(0, h * 0.5, 0);
        Vec3 wHead   = target.position().add(0, h - 0.1, 0);
        return hasLineOfSightFromSubLevel(subLevel, worldMuzzle, wCenter)
                || hasLineOfSightFromSubLevel(subLevel, worldMuzzle, wFeet)
                || hasLineOfSightFromSubLevel(subLevel, worldMuzzle, wHead);
    }

    /**
     * Мировые координаты → локальные координаты sublevel.
     *
     * logicalPose() не имеет inverse(), поэтому инвертируем вручную.
     * Для аффинного преобразования local→world: P_world = R * P_local + T
     * Обратное: P_local = R^T * (P_world - T)
     *
     * T = transformPosition(ZERO) — origin корабля в мировых координатах.
     * R^T — транспонированная ротация, получаем её через три базисных вектора:
     *   каждый из (1,0,0), (0,1,0), (0,0,1) трансформируем через transformNormal,
     *   получаем столбцы матрицы R. Строки R^T = столбцы R → применяем dot-product.
     */
    private static Vec3 worldToLocal(ServerSubLevel subLevel, Vec3 world) {
        var pose = subLevel.logicalPose();

        // Трансляция: куда попадает локальный origin (0,0,0) в мировых координатах
        Vec3 translation = pose.transformPosition(Vec3.ZERO);

        // Вектор в мировых координатах относительно origin корабля
        double dx = world.x - translation.x;
        double dy = world.y - translation.y;
        double dz = world.z - translation.z;

        // Столбцы матрицы R: куда уходят локальные оси X, Y, Z
        Vector3d colX = new Vector3d(1, 0, 0);
        Vector3d colY = new Vector3d(0, 1, 0);
        Vector3d colZ = new Vector3d(0, 0, 1);
        pose.transformNormal(colX);
        pose.transformNormal(colY);
        pose.transformNormal(colZ);

        // R^T * d = dot(col_i, d) для каждой оси
        double localX = colX.x * dx + colX.y * dy + colX.z * dz;
        double localY = colY.x * dx + colY.y * dy + colY.z * dz;
        double localZ = colZ.x * dx + colZ.y * dy + colZ.z * dz;

        return new Vec3(localX, localY, localZ);
    }

    /**
     * Универсальная проверка видимости для любой сущности.
     * Для {@link LivingEntity} использует тройную проверку (ноги, центр, голова).
     * Для прочих сущностей (например, contraption) — аналогичные три точки по BB.
     */
    public static boolean hasLineOfSightToEntity(Level level, Vec3 muzzlePos, Entity target) {
        if (target instanceof LivingEntity le) {
            return hasLineOfSightMulti(level, muzzlePos, le);
        }
        // Для не-живых сущностей (PitchOrientedContraptionEntity и др.)
        float h = target.getBbHeight();
        Vec3 feet   = target.position().add(0, 0.1,     0);
        Vec3 center = target.position().add(0, h * 0.5, 0);
        Vec3 head   = target.position().add(0, h - 0.1, 0);
        return hasLineOfSight(level, muzzlePos, center)
                || hasLineOfSight(level, muzzlePos, feet)
                || hasLineOfSight(level, muzzlePos, head);
    }

    /**
     * Проверяет прямую видимость от muzzlePos до трёх точек цели
     * (центр, ноги, голова). Возвращает true если хотя бы одна видна.
     */
    public static boolean hasLineOfSightMulti(Level level, Vec3 muzzlePos, LivingEntity target) {
        Vec3 feet   = target.position().add(0, 0.1, 0);
        Vec3 center = target.position().add(0, target.getBbHeight() * 0.5, 0);
        Vec3 head   = target.position().add(0, target.getBbHeight() - 0.1, 0);

        return hasLineOfSight(level, muzzlePos, center)
                || hasLineOfSight(level, muzzlePos, feet)
                || hasLineOfSight(level, muzzlePos, head);
    }

    /**
     * Raycast между двумя точками. Возвращает true если нет твёрдых блоков на пути.
     */
    public static boolean hasLineOfSight(Level level, Vec3 from, Vec3 to) {
        ClipContext ctx = new ClipContext(
                from,
                to,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                // В NeoForge 1.21 последний аргумент — ShapeGetter (Entity или null-compatible)
                // используем overload через CollisionContext.empty() — передаём фиктивный контекст
                net.minecraft.world.phys.shapes.CollisionContext.empty()
        );
        HitResult hit = level.clip(ctx);
        return hit.getType() == HitResult.Type.MISS;
    }
}