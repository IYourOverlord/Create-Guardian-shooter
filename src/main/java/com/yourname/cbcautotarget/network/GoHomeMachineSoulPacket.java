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

/**
 * Клиент → Сервер: вернуться на главную страницу (хаб) Machine Soul.
 * Отправляется при нажатии кнопки "←" на любой вкладке.
 */
public record GoHomeMachineSoulPacket(BlockPos pos) implements CustomPacketPayload {

    public static final Type<GoHomeMachineSoulPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cbc_autotarget", "go_home_machine_soul"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GoHomeMachineSoulPacket> CODEC =
            StreamCodec.of(
                    (buf, pkt) -> buf.writeBlockPos(pkt.pos()),
                    buf -> new GoHomeMachineSoulPacket(buf.readBlockPos())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(GoHomeMachineSoulPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            BlockEntity be = sp.level().getBlockEntity(pkt.pos());
            if (!(be instanceof MachineSoulBlockEntity soul)) return;
            OpenMachineSoulHomePacket.openFor(sp, soul);
        });
    }
}
