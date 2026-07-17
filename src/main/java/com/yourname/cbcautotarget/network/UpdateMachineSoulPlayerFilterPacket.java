package com.yourname.cbcautotarget.network;

import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity;
import com.yourname.cbcautotarget.compat.SableCompat;
import com.yourname.cbcautotarget.filter.WhitelistMode;
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
 * Клиент → Сервер: изменить фильтр игроков в MachineSoul.
 *
 * Операции:
 *   ADD          — добавить имя в вайтлист
 *   REMOVE       — удалить имя из вайтлиста
 *   SET_ENABLED  — включить/выключить вайтлист
 *   SET_MODE     — переключить WhitelistMode (TARGET / IGNORE / FOLLOW)
 *   SET_MASK     — установить маску категорий целей (HOSTILE/PASSIVE/PLAYERS)
 */
public record UpdateMachineSoulPlayerFilterPacket(
        BlockPos pos,
        Action action,
        String playerName,
        boolean enabled,
        int modeId,
        int mask
) implements CustomPacketPayload {

    public enum Action {
        ADD, REMOVE, SET_ENABLED, SET_MODE, SET_MASK;
        private static final Action[] VALUES = values();
        public static Action fromId(int id) { return id >= 0 && id < VALUES.length ? VALUES[id] : SET_ENABLED; }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("cbc_autotarget/UpdatePlayerFilter");

    public static final Type<UpdateMachineSoulPlayerFilterPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cbc_autotarget", "update_soul_player_filter"));

    public static final StreamCodec<FriendlyByteBuf, UpdateMachineSoulPlayerFilterPacket> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,                                              UpdateMachineSoulPlayerFilterPacket::pos,
                    ByteBufCodecs.INT.map(Action::fromId, a -> a.ordinal()),            UpdateMachineSoulPlayerFilterPacket::action,
                    ByteBufCodecs.STRING_UTF8,                                          UpdateMachineSoulPlayerFilterPacket::playerName,
                    ByteBufCodecs.BOOL,                                                 UpdateMachineSoulPlayerFilterPacket::enabled,
                    ByteBufCodecs.INT,                                                  UpdateMachineSoulPlayerFilterPacket::modeId,
                    ByteBufCodecs.INT,                                                  UpdateMachineSoulPlayerFilterPacket::mask,
                    UpdateMachineSoulPlayerFilterPacket::new
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    // ── Фабричные методы ──────────────────────────────────────────────────────

    public static UpdateMachineSoulPlayerFilterPacket add(BlockPos pos, String name) {
        return new UpdateMachineSoulPlayerFilterPacket(pos, Action.ADD, name, false, 0, 0);
    }
    public static UpdateMachineSoulPlayerFilterPacket remove(BlockPos pos, String name) {
        return new UpdateMachineSoulPlayerFilterPacket(pos, Action.REMOVE, name, false, 0, 0);
    }
    public static UpdateMachineSoulPlayerFilterPacket setEnabled(BlockPos pos, boolean enabled) {
        return new UpdateMachineSoulPlayerFilterPacket(pos, Action.SET_ENABLED, "", enabled, 0, 0);
    }
    public static UpdateMachineSoulPlayerFilterPacket setMode(BlockPos pos, WhitelistMode mode) {
        return new UpdateMachineSoulPlayerFilterPacket(pos, Action.SET_MODE, "", false, mode.id(), 0);
    }
    public static UpdateMachineSoulPlayerFilterPacket setMask(BlockPos pos, int mask) {
        return new UpdateMachineSoulPlayerFilterPacket(pos, Action.SET_MASK, "", false, 0, mask);
    }

    // ── Обработчик на сервере ─────────────────────────────────────────────────

    public static void handle(UpdateMachineSoulPlayerFilterPacket packet, IPayloadContext ctx) {
        LOGGER.info("[UpdatePlayerFilter] RECEIVED pos={} action={} playerName='{}' enabled={} modeId={} mask={}",
                packet.pos(), packet.action(), packet.playerName(), packet.enabled(), packet.modeId(), packet.mask());
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                LOGGER.warn("[UpdatePlayerFilter] ABORT pos={} — ctx.player() is not ServerPlayer (was {})",
                        packet.pos(), ctx.player());
                return;
            }

            if (packet.action() == Action.ADD || packet.action() == Action.REMOVE) {
                String name = packet.playerName().trim();
                if (name.isEmpty() || name.length() > 16) {
                    LOGGER.warn("[UpdatePlayerFilter] ABORT pos={} action={} — invalid name '{}' (len={})",
                            packet.pos(), packet.action(), name, name.length());
                    return;
                }
            }

            BlockEntity be = sp.serverLevel().getBlockEntity(packet.pos());
            boolean viaSubLevel = false;
            if (be == null && SableCompat.isAvailable()) {
                be = SableCompat.findBlockEntityInSubLevels(sp.serverLevel(), packet.pos());
                viaSubLevel = be != null;
            }
            if (!(be instanceof MachineSoulBlockEntity soul)) {
                LOGGER.warn("[UpdatePlayerFilter] ABORT pos={} — no MachineSoulBlockEntity found (be={}, viaSubLevel={})",
                        packet.pos(), be != null ? be.getClass().getSimpleName() : "null", viaSubLevel);
                return;
            }

            boolean onShip = SableCompat.isAvailable()
                    && SableCompat.getSubLevelForBlock(sp.serverLevel(), packet.pos()) != null;
            double distSqr = sp.blockPosition().distSqr(packet.pos());
            if (!onShip && distSqr > 64 * 64) {
                LOGGER.warn("[UpdatePlayerFilter] ABORT pos={} — player too far, distSqr={} onShip={}",
                        packet.pos(), distSqr, onShip);
                return;
            }

            var filter = soul.getPlayerFilterData();
            int maskBefore = filter.getMask();
            switch (packet.action()) {
                case ADD         -> filter.addToWhitelist(packet.playerName().trim());
                case REMOVE      -> filter.removeFromWhitelist(packet.playerName().trim());
                case SET_ENABLED -> filter.setWhitelistEnabled(packet.enabled());
                case SET_MODE    -> soul.setWhitelistMode(WhitelistMode.fromId(packet.modeId()));
                case SET_MASK    -> filter.setMask(packet.mask());
            }
            int maskAfter = filter.getMask();
            LOGGER.info("[UpdatePlayerFilter] APPLIED pos={} action={} maskBefore={} maskAfter={} (requested={}) viaSubLevel={}",
                    packet.pos(), packet.action(), maskBefore, maskAfter, packet.mask(), viaSubLevel);

            soul.setChanged();
            LOGGER.info("[UpdatePlayerFilter] setChanged() called pos={} — chunk marked dirty for disk save", packet.pos());
            // КРИТИЧНО: setChanged() только помечает чанк для сохранения на диск,
            // но НЕ отправляет клиенту обновлённые данные block entity. Без
            // sendBlockUpdated() клиентский ClientLevel BE остаётся со старыми
            // данными фильтра/режима, и при повторном открытии GUI (которое
            // строит состояние Menu из клиентского BE) чекбоксы, режим
            // Ignore/Follow и список whitelist визуально "сбрасываются" —
            // хотя на сервере всё сохранено верно и реально применяется.
            sp.serverLevel().sendBlockUpdated(packet.pos(), soul.getBlockState(), soul.getBlockState(), 3);
            LOGGER.info("[UpdatePlayerFilter] sendBlockUpdated() called pos={} — should trigger getUpdateTag/handleUpdateTag on client", packet.pos());

            // Синхронизируем обратно клиенту
            LOGGER.info("[UpdatePlayerFilter] SENDING SyncMachineSoulPlayerFilterPacket pos={} whitelistEnabled={} whitelistSize={} modeId={} mask={} to player={}",
                    packet.pos(), filter.isWhitelistEnabled(), filter.getWhitelist().size(),
                    soul.getWhitelistMode().id(), filter.getMask(), sp.getGameProfile().getName());
            PacketDistributor.sendToPlayer(sp, new SyncMachineSoulPlayerFilterPacket(
                    packet.pos(),
                    filter.isWhitelistEnabled(),
                    new ArrayList<>(filter.getWhitelist()),
                    soul.getWhitelistMode().id(),
                    filter.getMask()
            ));
        });
    }
}
