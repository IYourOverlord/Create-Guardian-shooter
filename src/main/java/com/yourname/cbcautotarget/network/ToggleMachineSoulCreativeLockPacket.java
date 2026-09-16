package com.yourname.cbcautotarget.network;

import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity;
import com.yourname.cbcautotarget.compat.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Клиент → Сервер: переключить флаг блокировки редактирования (creativeLock)
 * у Machine Soul — кнопка «The NPC» на вкладке NPC.
 * Только игроки в GameType.CREATIVE могут переключать этот флаг.
 */
public record ToggleMachineSoulCreativeLockPacket(BlockPos pos) implements CustomPacketPayload {

    private static final Logger LOGGER = LoggerFactory.getLogger("cbc_autotarget/ToggleSoulCreativeLock");

    public static final Type<ToggleMachineSoulCreativeLockPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cbc_autotarget", "toggle_soul_creative_lock"));

    public static final StreamCodec<FriendlyByteBuf, ToggleMachineSoulCreativeLockPacket> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ToggleMachineSoulCreativeLockPacket::pos,
                    ToggleMachineSoulCreativeLockPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(ToggleMachineSoulCreativeLockPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                LOGGER.warn("[ToggleCreativeLock] Player is not ServerPlayer, aborting");
                return;
            }

            if (sp.gameMode.getGameModeForPlayer() != GameType.CREATIVE) {
                LOGGER.warn("[ToggleCreativeLock] Player {} is not in CREATIVE mode, denied", sp.getGameProfile().getName());
                return;
            }

            BlockEntity be = sp.serverLevel().getBlockEntity(packet.pos());
            if (be == null && SableCompat.isAvailable()) {
                be = SableCompat.findBlockEntityInSubLevels(sp.serverLevel(), packet.pos());
            }

            if (!(be instanceof MachineSoulBlockEntity soul)) {
                LOGGER.warn("[ToggleCreativeLock] No MachineSoulBlockEntity found at {}", packet.pos());
                return;
            }

            boolean onShip = SableCompat.isAvailable()
                    && SableCompat.getSubLevelForBlock(sp.serverLevel(), packet.pos()) != null;
            double distSq = sp.blockPosition().distSqr(packet.pos());
            if (!onShip && distSq > 64 * 64) {
                LOGGER.warn("[ToggleCreativeLock] Player too far (distSq={}), aborting", distSq);
                return;
            }

            soul.setCreativeLocked(!soul.isCreativeLocked());
            LOGGER.info("[ToggleCreativeLock] pos={} -> {}", packet.pos(), soul.isCreativeLocked());
        });
    }
}
