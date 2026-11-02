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
 * Клиент → Сервер: переключить «Structure Destroy» у Machine Soul (кнопка на вкладке NPC).
 */
public record ToggleMachineSoulStructureDestroyPacket(BlockPos pos) implements CustomPacketPayload {

    private static final Logger LOGGER = LoggerFactory.getLogger("cbc_autotarget/ToggleSoulStructureDestroy");

    public static final Type<ToggleMachineSoulStructureDestroyPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cbc_autotarget", "toggle_soul_structure_destroy"));

    public static final StreamCodec<FriendlyByteBuf, ToggleMachineSoulStructureDestroyPacket> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ToggleMachineSoulStructureDestroyPacket::pos,
                    ToggleMachineSoulStructureDestroyPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(ToggleMachineSoulStructureDestroyPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                LOGGER.warn("[ToggleStructureDestroy] Player is not ServerPlayer, aborting");
                return;
            }

            BlockEntity be = sp.serverLevel().getBlockEntity(packet.pos());
            if (be == null && SableCompat.isAvailable()) {
                be = SableCompat.findBlockEntityInSubLevels(sp.serverLevel(), packet.pos());
            }

            if (!(be instanceof MachineSoulBlockEntity soul)) {
                LOGGER.warn("[ToggleStructureDestroy] No MachineSoulBlockEntity found at {}", packet.pos());
                return;
            }

            boolean onShip = SableCompat.isAvailable()
                    && SableCompat.getSubLevelForBlock(sp.serverLevel(), packet.pos()) != null;
            double distSq = sp.blockPosition().distSqr(packet.pos());

            if (!onShip && distSq > 64 * 64) {
                LOGGER.warn("[ToggleStructureDestroy] Player too far (distSq={}), aborting", distSq);
                return;
            }

            if (SaveMachineSoulConfigPacket.isSoulLocked(soul, sp)) {
                LOGGER.warn("[ToggleStructureDestroy] Block is creativeLocked, player {} denied", sp.getGameProfile().getName());
                return;
            }
            soul.setDestroyStructure(!soul.isDestroyStructure());
            LOGGER.info("[ToggleStructureDestroy] pos={} -> {}", packet.pos(), soul.isDestroyStructure());
        });
    }
}