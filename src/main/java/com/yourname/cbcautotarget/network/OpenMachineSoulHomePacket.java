package com.yourname.cbcautotarget.network;

import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity;
import com.yourname.cbcautotarget.menu.MachineSoulHomeMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Сервер → Клиент: открыть главную страницу (хаб) Machine Soul.
 * Используется при клике на блок и при нажатии кнопки "←" в любой вкладке.
 */
public record OpenMachineSoulHomePacket(BlockPos pos) implements CustomPacketPayload {

    public static final Type<OpenMachineSoulHomePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cbc_autotarget", "open_machine_soul_home"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenMachineSoulHomePacket> CODEC =
            StreamCodec.of(
                    (buf, pkt) -> buf.writeBlockPos(pkt.pos()),
                    buf -> new OpenMachineSoulHomePacket(buf.readBlockPos())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** Вызывается сервером при открытии блока игроком. */
    public static void openFor(ServerPlayer sp, MachineSoulBlockEntity soul) {
        soul.onPlayerOpened(sp);
        BlockPos pos = soul.getBlockPos();
        sp.openMenu(new MenuProvider() {
            @Override public Component getDisplayName() { return Component.empty(); }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                return new MachineSoulHomeMenu(id, inv, soul);
            }
        }, buf -> {
            buf.writeBlockPos(pos);
            buf.writeBoolean(soul.isTargetSearchActive());
            buf.writeBoolean(soul.isRequireSubLevel());
        });
    }

    /** Обработчик на клиенте — не нужен, openMenu сам открывает экран через ClientSetup. */
    public static void handle(OpenMachineSoulHomePacket pkt, IPayloadContext ctx) { }
}
