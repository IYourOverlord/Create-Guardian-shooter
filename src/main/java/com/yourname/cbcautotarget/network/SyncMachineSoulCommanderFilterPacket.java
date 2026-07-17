package com.yourname.cbcautotarget.network;

import com.yourname.cbcautotarget.client.MachineSoulTargetScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Сервер → Клиент: синхронизирует список дружественных ID командеров
 * MachineSoul после каждого изменения.
 */
public record SyncMachineSoulCommanderFilterPacket(
        BlockPos pos,
        List<String> friendlyIds
) implements CustomPacketPayload {

    public static final Type<SyncMachineSoulCommanderFilterPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cbc_autotarget", "sync_soul_commander_filter"));

    public static final StreamCodec<FriendlyByteBuf, SyncMachineSoulCommanderFilterPacket> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,
                    SyncMachineSoulCommanderFilterPacket::pos,
                    ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8),
                    SyncMachineSoulCommanderFilterPacket::friendlyIds,
                    SyncMachineSoulCommanderFilterPacket::new
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SyncMachineSoulCommanderFilterPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof MachineSoulTargetScreen screen
                    && screen.getBlockPos().equals(packet.pos())) {
                screen.applyCommanderFilterSync(packet.friendlyIds());
            }
        });
    }
}
