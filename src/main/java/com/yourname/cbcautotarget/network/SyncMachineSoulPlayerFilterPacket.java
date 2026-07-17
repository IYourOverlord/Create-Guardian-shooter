package com.yourname.cbcautotarget.network;

import com.yourname.cbcautotarget.client.MachineSoulPlayerFilterScreen;
import com.yourname.cbcautotarget.client.MachineSoulTargetScreen;
import com.yourname.cbcautotarget.filter.WhitelistMode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Сервер → Клиент: синхронизирует состояние фильтра игроков Machine Soul
 * (вайтлист, флаг enabled, режим WhitelistMode, маска категорий целей)
 * после каждого изменения.
 */
public record SyncMachineSoulPlayerFilterPacket(
        BlockPos pos,
        boolean whitelistEnabled,
        List<String> whitelist,
        int modeId,
        int mask
) implements CustomPacketPayload {

    public static final Type<SyncMachineSoulPlayerFilterPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cbc_autotarget", "sync_soul_player_filter"));

    public static final StreamCodec<FriendlyByteBuf, SyncMachineSoulPlayerFilterPacket> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,
                    SyncMachineSoulPlayerFilterPacket::pos,
                    ByteBufCodecs.BOOL,
                    SyncMachineSoulPlayerFilterPacket::whitelistEnabled,
                    ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8),
                    SyncMachineSoulPlayerFilterPacket::whitelist,
                    ByteBufCodecs.INT,
                    SyncMachineSoulPlayerFilterPacket::modeId,
                    ByteBufCodecs.INT,
                    SyncMachineSoulPlayerFilterPacket::mask,
                    SyncMachineSoulPlayerFilterPacket::new
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static final Logger LOGGER = LoggerFactory.getLogger("cbc_autotarget/SyncPlayerFilter");

    public static void handle(SyncMachineSoulPlayerFilterPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            LOGGER.info("[SyncPlayerFilter] RECEIVED pos={} whitelistEnabled={} whitelistSize={} modeId={} mask={} currentScreen={}",
                    packet.pos(), packet.whitelistEnabled(), packet.whitelist().size(),
                    packet.modeId(), packet.mask(),
                    mc.screen != null ? mc.screen.getClass().getSimpleName() : "null");

            boolean appliedToWhitelistScreen = false;
            boolean appliedToTargetScreen = false;

            if (mc.screen instanceof MachineSoulPlayerFilterScreen screen) {
                boolean posMatches = screen.getBlockPos().equals(packet.pos());
                LOGGER.info("[SyncPlayerFilter] screen is MachineSoulPlayerFilterScreen, screenPos={} packetPos={} posMatches={}",
                        screen.getBlockPos(), packet.pos(), posMatches);
                if (posMatches) {
                    screen.applySync(
                            packet.whitelistEnabled(),
                            packet.whitelist(),
                            WhitelistMode.fromId(packet.modeId())
                    );
                    appliedToWhitelistScreen = true;
                }
            }
            if (mc.screen instanceof MachineSoulTargetScreen screen) {
                boolean posMatches = screen.getBlockPos().equals(packet.pos());
                LOGGER.info("[SyncPlayerFilter] screen is MachineSoulTargetScreen, screenPos={} packetPos={} posMatches={}",
                        screen.getBlockPos(), packet.pos(), posMatches);
                if (posMatches) {
                    screen.applyMaskSync(packet.mask());
                    appliedToTargetScreen = true;
                }
            }

            if (!appliedToWhitelistScreen && !appliedToTargetScreen) {
                // ВАЖНО: если ни один из открытых экранов не совпал по типу/позиции,
                // пакет применяется молча в никуда. Это не страшно для NBT (сервер уже
                // сохранил данные), но означает, что ТЕКУЩИЙ GUI (если он вообще открыт)
                // не увидит новое значение маски/вайтлиста, пока не будет переоткрыт.
                LOGGER.warn("[SyncPlayerFilter] DROPPED pos={} mask={} — no matching open screen (currentScreen={}). "
                                + "Data IS saved server-side, but no client screen updated its widgets.",
                        packet.pos(), packet.mask(),
                        mc.screen != null ? mc.screen.getClass().getSimpleName() : "null");
            }
        });
    }
}
