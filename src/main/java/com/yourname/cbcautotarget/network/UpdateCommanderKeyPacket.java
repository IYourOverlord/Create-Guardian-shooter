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
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Клиент → сервер: игрок подтверждает новый ключ альянса для командера.
 * Отправляется при нажатии кнопки Apply в GUI командера.
 */
public record UpdateCommanderKeyPacket(BlockPos pos, String allianceKey) implements CustomPacketPayload {

    private static final Logger LOGGER = LoggerFactory.getLogger("cbc_autotarget/UpdateCommanderKey");

    public static final Type<UpdateCommanderKeyPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cbc_autotarget", "update_commander_key"));

    public static final StreamCodec<FriendlyByteBuf, UpdateCommanderKeyPacket> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,    UpdateCommanderKeyPacket::pos,
                    ByteBufCodecs.STRING_UTF8, UpdateCommanderKeyPacket::allianceKey,
                    UpdateCommanderKeyPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(UpdateCommanderKeyPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            // Ключ не должен быть длиннее 64 символов
            String sanitized = packet.allianceKey().strip();
            if (sanitized.length() > 64) sanitized = sanitized.substring(0, 64);

            BlockEntity be = sp.serverLevel().getBlockEntity(packet.pos());
            if (be == null && SableCompat.isAvailable()) {
                be = SableCompat.findBlockEntityInSubLevels(sp.serverLevel(), packet.pos());
            }

            if (!(be instanceof CommanderBlockEntity commander)) {
                LOGGER.warn("[UpdateKey] No CommanderBlockEntity found at {}", packet.pos());
                return;
            }

            double distSq = sp.blockPosition().distSqr(packet.pos());
            boolean onShip = SableCompat.isAvailable()
                    && SableCompat.getSubLevelForBlock(sp.serverLevel(), packet.pos()) != null;
            if (!onShip && distSq > 64 * 64) {
                LOGGER.warn("[UpdateKey] Player too far (distSq={}), aborting", distSq);
                return;
            }

            LOGGER.info("[UpdateKey] Setting allianceKey='{}' on commander at {}", sanitized, packet.pos());
            commander.setAllianceKey(sanitized);
        });
    }
}
