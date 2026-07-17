package com.yourname.cbcautotarget.network;

import com.yourname.cbcautotarget.CBCAutoTarget;
import com.yourname.cbcautotarget.blockentity.ControllerBlockEntity;
import com.yourname.cbcautotarget.compat.SableCompat;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ToggleActivePacket(BlockPos pos) implements CustomPacketPayload {

    public static final Type<ToggleActivePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cbc_autotarget", "toggle_active"));

    public static final StreamCodec<FriendlyByteBuf, ToggleActivePacket> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,
                    ToggleActivePacket::pos,
                    ToggleActivePacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(ToggleActivePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
           /* CBCAutoTarget.LOGGER.info("[TOGGLE] Packet received. pos={}", packet.pos());
*/
            if (!(ctx.player() instanceof ServerPlayer sp)) {
              /* CBCAutoTarget.LOGGER.warn("[TOGGLE] Player is not ServerPlayer, aborting");
                */
                return;

            }

            CBCAutoTarget.LOGGER.info("[TOGGLE] Player='{}' playerPos={} playerLevel={}",
                    sp.getName().getString(),
                    sp.blockPosition(),
                    sp.serverLevel().dimension().location());

              var beMain = sp.serverLevel().getBlockEntity(packet.pos());
          /*  CBCAutoTarget.LOGGER.info("[TOGGLE] Main level getBlockEntity({}) = {}",
                    packet.pos(), beMain == null ? "null" : beMain.getClass().getSimpleName());
*/
            if (beMain instanceof ControllerBlockEntity be) {
                double dist = sp.blockPosition().distSqr(packet.pos());
              /*  CBCAutoTarget.LOGGER.info("[TOGGLE] Found in main level. distSqr={}", dist);
                */// Если координаты plot-пространства Sable (очень большие) — пропускаем дистанс-чек
                boolean isSablePlot = Math.abs(packet.pos().getX()) > 1_000_000
                        || Math.abs(packet.pos().getZ()) > 1_000_000;
                if (!isSablePlot && dist > 64 * 64) {
                  /*  CBCAutoTarget.LOGGER.warn("[TOGGLE] Too far! dist>{}", 64 * 64);
                   */ return;
                }
                be.setActive(!be.isActive());
              /*  CBCAutoTarget.LOGGER.info("[TOGGLE] setActive({}) OK isSablePlot={}", be.isActive(), isSablePlot);
                */return;
            }

            // 2. SubLevel'ы Sable
           /* CBCAutoTarget.LOGGER.info("[TOGGLE] Not found in main level. Sable available={}",
                    SableCompat.isAvailable());
*/
            if (!SableCompat.isAvailable()) return;

            ServerSubLevelContainer container = SubLevelContainer.getContainer(sp.serverLevel());
           /* CBCAutoTarget.LOGGER.info("[TOGGLE] SubLevelContainer={}", container == null ? "null" : "present");
            */if (container == null) return;

            var allSubLevels = container.getAllSubLevels();
            /*CBCAutoTarget.LOGGER.info("[TOGGLE] SubLevel count={}", allSubLevels.size());
*/
            for (ServerSubLevel ssl : allSubLevels) {
             /*   CBCAutoTarget.LOGGER.info("[TOGGLE] Checking SubLevel uuid={} removed={}",
                        ssl.getUniqueId(), ssl.isRemoved());
               */ if (ssl.isRemoved()) continue;

                var accessor = ssl.getPlot().getEmbeddedLevelAccessor();
                var be2 = accessor.getBlockEntity(packet.pos());
               /* CBCAutoTarget.LOGGER.info("[TOGGLE]   getBlockEntity({}) in SubLevel = {}",
                        packet.pos(), be2 == null ? "null" : be2.getClass().getSimpleName());
*/
                if (be2 instanceof ControllerBlockEntity be) {
                    be.setActive(!be.isActive());
                 /*   CBCAutoTarget.LOGGER.info("[TOGGLE] setActive({}) in SubLevel OK", be.isActive());
                   */ return;
                }
            }

           /* CBCAutoTarget.LOGGER.warn("[TOGGLE] ControllerBlockEntity NOT FOUND anywhere for pos={}", packet.pos());
        */});
    }
}