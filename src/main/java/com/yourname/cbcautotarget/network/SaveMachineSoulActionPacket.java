package com.yourname.cbcautotarget.network;

import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.CommandRole;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Клиент → Сервер: сохранить freq-слот огня (вкладка Action). */
public record SaveMachineSoulActionPacket(BlockPos pos, ItemStack freq0, ItemStack freq1)
        implements CustomPacketPayload {

    public static final Type<SaveMachineSoulActionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cbc_autotarget", "save_soul_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SaveMachineSoulActionPacket> CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBlockPos(p.pos());
                        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, p.freq0());
                        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, p.freq1());
                    },
                    buf -> new SaveMachineSoulActionPacket(
                            buf.readBlockPos(),
                            ItemStack.OPTIONAL_STREAM_CODEC.decode(buf),
                            ItemStack.OPTIONAL_STREAM_CODEC.decode(buf))
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SaveMachineSoulActionPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            BlockEntity be = SaveMachineSoulConfigPacket.findBE(sp, pkt.pos());
            if (!(be instanceof MachineSoulBlockEntity soul)) return;
            soul.assignSlot(CommandRole.FIRE, pkt.freq0(), pkt.freq1());
            soul.onPlayerSaved(sp);
        });
    }
}
