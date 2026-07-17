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
 * Клиент → Сервер: переключить режим "Только на физической конструкции"
 * у Machine Soul (кнопка на главной странице GUI, под кнопкой поиска цели).
 *
 * Когда этот режим включён, обычная кнопка активации (targetSearchActive)
 * полностью игнорируется в serverTick — работа блока зависит только от
 * того, находится ли он сейчас на Sable sub-level.
 */
public record ToggleMachineSoulSubLevelOnlyPacket(BlockPos pos) implements CustomPacketPayload {

    private static final Logger LOGGER = LoggerFactory.getLogger("cbc_autotarget/ToggleSoulSubLevelOnly");

    public static final Type<ToggleMachineSoulSubLevelOnlyPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cbc_autotarget", "toggle_soul_sublevel_only"));

    public static final StreamCodec<FriendlyByteBuf, ToggleMachineSoulSubLevelOnlyPacket> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ToggleMachineSoulSubLevelOnlyPacket::pos,
                    ToggleMachineSoulSubLevelOnlyPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(ToggleMachineSoulSubLevelOnlyPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                LOGGER.warn("[ToggleSubLevelOnly] Player is not ServerPlayer, aborting");
                return;
            }

            BlockEntity be = sp.serverLevel().getBlockEntity(packet.pos());
            LOGGER.info("[ToggleSubLevelOnly] Main level lookup: {}", be == null ? "null" : be.getClass().getSimpleName());

            if (be == null && SableCompat.isAvailable()) {
                be = SableCompat.findBlockEntityInSubLevels(sp.serverLevel(), packet.pos());
                LOGGER.info("[ToggleSubLevelOnly] SubLevel lookup result: {}",
                        be == null ? "null" : be.getClass().getSimpleName() + " at " + be.getBlockPos());
            }

            if (!(be instanceof MachineSoulBlockEntity soul)) {
                LOGGER.warn("[ToggleSubLevelOnly] No MachineSoulBlockEntity found at {}", packet.pos());
                return;
            }

            boolean onShip = SableCompat.isAvailable()
                    && SableCompat.getSubLevelForBlock(sp.serverLevel(), packet.pos()) != null;
            double distSq = sp.blockPosition().distSqr(packet.pos());

            if (!onShip && distSq > 64 * 64) {
                LOGGER.warn("[ToggleSubLevelOnly] Player too far (distSq={}), aborting", distSq);
                return;
            }

            soul.setRequireSubLevel(!soul.isRequireSubLevel());
            LOGGER.info("[ToggleSubLevelOnly] pos={} -> {}", packet.pos(), soul.isRequireSubLevel());
        });
    }
}
