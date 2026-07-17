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

public record AssignMachineSoulSlotPacket(
        BlockPos pos,
        CommandRole role,
        ItemStack freq0,
        ItemStack freq1
) implements CustomPacketPayload {

    public static final Type<AssignMachineSoulSlotPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    "cbc_autotarget", "assign_machine_soul_slot"));

    private static final StreamCodec<RegistryFriendlyByteBuf, CommandRole> ROLE_CODEC =
            StreamCodec.of(
                    (buf, role) -> buf.writeByte(role.ordinal()),
                    buf -> CommandRole.values()[buf.readByte()]
            );

    public static final StreamCodec<RegistryFriendlyByteBuf, AssignMachineSoulSlotPacket> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,                  AssignMachineSoulSlotPacket::pos,
                    ROLE_CODEC,                             AssignMachineSoulSlotPacket::role,
                    ItemStack.OPTIONAL_STREAM_CODEC,        AssignMachineSoulSlotPacket::freq0,
                    ItemStack.OPTIONAL_STREAM_CODEC,        AssignMachineSoulSlotPacket::freq1,
                    AssignMachineSoulSlotPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(AssignMachineSoulSlotPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            BlockEntity be = SaveMachineSoulConfigPacket.findBE(sp, packet.pos());
            if (!(be instanceof MachineSoulBlockEntity soul)) return;
            soul.assignSlot(packet.role(), packet.freq0(), packet.freq1());
        });
    }
}
