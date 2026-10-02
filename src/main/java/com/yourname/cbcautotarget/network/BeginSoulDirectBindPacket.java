package com.yourname.cbcautotarget.network;

import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.CommandRole;
import com.yourname.cbcautotarget.event.SoulDirectBindHandler;
import com.yourname.cbcautotarget.menu.MachineSoulMoveMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Arrays;

/** Клиент → Сервер: начать привязку прямого редстоун-выхода роли (MOVE_* или FIRE) к стороне блока. */
public record BeginSoulDirectBindPacket(BlockPos pos, int roleOrdinal) implements CustomPacketPayload {

    public static final Type<BeginSoulDirectBindPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cbc_autotarget", "begin_soul_direct_bind"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BeginSoulDirectBindPacket> CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBlockPos(p.pos());
                        buf.writeVarInt(p.roleOrdinal());
                    },
                    buf -> new BeginSoulDirectBindPacket(buf.readBlockPos(), buf.readVarInt())
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(BeginSoulDirectBindPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            CommandRole[] roles = CommandRole.values();
            if (pkt.roleOrdinal() < 0 || pkt.roleOrdinal() >= roles.length) return;
            CommandRole role = roles[pkt.roleOrdinal()];
            if (role != CommandRole.FIRE && !Arrays.asList(MachineSoulMoveMenu.MOVE_ROLES).contains(role)) return;
            BlockEntity be = SaveMachineSoulConfigPacket.findBE(sp, pkt.pos());
            if (!(be instanceof MachineSoulBlockEntity soul)) return;
            if (SaveMachineSoulConfigPacket.isSoulLocked(soul, sp)) return;
            SoulDirectBindHandler.begin(sp, soul, role);
        });
    }
}
