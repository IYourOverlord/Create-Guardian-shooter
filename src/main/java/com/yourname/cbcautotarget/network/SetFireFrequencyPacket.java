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
 * Пакет клиент → сервер: устанавливает частоту синхронного огня (Fire Trigger)
 * для контроллера. 0 = частота отключена.
 */
public record SetFireFrequencyPacket(BlockPos pos, int frequency)
        implements CustomPacketPayload {

    private static final Logger LOGGER = LoggerFactory.getLogger("cbc_autotarget/SetFireFrequency");

    public static final Type<SetFireFrequencyPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cbc_autotarget", "set_fire_frequency"));

    public static final StreamCodec<FriendlyByteBuf, SetFireFrequencyPacket> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,      SetFireFrequencyPacket::pos,
                    ByteBufCodecs.VAR_INT,      SetFireFrequencyPacket::frequency,
                    SetFireFrequencyPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SetFireFrequencyPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            BlockEntity be = sp.serverLevel().getBlockEntity(packet.pos());

            // Поиск в SubLevel'ах Sable если не найдено в основном мире
            if (be == null && SableCompat.isAvailable()) {
                be = SableCompat.findBlockEntityInSubLevels(sp.serverLevel(), packet.pos());
            }

            if (!(be instanceof ControllerBlockEntity controller)) {
                LOGGER.warn("[SetFireFrequency] No ControllerBlockEntity at {}", packet.pos());
                return;
            }

            double distSq = sp.blockPosition().distSqr(packet.pos());
            boolean isSablePlot = Math.abs(packet.pos().getX()) > 1_000_000
                    || Math.abs(packet.pos().getZ()) > 1_000_000;
            if (!isSablePlot && distSq > 64 * 64) {
                LOGGER.warn("[SetFireFrequency] Player too far, aborting");
                return;
            }

            controller.setFireFrequency(packet.frequency());
            LOGGER.debug("[SetFireFrequency] frequency={} at {}", packet.frequency(), packet.pos());
        });
    }
}
