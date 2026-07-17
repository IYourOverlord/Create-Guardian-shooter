package com.yourname.cbcautotarget.network;

import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity;
import com.yourname.cbcautotarget.compat.SableCompat;
import com.yourname.cbcautotarget.filter.CommanderFilterData;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

/**
 * Клиент → Сервер: изменить фильтр дружественных командеров в MachineSoul.
 *
 * Операции:
 *   ADD    — добавить Alliance Key в список друзей (та же строка, что вручную
 *            вводится в поле "Alliance Key:" внутри блока командера, до
 *            CommanderFilterData.MAX_KEY_LENGTH символов)
 *   REMOVE — удалить Alliance Key из списка друзей
 */
public record UpdateMachineSoulCommanderFilterPacket(
        BlockPos pos,
        Action action,
        String commanderId
) implements CustomPacketPayload {

    public enum Action {
        ADD, REMOVE;
        private static final Action[] VALUES = values();
        public static Action fromId(int id) { return id >= 0 && id < VALUES.length ? VALUES[id] : ADD; }
    }

    public static final Type<UpdateMachineSoulCommanderFilterPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cbc_autotarget", "update_soul_commander_filter"));

    public static final StreamCodec<FriendlyByteBuf, UpdateMachineSoulCommanderFilterPacket> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,                                              UpdateMachineSoulCommanderFilterPacket::pos,
                    ByteBufCodecs.INT.map(Action::fromId, a -> a.ordinal()),            UpdateMachineSoulCommanderFilterPacket::action,
                    ByteBufCodecs.STRING_UTF8,                                          UpdateMachineSoulCommanderFilterPacket::commanderId,
                    UpdateMachineSoulCommanderFilterPacket::new
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    // ── Фабричные методы ──────────────────────────────────────────────────────

    public static UpdateMachineSoulCommanderFilterPacket add(BlockPos pos, String id) {
        return new UpdateMachineSoulCommanderFilterPacket(pos, Action.ADD, id);
    }
    public static UpdateMachineSoulCommanderFilterPacket remove(BlockPos pos, String id) {
        return new UpdateMachineSoulCommanderFilterPacket(pos, Action.REMOVE, id);
    }

    // ── Обработчик на сервере ─────────────────────────────────────────────────

    private static final Logger LOGGER = LoggerFactory.getLogger("cbc_autotarget/UpdateCommanderFilter");

    public static void handle(UpdateMachineSoulCommanderFilterPacket packet, IPayloadContext ctx) {
        LOGGER.info("[UpdateCommanderFilter] RECEIVED pos={} action={} commanderId='{}'",
                packet.pos(), packet.action(), packet.commanderId());
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            String id = CommanderFilterData.normalize(packet.commanderId());
            if (id.isEmpty() || id.length() > CommanderFilterData.MAX_KEY_LENGTH) {
                LOGGER.warn("[UpdateCommanderFilter] ABORT pos={} — invalid id '{}' (normalized='{}')",
                        packet.pos(), packet.commanderId(), id);
                return;
            }

            BlockEntity be = sp.serverLevel().getBlockEntity(packet.pos());
            if (be == null && SableCompat.isAvailable())
                be = SableCompat.findBlockEntityInSubLevels(sp.serverLevel(), packet.pos());
            if (!(be instanceof MachineSoulBlockEntity soul)) {
                LOGGER.warn("[UpdateCommanderFilter] ABORT pos={} — no MachineSoulBlockEntity found (be={})",
                        packet.pos(), be != null ? be.getClass().getSimpleName() : "null");
                return;
            }

            boolean onShip = SableCompat.isAvailable()
                    && SableCompat.getSubLevelForBlock(sp.serverLevel(), packet.pos()) != null;
            if (!onShip && sp.blockPosition().distSqr(packet.pos()) > 64 * 64) {
                LOGGER.warn("[UpdateCommanderFilter] ABORT pos={} — player too far", packet.pos());
                return;
            }

            var filter = soul.getCommanderFilterData();
            LOGGER.info("[UpdateCommanderFilter] pos={} BEFORE friendlyIds={}", packet.pos(), filter.getFriendlyIds());
            switch (packet.action()) {
                case ADD    -> filter.addFriendly(id);
                case REMOVE -> filter.removeFriendly(id);
            }
            LOGGER.info("[UpdateCommanderFilter] pos={} AFTER action={} id='{}' friendlyIds={}",
                    packet.pos(), packet.action(), id, filter.getFriendlyIds());
            soul.setChanged();
            // См. подробный комментарий в UpdateMachineSoulPlayerFilterPacket.handle():
            // без sendBlockUpdated() клиентский BE не получает новые данные, и
            // список дружественных командеров визуально "теряется" при
            // повторном открытии GUI, хотя реально сохранён и применяется.
            sp.serverLevel().sendBlockUpdated(packet.pos(), soul.getBlockState(), soul.getBlockState(), 3);

            // Синхронизируем обратно клиенту
            PacketDistributor.sendToPlayer(sp, new SyncMachineSoulCommanderFilterPacket(
                    packet.pos(),
                    new ArrayList<>(filter.getFriendlyIds())
            ));
        });
    }
}
