package com.yourname.cbcautotarget.network;

import com.yourname.cbcautotarget.CBCAutoTarget;
import com.yourname.cbcautotarget.blockentity.ControllerBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Сервер → Клиент. Отправляется при открытии GUI.
 *
 * ВАЖНО: replaceWhitelist() полностью заменяет список на клиенте,
 * что исправляет баг с «призраками» удалённых ников.
 */
public record SyncWhitelistPacket(
        BlockPos pos,
        boolean whitelistEnabled,
        List<String> whitelist
) implements CustomPacketPayload {

    public static final Type<SyncWhitelistPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cbc_autotarget", "sync_whitelist"));

    public static final StreamCodec<FriendlyByteBuf, SyncWhitelistPacket> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,
                    SyncWhitelistPacket::pos,
                    ByteBufCodecs.BOOL,
                    SyncWhitelistPacket::whitelistEnabled,
                    ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8),
                    SyncWhitelistPacket::whitelist,
                    SyncWhitelistPacket::new
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SyncWhitelistPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var level = Minecraft.getInstance().level;
            if (level == null) return;
            var be = level.getBlockEntity(packet.pos());
            if (!(be instanceof ControllerBlockEntity controller)) {
               /* CBCAutoTarget.LOGGER.warn("[WL SYNC] No ControllerBE at {}", packet.pos());*/
                return;
            }
            var filter = controller.getFilterData();
            filter.setWhitelistEnabled(packet.whitelistEnabled());
            // replaceWhitelist — ПОЛНАЯ замена, не добавление.
            // Это ключевое исправление бага с "призраками" ников.
            filter.replaceWhitelist(packet.whitelist());
           /* CBCAutoTarget.LOGGER.debug("[WL SYNC] {} entries, enabled={}",
                    packet.whitelist().size(), packet.whitelistEnabled());*/
        });
    }
}
