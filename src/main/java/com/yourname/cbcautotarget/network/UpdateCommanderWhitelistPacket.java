package com.yourname.cbcautotarget.network;

import com.yourname.cbcautotarget.blockentity.CommanderBlockEntity;
import com.yourname.cbcautotarget.compat.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;

public record UpdateCommanderWhitelistPacket(
        BlockPos pos,
        Action action,
        String playerName,
        boolean enabled
) implements CustomPacketPayload {

    public enum Action {
        ADD, REMOVE, SET_ENABLED;
        private static final Action[] VALUES = values();
        public static Action fromId(int id) { return id >= 0 && id < VALUES.length ? VALUES[id] : SET_ENABLED; }
    }

    public static final Type<UpdateCommanderWhitelistPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cbc_autotarget", "update_commander_whitelist"));

    public static final StreamCodec<FriendlyByteBuf, UpdateCommanderWhitelistPacket> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,                                        UpdateCommanderWhitelistPacket::pos,
                    ByteBufCodecs.INT.map(Action::fromId, a -> a.ordinal()),      UpdateCommanderWhitelistPacket::action,
                    ByteBufCodecs.STRING_UTF8,                                    UpdateCommanderWhitelistPacket::playerName,
                    ByteBufCodecs.BOOL,                                           UpdateCommanderWhitelistPacket::enabled,
                    UpdateCommanderWhitelistPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static UpdateCommanderWhitelistPacket add(BlockPos pos, String name) {
        return new UpdateCommanderWhitelistPacket(pos, Action.ADD, name, false);
    }
    public static UpdateCommanderWhitelistPacket remove(BlockPos pos, String name) {
        return new UpdateCommanderWhitelistPacket(pos, Action.REMOVE, name, false);
    }
    public static UpdateCommanderWhitelistPacket setEnabled(BlockPos pos, boolean enabled) {
        return new UpdateCommanderWhitelistPacket(pos, Action.SET_ENABLED, "", enabled);
    }

    public static void handle(UpdateCommanderWhitelistPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (packet.action() != Action.SET_ENABLED) {
                String name = packet.playerName().trim();
                if (name.isEmpty() || name.length() > 16) return;
            }
            BlockEntity be = sp.serverLevel().getBlockEntity(packet.pos());
            if (be == null && SableCompat.isAvailable()) {
                be = SableCompat.findBlockEntityInSubLevels(sp.serverLevel(), packet.pos());
            }
            if (!(be instanceof CommanderBlockEntity commander)) return;
            boolean onShip = SableCompat.isAvailable()
                    && SableCompat.getSubLevelForBlock(sp.serverLevel(), packet.pos()) != null;
            if (!onShip && sp.blockPosition().distSqr(packet.pos()) > 64 * 64) return;

            var filter = commander.getFilterData();
            switch (packet.action()) {
                case ADD         -> filter.addToWhitelist(packet.playerName());
                case REMOVE      -> filter.removeFromWhitelist(packet.playerName());
                case SET_ENABLED -> filter.setWhitelistEnabled(packet.enabled());
            }
            commander.setChanged();
            // См. подробности в UpdateMachineSoulPlayerFilterPacket.handle():
            // setChanged() не отправляет ClientboundBlockEntityDataPacket, поэтому
            // без sendBlockUpdated() клиентский BE не обновляется, и при повторном
            // открытии GUI вайтлист визуально выглядит сброшенным.
            sp.serverLevel().sendBlockUpdated(packet.pos(), commander.getBlockState(), commander.getBlockState(), 3);

            // ИСПРАВЛЕНИЕ: после изменения вайтлиста отправляем актуальное состояние
            // обратно клиенту, чтобы filterData на клиенте всегда был синхронизирован
            PacketDistributor.sendToPlayer(sp, new SyncCommanderDataPacket(
                    packet.pos(),
                    commander.getFilterMask(),
                    filter.isWhitelistEnabled(),
                    new ArrayList<>(filter.getWhitelist()),
                    commander.getAllianceKey()
            ));
        });
    }
}