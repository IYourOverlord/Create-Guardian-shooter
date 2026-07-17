package com.yourname.cbcautotarget.network;

import com.yourname.cbcautotarget.CBCAutoTarget;
import com.yourname.cbcautotarget.blockentity.ControllerBlockEntity;
import com.yourname.cbcautotarget.compat.SableCompat;
import com.yourname.cbcautotarget.filter.TargetCategory;
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

/**
 * Пакет «клиент → сервер» для обновления фильтра целей.
 *
 * <p>Отправляется при каждом изменении чекбокса в {@code TargetFilterScreen}.
 * Сервер валидирует маску и дистанцию до блока, после чего обновляет
 * {@link ControllerBlockEntity#setFilterMask}.
 */
public record UpdateFilterPacket(BlockPos pos, int mask) implements CustomPacketPayload {

    public static final Type<UpdateFilterPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cbc_autotarget", "update_filter"));

    public static final StreamCodec<FriendlyByteBuf, UpdateFilterPacket> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, UpdateFilterPacket::pos,
                    net.minecraft.network.codec.ByteBufCodecs.INT, UpdateFilterPacket::mask,
                    UpdateFilterPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(UpdateFilterPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {

            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            // Валидация: маска должна быть в допустимом диапазоне
            int sanitized = packet.mask() & TargetCategory.ALL_MASK;

            // ── Основной уровень ─────────────────────────────────────────────
            var beMain = sp.serverLevel().getBlockEntity(packet.pos());
            if (beMain instanceof ControllerBlockEntity be) {
                boolean isSablePlot = Math.abs(packet.pos().getX()) > 1_000_000
                        || Math.abs(packet.pos().getZ()) > 1_000_000;
                if (!isSablePlot && sp.blockPosition().distSqr(packet.pos()) > 64 * 64) {
                   /* CBCAutoTarget.LOGGER.warn("[FILTER] Update rejected: player too far (pos={})", packet.pos());*/
                    return;
                }
                be.setFilterMask(sanitized);
               /* CBCAutoTarget.LOGGER.debug("[FILTER] Updated filter mask={} at {}", sanitized, packet.pos());*/
                return;
            }

            // ── Sable SubLevel ───────────────────────────────────────────────
            if (!SableCompat.isAvailable()) return;

            ServerSubLevelContainer container = SubLevelContainer.getContainer(sp.serverLevel());
            if (container == null) return;

            for (ServerSubLevel ssl : container.getAllSubLevels()) {
                if (ssl.isRemoved()) continue;
                var accessor = ssl.getPlot().getEmbeddedLevelAccessor();
                var be2 = accessor.getBlockEntity(packet.pos());
                if (be2 instanceof ControllerBlockEntity be) {
                    be.setFilterMask(sanitized);
                  /*  CBCAutoTarget.LOGGER.debug("[FILTER] Updated filter (SubLevel) mask={} at {}",
                            sanitized, packet.pos());*/
                    return;
                }
            }

           /* CBCAutoTarget.LOGGER.warn("[FILTER] ControllerBlockEntity not found for pos={}", packet.pos());*/
        });
    }
}
