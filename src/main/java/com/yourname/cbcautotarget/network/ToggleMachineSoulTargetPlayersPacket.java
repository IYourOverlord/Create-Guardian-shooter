package com.yourname.cbcautotarget.network;

import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity;
import com.yourname.cbcautotarget.compat.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Клиент → Сервер: переключить таргетинг игроков у Machine Soul
 * (кнопка на вкладке Target).
 */
public record ToggleMachineSoulTargetPlayersPacket(BlockPos pos) implements CustomPacketPayload {

    private static final Logger LOGGER = LoggerFactory.getLogger("cbc_autotarget/ToggleTargetPlayers");

    public static final Type<ToggleMachineSoulTargetPlayersPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cbc_autotarget", "toggle_soul_target_players"));

    public static final StreamCodec<FriendlyByteBuf, ToggleMachineSoulTargetPlayersPacket> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ToggleMachineSoulTargetPlayersPacket::pos,
                    ToggleMachineSoulTargetPlayersPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(ToggleMachineSoulTargetPlayersPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                LOGGER.warn("[ToggleTargetPlayers] Player is not ServerPlayer, aborting");
                return;
            }

            BlockEntity be = sp.serverLevel().getBlockEntity(packet.pos());
            LOGGER.info("[ToggleTargetPlayers] Main level lookup: {}", be == null ? "null" : be.getClass().getSimpleName());

            if (be == null && SableCompat.isAvailable()) {
                be = SableCompat.findBlockEntityInSubLevels(sp.serverLevel(), packet.pos());
                LOGGER.info("[ToggleTargetPlayers] SubLevel lookup result: {}",
                        be == null ? "null" : be.getClass().getSimpleName() + " at " + be.getBlockPos());
            }

            if (!(be instanceof MachineSoulBlockEntity soul)) {
                LOGGER.warn("[ToggleTargetPlayers] No MachineSoulBlockEntity found at {}", packet.pos());
                return;
            }

            boolean onShip = SableCompat.isAvailable()
                    && SableCompat.getSubLevelForBlock(sp.serverLevel(), packet.pos()) != null;
            double distSq = sp.blockPosition().distSqr(packet.pos());

            if (!onShip && distSq > 64 * 64) {
                LOGGER.warn("[ToggleTargetPlayers] Player too far (distSq={}), aborting", distSq);
                return;
            }

            soul.setTargetPlayers(!soul.isTargetPlayers());
            LOGGER.info("[ToggleTargetPlayers] pos={} -> {}", packet.pos(), soul.isTargetPlayers());
        });
    }
}
