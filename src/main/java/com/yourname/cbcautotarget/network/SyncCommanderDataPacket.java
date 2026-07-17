package com.yourname.cbcautotarget.network;

import com.yourname.cbcautotarget.blockentity.CommanderBlockEntity;
import com.yourname.cbcautotarget.menu.CommanderMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Сервер → клиент: синхронизирует полные данные командера при открытии GUI:
 * filterMask, вайтлист игроков и ключ альянса.
 */
public record SyncCommanderDataPacket(
        BlockPos pos,
        int filterMask,
        boolean whitelistEnabled,
        List<String> whitelist,
        String allianceKey
) implements CustomPacketPayload {

    public static final Type<SyncCommanderDataPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cbc_autotarget", "sync_commander_data"));

    public static final StreamCodec<FriendlyByteBuf, SyncCommanderDataPacket> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,
                    SyncCommanderDataPacket::pos,
                    ByteBufCodecs.INT,
                    SyncCommanderDataPacket::filterMask,
                    ByteBufCodecs.BOOL,
                    SyncCommanderDataPacket::whitelistEnabled,
                    ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8),
                    SyncCommanderDataPacket::whitelist,
                    ByteBufCodecs.STRING_UTF8,
                    SyncCommanderDataPacket::allianceKey,
                    SyncCommanderDataPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SyncCommanderDataPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();

            // ИСПРАВЛЕНИЕ: клиентский dummy BE создаётся с level=null и не регистрируется
            // в уровне, поэтому level.getBlockEntity(pos) его не найдёт.
            // Ищем BE через открытое меню — именно там хранится dummy для клиента.
            CommanderBlockEntity commander = null;

            if (mc.player != null && mc.player.containerMenu instanceof CommanderMenu menu
                    && menu.getBlockEntity().getBlockPos().equals(packet.pos())) {
                commander = menu.getBlockEntity();
            }

            // Фолбэк: если меню уже закрыто, но BE есть в мире (одиночная игра / реальный BE)
            if (commander == null && mc.level != null) {
                BlockEntity be = mc.level.getBlockEntity(packet.pos());
                if (be instanceof CommanderBlockEntity cbe) {
                    commander = cbe;
                }
            }

            if (commander == null) return;

            var filter = commander.getFilterData();
            commander.setFilterMaskClient(packet.filterMask());
            filter.setWhitelistEnabled(packet.whitelistEnabled());
            filter.replaceWhitelist(packet.whitelist());
            commander.setAllianceKey(packet.allianceKey());
        });
    }
}