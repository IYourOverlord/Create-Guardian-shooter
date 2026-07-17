package com.yourname.cbcautotarget.network;

import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.CommandRole;
import com.yourname.cbcautotarget.client.MachineSoulScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.EnumMap;
import java.util.Map;

/**
 * Сервер → Клиент.
 * Отправляется каждые 10 тиков пока игрок смотрит в GUI MachineSoul.
 * Содержит: для каждой роли — найден ли линк с нужной частотой в радиусе.
 */
public record SyncMachineSoulStatusPacket(
        Map<CommandRole, Boolean> linkFound
) implements CustomPacketPayload {

    public static final Type<SyncMachineSoulStatusPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    "cbc_autotarget", "sync_machine_soul_status"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncMachineSoulStatusPacket> CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        for (CommandRole role : CommandRole.values()) {
                            buf.writeBoolean(pkt.linkFound().getOrDefault(role, false));
                        }
                    },
                    buf -> {
                        Map<CommandRole, Boolean> map = new EnumMap<>(CommandRole.class);
                        for (CommandRole role : CommandRole.values()) {
                            map.put(role, buf.readBoolean());
                        }
                        return new SyncMachineSoulStatusPacket(map);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SyncMachineSoulStatusPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof MachineSoulScreen screen) {
                screen.updateLinkStatus(packet.linkFound());
            }
        });
    }
}
