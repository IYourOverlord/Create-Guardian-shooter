package com.yourname.cbcautotarget.network;

import com.yourname.cbcautotarget.blockentity.CommanderBlockEntity;
import com.yourname.cbcautotarget.compat.SableCompat;
import com.yourname.cbcautotarget.filter.TargetCategory;
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

public record UpdateCommanderFilterPacket(BlockPos pos, int mask) implements CustomPacketPayload {

    private static final Logger LOGGER = LoggerFactory.getLogger("cbc_autotarget/UpdateFilter");

    public static final Type<UpdateCommanderFilterPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cbc_autotarget", "update_commander_filter"));

    public static final StreamCodec<FriendlyByteBuf, UpdateCommanderFilterPacket> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,        UpdateCommanderFilterPacket::pos,
                    ByteBufCodecs.INT,            UpdateCommanderFilterPacket::mask,
                    UpdateCommanderFilterPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(UpdateCommanderFilterPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            LOGGER.info("[Filter] Received: pos={} mask={}", packet.pos(), Integer.toBinaryString(packet.mask()));

            if (!(ctx.player() instanceof ServerPlayer sp)) {
                LOGGER.warn("[Filter] Not a ServerPlayer, aborting");
                return;
            }

            int sanitized = packet.mask() & TargetCategory.ALL_MASK;
            LOGGER.info("[Filter] Sanitized mask={}", Integer.toBinaryString(sanitized));

            BlockEntity be = sp.serverLevel().getBlockEntity(packet.pos());
            LOGGER.info("[Filter] Main level lookup: {}", be == null ? "null" : be.getClass().getSimpleName());

            if (be == null && SableCompat.isAvailable()) {
                LOGGER.info("[Filter] Sable available, searching SubLevels...");
                be = SableCompat.findBlockEntityInSubLevels(sp.serverLevel(), packet.pos());
                LOGGER.info("[Filter] SubLevel lookup: {}", be == null ? "null" : be.getClass().getSimpleName() + " at " + be.getBlockPos());
            }

            if (!(be instanceof CommanderBlockEntity commander)) {
                LOGGER.warn("[Filter] No CommanderBlockEntity found at {}", packet.pos());
                return;
            }

            double distSq = sp.blockPosition().distSqr(packet.pos());
            boolean onShip = SableCompat.isAvailable()
                    && SableCompat.getSubLevelForBlock(sp.serverLevel(), packet.pos()) != null;
            LOGGER.info("[Filter] Found commander. onShip={} distSq={}", onShip, distSq);

            if (!onShip && distSq > 64 * 64) {
                LOGGER.warn("[Filter] Player too far (distSq={}), aborting", distSq);
                return;
            }

            LOGGER.info("[Filter] Applying mask={} to commander at {}", Integer.toBinaryString(sanitized), commander.getBlockPos());
            commander.setFilterMask(sanitized);
            LOGGER.info("[Filter] Done. Current mask={}", Integer.toBinaryString(commander.getFilterMask()));
        });
    }
}