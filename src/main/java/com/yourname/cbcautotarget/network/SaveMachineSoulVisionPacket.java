package com.yourname.cbcautotarget.network;

import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Клиент → Сервер: сохранить настройки вкладки Vision (радиус обнаружения + дистанция удержания). */
public record SaveMachineSoulVisionPacket(BlockPos pos, int radius, int keepDistance, int standStillDistance) implements CustomPacketPayload {

    public static final Type<SaveMachineSoulVisionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cbc_autotarget", "save_soul_vision"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SaveMachineSoulVisionPacket> CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBlockPos(p.pos());
                        buf.writeInt(p.radius());
                        buf.writeInt(p.keepDistance());
                        buf.writeInt(p.standStillDistance());
                    },
                    buf -> new SaveMachineSoulVisionPacket(buf.readBlockPos(), buf.readInt(), buf.readInt(), buf.readInt())
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SaveMachineSoulVisionPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            BlockEntity be = SaveMachineSoulConfigPacket.findBE(sp, pkt.pos());
            if (!(be instanceof MachineSoulBlockEntity soul)) return;
            soul.setDetectionRadius(pkt.radius());
            soul.setKeepDistance(pkt.keepDistance());
            soul.setStandStillDistance(pkt.standStillDistance());
            soul.onPlayerSaved(sp);
        });
    }
}
