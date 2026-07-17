package com.yourname.cbcautotarget.network;

import com.yourname.cbcautotarget.CBCAutoTarget;
import com.yourname.cbcautotarget.blockentity.ControllerBlockEntity;
import com.yourname.cbcautotarget.compat.SableCompat;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record UpdateWhitelistPacket(
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

    public static final Type<UpdateWhitelistPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cbc_autotarget", "update_whitelist"));

    public static final StreamCodec<FriendlyByteBuf, UpdateWhitelistPacket> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,        UpdateWhitelistPacket::pos,
                    ByteBufCodecs.INT.map(Action::fromId, a -> a.ordinal()),
                                                  UpdateWhitelistPacket::action,
                    ByteBufCodecs.STRING_UTF8,    UpdateWhitelistPacket::playerName,
                    ByteBufCodecs.BOOL,           UpdateWhitelistPacket::enabled,
                    UpdateWhitelistPacket::new
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static UpdateWhitelistPacket add(BlockPos pos, String name) {
        return new UpdateWhitelistPacket(pos, Action.ADD, name, false);
    }
    public static UpdateWhitelistPacket remove(BlockPos pos, String name) {
        return new UpdateWhitelistPacket(pos, Action.REMOVE, name, false);
    }
    public static UpdateWhitelistPacket setEnabled(BlockPos pos, boolean enabled) {
        return new UpdateWhitelistPacket(pos, Action.SET_ENABLED, "", enabled);
    }

    public static void handle(UpdateWhitelistPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (packet.action() != Action.SET_ENABLED) {
                String name = packet.playerName().trim();
                if (name.isEmpty() || name.length() > 16) return;
            }
            ControllerBlockEntity be = findBE(sp, packet.pos());
            if (be == null) return;
            var filter = be.getFilterData();
            switch (packet.action()) {
                case ADD    -> filter.addToWhitelist(packet.playerName());
                case REMOVE -> filter.removeFromWhitelist(packet.playerName());
                case SET_ENABLED -> filter.setWhitelistEnabled(packet.enabled());
            }
            be.setChanged();
            // См. подробности в UpdateMachineSoulPlayerFilterPacket.handle():
            // без sendBlockUpdated() клиентский BE не получает новые данные
            // фильтра, и при повторном открытии GUI вайтлист выглядит сброшенным,
            // хотя на сервере всё сохранено и реально применяется.
            sp.serverLevel().sendBlockUpdated(packet.pos(), be.getBlockState(), be.getBlockState(), 3);
            /*CBCAutoTarget.LOGGER.debug("[WL] {} '{}' enabled={}", packet.action(), packet.playerName(), packet.enabled());*/
        });
    }

    private static ControllerBlockEntity findBE(ServerPlayer sp, BlockPos pos) {
        var be = sp.serverLevel().getBlockEntity(pos);
        if (be instanceof ControllerBlockEntity c) return c;
        if (!SableCompat.isAvailable()) return null;
        ServerSubLevelContainer container = SubLevelContainer.getContainer(sp.serverLevel());
        if (container == null) return null;
        for (ServerSubLevel ssl : container.getAllSubLevels()) {
            if (ssl.isRemoved()) continue;
            var be2 = ssl.getPlot().getEmbeddedLevelAccessor().getBlockEntity(pos);
            if (be2 instanceof ControllerBlockEntity c) return c;
        }
        return null;
    }
}
