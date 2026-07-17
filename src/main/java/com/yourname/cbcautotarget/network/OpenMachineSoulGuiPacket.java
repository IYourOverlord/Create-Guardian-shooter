package com.yourname.cbcautotarget.network;

import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Открыть GUI MachineSoul — всегда открывает вкладку Vision первой.
 * Переключение между вкладками — через SwitchMachineSoulTabPacket.
 */
public record OpenMachineSoulGuiPacket(BlockPos soulPos) implements CustomPacketPayload {

    public static final Type<OpenMachineSoulGuiPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cbc_autotarget", "open_machine_soul_gui"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenMachineSoulGuiPacket> CODEC =
            StreamCodec.of(
                    (buf, pkt) -> buf.writeBlockPos(pkt.soulPos()),
                    buf -> new OpenMachineSoulGuiPacket(buf.readBlockPos())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** Открыть GUI — теперь открывает главную страницу (хаб). */
    public static void openFor(ServerPlayer sp, MachineSoulBlockEntity soul) {
        OpenMachineSoulHomePacket.openFor(sp, soul);
    }
}
