package com.yourname.cbcautotarget.network;

import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.CommandRole;
import com.yourname.cbcautotarget.compat.SableCompat;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;

/**
 * Клиент → Сервер: сохранить конфигурацию Machine Soul.
 * Содержит: позицию блока, радиус обнаружения, и пары ItemStack для каждой роли.
 */
public record SaveMachineSoulConfigPacket(
        BlockPos pos,
        int detectionRadius,
        Map<CommandRole, ItemStack[]> slots
) implements CustomPacketPayload {

    private static final Logger LOGGER = LoggerFactory.getLogger("cbc_autotarget/SaveConfig");

    public static final Type<SaveMachineSoulConfigPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    "cbc_autotarget", "save_machine_soul_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SaveMachineSoulConfigPacket> CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeBlockPos(pkt.pos());
                        buf.writeInt(pkt.detectionRadius());
                        for (CommandRole role : CommandRole.values()) {
                            ItemStack[] pair = pkt.slots().get(role);
                            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, pair != null ? pair[0] : ItemStack.EMPTY);
                            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, pair != null ? pair[1] : ItemStack.EMPTY);
                        }
                    },
                    buf -> {
                        BlockPos pos   = buf.readBlockPos();
                        int radius     = buf.readInt();
                        Map<CommandRole, ItemStack[]> map = new EnumMap<>(CommandRole.class);
                        for (CommandRole role : CommandRole.values()) {
                            ItemStack f0 = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                            ItemStack f1 = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                            map.put(role, new ItemStack[]{f0, f1});
                        }
                        return new SaveMachineSoulConfigPacket(pos, radius, map);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SaveMachineSoulConfigPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            BlockEntity be = findBE(sp, packet.pos());
            LOGGER.info("[SaveConfig] pos={} found={}", packet.pos(),
                    be != null ? be.getClass().getSimpleName() : "null");

            if (!(be instanceof MachineSoulBlockEntity soul)) return;

            soul.setDetectionRadius(packet.detectionRadius());

            for (CommandRole role : CommandRole.values()) {
                ItemStack[] pair = packet.slots().get(role);
                if (pair != null) soul.assignSlot(role, pair[0], pair[1]);
            }
            soul.onPlayerSaved(sp);
            LOGGER.info("[SaveConfig] SAVED to {} radius={}", soul.getBlockPos(), packet.detectionRadius());
        });
    }

    static BlockEntity findBE(ServerPlayer sp, BlockPos pos) {
        ServerLevel playerLevel = sp.serverLevel();
        BlockEntity be = playerLevel.getBlockEntity(pos);
        if (be != null) {
            boolean isSubLevel = SableCompat.isAvailable()
                    && SubLevelContainer.getContainer(playerLevel) != null;
            if (!isSubLevel && sp.blockPosition().distSqr(pos) > 64 * 64) return null;
            return be;
        }
        if (SableCompat.isAvailable()) {
            be = SableCompat.findBEInAnyLevel(sp.getServer(), pos);
            if (be != null) return be;
        }
        return null;
    }
}
