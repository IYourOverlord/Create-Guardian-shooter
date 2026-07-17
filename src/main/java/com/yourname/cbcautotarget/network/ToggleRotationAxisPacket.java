package com.yourname.cbcautotarget.network;

import com.yourname.cbcautotarget.blockentity.ControllerBlockEntity;
import com.yourname.cbcautotarget.compat.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Пакет клиент → сервер: переключение разрешения вращения пушки
 * по горизонтали (H) или вертикали (V).
 *
 * axis=true  → горизонталь (yaw)
 * axis=false → вертикаль  (pitch)
 */
public record ToggleRotationAxisPacket(BlockPos pos, boolean axis, boolean enabled)
        implements CustomPacketPayload {

    private static final Logger LOGGER = LoggerFactory.getLogger("cbc_autotarget/ToggleRotationAxis");

    public static final Type<ToggleRotationAxisPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cbc_autotarget", "toggle_rotation_axis"));

    public static final StreamCodec<FriendlyByteBuf, ToggleRotationAxisPacket> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,      ToggleRotationAxisPacket::pos,
                    ByteBufCodecs.BOOL,         ToggleRotationAxisPacket::axis,
                    ByteBufCodecs.BOOL,         ToggleRotationAxisPacket::enabled,
                    ToggleRotationAxisPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(ToggleRotationAxisPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            BlockEntity be = sp.serverLevel().getBlockEntity(packet.pos());

            // Поиск в SubLevel'ах Sable если не найдено в основном мире
            if (be == null && SableCompat.isAvailable()) {
                be = SableCompat.findBlockEntityInSubLevels(sp.serverLevel(), packet.pos());
            }

            if (!(be instanceof ControllerBlockEntity controller)) {
                LOGGER.warn("[ToggleRotationAxis] No ControllerBlockEntity at {}", packet.pos());
                return;
            }

            double distSq = sp.blockPosition().distSqr(packet.pos());
            boolean isSablePlot = Math.abs(packet.pos().getX()) > 1_000_000
                    || Math.abs(packet.pos().getZ()) > 1_000_000;
            if (!isSablePlot && distSq > 64 * 64) {
                LOGGER.warn("[ToggleRotationAxis] Player too far, aborting");
                return;
            }

            if (packet.axis()) {
                // true = горизонталь (yaw)
                controller.setAllowHorizontal(packet.enabled());
                LOGGER.debug("[ToggleRotationAxis] allowHorizontal={} at {}", packet.enabled(), packet.pos());
            } else {
                // false = вертикаль (pitch)
                controller.setAllowVertical(packet.enabled());
                LOGGER.debug("[ToggleRotationAxis] allowVertical={} at {}", packet.enabled(), packet.pos());
            }
        });
    }
}
