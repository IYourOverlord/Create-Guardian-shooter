package com.yourname.cbcautotarget.network;

import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity;
import com.yourname.cbcautotarget.compat.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Клиент → Сервер: переключить гироскопическую стабилизацию крена (Sable)
 * у Machine Soul (кнопка на вкладке NPC).
 */
public record ToggleMachineSoulGyroStabilizationPacket(BlockPos pos) implements CustomPacketPayload {

    private static final Logger LOGGER = LoggerFactory.getLogger("cbc_autotarget/ToggleSoulGyro");

    public static final Type<ToggleMachineSoulGyroStabilizationPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cbc_autotarget", "toggle_soul_gyro_stabilization"));

    public static final StreamCodec<FriendlyByteBuf, ToggleMachineSoulGyroStabilizationPacket> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ToggleMachineSoulGyroStabilizationPacket::pos,
                    ToggleMachineSoulGyroStabilizationPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(ToggleMachineSoulGyroStabilizationPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                LOGGER.warn("[ToggleGyro] Player is not ServerPlayer, aborting");
                return;
            }

            BlockEntity be = sp.serverLevel().getBlockEntity(packet.pos());
            if (be == null && SableCompat.isAvailable()) {
                be = SableCompat.findBlockEntityInSubLevels(sp.serverLevel(), packet.pos());
            }

            if (!(be instanceof MachineSoulBlockEntity soul)) {
                LOGGER.warn("[ToggleGyro] No MachineSoulBlockEntity found at {}", packet.pos());
                return;
            }

            boolean onShip = SableCompat.isAvailable()
                    && SableCompat.getSubLevelForBlock(sp.serverLevel(), packet.pos()) != null;
            double distSq = sp.blockPosition().distSqr(packet.pos());

            if (!onShip && distSq > 64 * 64) {
                LOGGER.warn("[ToggleGyro] Player too far (distSq={}), aborting", distSq);
                return;
            }

            soul.setGyroStabilizationActive(!soul.isGyroStabilizationActive());
            LOGGER.info("[ToggleGyro] pos={} -> {}", packet.pos(), soul.isGyroStabilizationActive());
        });
    }
}
