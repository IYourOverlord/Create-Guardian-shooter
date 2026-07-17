package com.yourname.cbcautotarget.event;

import com.simibubi.create.content.redstone.link.RedstoneLinkBlockEntity;
import com.yourname.cbcautotarget.ModBlocks;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity;
import com.yourname.cbcautotarget.compat.SableCompat;
import com.yourname.cbcautotarget.network.OpenMachineSoulGuiPacket;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Перехватывает правый клик по Redstone Link (в основном мире или в SubLevel Sable)
 * когда в руке блок Machine Soul.
 *
 * Логика:
 *  1. Игрок держит MachineSoul item в любой руке
 *  2. Правый клик по Redstone Link
 *  3a. Если линк в основном мире — ищем Soul в основном мире
 *  3b. Если линк в SubLevel Sable — ищем Soul в основном мире по мировым координатам клика
 *  4. Если Soul найден — открываем GUI и отменяем стандартное поведение линка
 */
@EventBusSubscriber(modid = "cbc_autotarget", bus = EventBusSubscriber.Bus.GAME)
public class MachineSoulLinkInteractionHandler {

    private static final int SEARCH_RADIUS = 32;

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;

        ItemStack mainHand = sp.getItemInHand(InteractionHand.MAIN_HAND);
        ItemStack offHand  = sp.getItemInHand(InteractionHand.OFF_HAND);
        if (!isSoulItem(mainHand) && !isSoulItem(offHand)) return;

        Level level = event.getLevel();
        BlockPos clickedPos = event.getPos();

        // ── Случай 1: линк в основном мире ────────────────────────────────────
        BlockEntity clickedBe = level.getBlockEntity(clickedPos);
        if (clickedBe instanceof RedstoneLinkBlockEntity) {
            MachineSoulBlockEntity soul = findNearestSoulInLevel(level, clickedPos);
            if (soul == null) return;
            soul.onPlayerOpened(sp);
            OpenMachineSoulGuiPacket.openFor(sp, soul);
            event.setCanceled(true);
            return;
        }

        // ── Случай 2: линк в SubLevel Sable ───────────────────────────────────
        // Клик по конструкции в основном мире приходит с координатами основного мира.
        // Нужно найти SubLevel, который занимает эту позицию, перевести координаты
        // в локальные, найти RedstoneLinkBlockEntity внутри SubLevel.
        if (!SableCompat.isAvailable()) return;
        if (!(level instanceof ServerLevel sl)) return;

        RedstoneLinkBlockEntity linkInSub = findLinkInSubLevels(sl, clickedPos);
        if (linkInSub == null) return;

        // Soul стоит в основном мире — ищем по мировым координатам клика
        MachineSoulBlockEntity soul = findNearestSoulInLevel(level, clickedPos);
        if (soul == null) return;

        soul.onPlayerOpened(sp);
        OpenMachineSoulGuiPacket.openFor(sp, soul);
        event.setCanceled(true);
    }

    // ── Вспомогательные ───────────────────────────────────────────────────────

    private static boolean isSoulItem(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModBlocks.MACHINE_SOUL.get().asItem());
    }

    /**
     * Ищет ближайший MachineSoulBlockEntity в основном мире в радиусе вокруг center.
     */
    private static MachineSoulBlockEntity findNearestSoulInLevel(Level level, BlockPos center) {
        MachineSoulBlockEntity nearest = null;
        double nearestDist = Double.MAX_VALUE;

        int r = SEARCH_RADIUS;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-r, -r, -r),
                center.offset( r,  r,  r))) {

            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof MachineSoulBlockEntity soul)) continue;

            double dist = pos.distSqr(center);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = soul;
            }
        }
        return nearest;
    }

    /**
     * Перебирает все SubLevel-ы Sable в основном мире.
     * Для каждого переводит worldPos в локальные координаты SubLevel
     * и проверяет, есть ли там RedstoneLinkBlockEntity.
     */
    private static RedstoneLinkBlockEntity findLinkInSubLevels(ServerLevel mainLevel,
                                                               BlockPos worldPos) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(mainLevel);
        if (container == null) return null;

        Vec3 worldVec = Vec3.atCenterOf(worldPos);

        for (ServerSubLevel ssl : container.getAllSubLevels()) {
            if (ssl.isRemoved()) continue;
            try {
                // Переводим мировую позицию в локальную систему SubLevel
                Vec3 localVec = worldToLocal(ssl, worldVec);
                BlockPos localPos = BlockPos.containing(localVec);

                BlockEntity be = ssl.getPlot()
                        .getEmbeddedLevelAccessor()
                        .getBlockEntity(localPos);

                if (be instanceof RedstoneLinkBlockEntity link) {
                    return link;
                }
            } catch (Exception ignored) {
                // SubLevel мог быть удалён или ещё не инициализирован
            }
        }
        return null;
    }

    /**
     * Переводит мировые координаты в локальные координаты SubLevel.
     * Используем обратное преобразование через logicalPose.
     */
    private static Vec3 worldToLocal(ServerSubLevel ssl, Vec3 world) {
        var pose = ssl.logicalPose();
        Vec3 origin = pose.transformPosition(Vec3.ZERO);
        double dx = world.x - origin.x;
        double dy = world.y - origin.y;
        double dz = world.z - origin.z;

        org.joml.Vector3d colX = new org.joml.Vector3d(1, 0, 0);
        org.joml.Vector3d colY = new org.joml.Vector3d(0, 1, 0);
        org.joml.Vector3d colZ = new org.joml.Vector3d(0, 0, 1);
        pose.transformNormal(colX);
        pose.transformNormal(colY);
        pose.transformNormal(colZ);

        return new Vec3(
                colX.x * dx + colX.y * dy + colX.z * dz,
                colY.x * dx + colY.y * dy + colY.z * dz,
                colZ.x * dx + colZ.y * dy + colZ.z * dz
        );
    }
}