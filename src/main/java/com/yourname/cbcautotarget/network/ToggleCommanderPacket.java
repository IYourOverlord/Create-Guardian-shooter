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

public record ToggleCommanderPacket(BlockPos pos, boolean activate) implements CustomPacketPayload {

    private static final Logger LOGGER = LoggerFactory.getLogger("cbc_autotarget/ToggleCommander");

    public static final Type<ToggleCommanderPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cbc_autotarget", "toggle_commander"));

    public static final StreamCodec<FriendlyByteBuf, ToggleCommanderPacket> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ToggleCommanderPacket::pos,
                    ByteBufCodecs.BOOL,    ToggleCommanderPacket::activate,
                    ToggleCommanderPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(ToggleCommanderPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            LOGGER.info("[Toggle] Received: pos={} activate={}", packet.pos(), packet.activate());

            if (!(ctx.player() instanceof ServerPlayer sp)) {
                LOGGER.warn("[Toggle] Player is not ServerPlayer, aborting");
                return;
            }

            BlockEntity be = sp.serverLevel().getBlockEntity(packet.pos());
            LOGGER.info("[Toggle] Main level lookup: {}", be == null ? "null" : be.getClass().getSimpleName());

            if (be == null && SableCompat.isAvailable()) {
                LOGGER.info("[Toggle] Sable available, searching SubLevels...");
                be = SableCompat.findBlockEntityInSubLevels(sp.serverLevel(), packet.pos());
                LOGGER.info("[Toggle] SubLevel lookup result: {}", be == null ? "null" : be.getClass().getSimpleName() + " at " + be.getBlockPos());
            }

            if (!(be instanceof CommanderBlockEntity commander)) {
                LOGGER.warn("[Toggle] No CommanderBlockEntity found at {}", packet.pos());
                return;
            }

            double distSq = sp.blockPosition().distSqr(packet.pos());
            boolean onShip = SableCompat.isAvailable()
                    && SableCompat.getSubLevelForBlock(sp.serverLevel(), packet.pos()) != null;
            LOGGER.info("[Toggle] Found commander. onShip={} distSq={}", onShip, distSq);

            if (!onShip && distSq > 64 * 64) {
                LOGGER.warn("[Toggle] Player too far (distSq={}), aborting", distSq);
                return;
            }

            LOGGER.info("[Toggle] Calling broadcast{}...", packet.activate() ? "Activate" : "Deactivate");
            if (packet.activate()) {
                commander.broadcastActivate();
            } else {
                commander.broadcastDeactivate();
            }
        });
    }
}