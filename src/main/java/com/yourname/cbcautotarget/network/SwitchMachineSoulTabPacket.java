package com.yourname.cbcautotarget.network;

import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.CommandRole;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.Tab;
import com.yourname.cbcautotarget.menu.MachineSoulMoveMenu;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Клиент → Сервер: переключить вкладку GUI Machine Soul.
 * Сервер открывает соответствующее меню. Данные НЕ сохраняются (только Save).
 */
public record SwitchMachineSoulTabPacket(BlockPos pos, Tab targetTab) implements CustomPacketPayload {

    public static final Type<SwitchMachineSoulTabPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cbc_autotarget", "switch_machine_soul_tab"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SwitchMachineSoulTabPacket> CODEC =
            StreamCodec.of(
                    (buf, pkt) -> { buf.writeBlockPos(pkt.pos()); buf.writeEnum(pkt.targetTab()); },
                    buf -> new SwitchMachineSoulTabPacket(buf.readBlockPos(), buf.readEnum(Tab.class))
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static final Logger LOGGER = LoggerFactory.getLogger("cbc_autotarget/SwitchTab");

    public static void handle(SwitchMachineSoulTabPacket pkt, IPayloadContext ctx) {
        LOGGER.info("[SwitchTab] RECEIVED pos={} targetTab={}", pkt.pos(), pkt.targetTab());
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                LOGGER.warn("[SwitchTab] ABORT pos={} — ctx.player() not ServerPlayer", pkt.pos());
                return;
            }
            BlockEntity rawBe = SaveMachineSoulConfigPacket.findBE(sp, pkt.pos());
            if (!(rawBe instanceof MachineSoulBlockEntity soul)) {
                LOGGER.warn("[SwitchTab] ABORT pos={} — findBE returned {}",
                        pkt.pos(), rawBe != null ? rawBe.getClass().getSimpleName() : "null");
                return;
            }
            switch (pkt.targetTab()) {
                case VISION   -> openVision(sp, soul);
                case MOVEMENT -> openMove(sp, soul);
                case ACTION   -> openAction(sp, soul);
                case TARGET   -> openTarget(sp, soul);
            }
        });
    }

    public static void openVision(ServerPlayer sp, MachineSoulBlockEntity soul) {
        sp.openMenu(simpleProvider(soul, Tab.VISION), buf -> {
            buf.writeBlockPos(soul.getBlockPos());
            buf.writeInt(soul.getDetectionRadius());
            buf.writeInt(soul.getKeepDistance());
            buf.writeInt(soul.getStandStillDistance());
        });
    }

    public static void openMove(ServerPlayer sp, MachineSoulBlockEntity soul) {
        sp.openMenu(simpleProvider(soul, Tab.MOVEMENT), buf -> {
            buf.writeBlockPos(soul.getBlockPos());
            for (CommandRole role : MachineSoulMoveMenu.MOVE_ROLES) {
                var s = soul.getSlot(role);
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, s.freq0);
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, s.freq1);
            }
        });
    }

    public static void openAction(ServerPlayer sp, MachineSoulBlockEntity soul) {
        sp.openMenu(simpleProvider(soul, Tab.ACTION), buf -> {
            buf.writeBlockPos(soul.getBlockPos());
            var s = soul.getSlot(CommandRole.FIRE);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, s.freq0);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, s.freq1);
        });
    }

    public static void openTarget(ServerPlayer sp, MachineSoulBlockEntity soul) {
        var filter = soul.getPlayerFilterData();
        LOGGER.info("[SwitchTab] openTarget pos={} soul.mask(fromBE)={} whitelistEnabled={} whitelistSize={} whitelistMode={} friendlyCommanders={}",
                soul.getBlockPos(), filter.getMask(), filter.isWhitelistEnabled(),
                filter.getWhitelist().size(), soul.getWhitelistMode(),
                soul.getCommanderFilterData().getFriendlyIds().size());
        sp.openMenu(simpleProvider(soul, Tab.TARGET), buf -> {
            buf.writeBlockPos(soul.getBlockPos());
            buf.writeBoolean(soul.isTargetPlayers());
            // Фильтр игроков (вайтлист)
            buf.writeBoolean(filter.isWhitelistEnabled());
            net.minecraft.network.codec.ByteBufCodecs.collection(
                java.util.ArrayList::new,
                net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8
            ).encode(buf, new java.util.ArrayList<>(filter.getWhitelist()));
            buf.writeInt(soul.getWhitelistMode().id());
            // Маска категорий целей (Hostile/Passive/Players/Enemy Commanders).
            // РАНЬШЕ ОТСУТСТВОВАЛА: без неё клиентское меню всегда создавало
            // TargetFilterData() с маской по умолчанию (все категории включены),
            // и при каждом повторном открытии GUI чекбоксы отображались как
            // включённые независимо от того, что реально было сохранено и
            // применялось на сервере.
            buf.writeInt(filter.getMask());
            LOGGER.info("[SwitchTab] openTarget pos={} WROTE mask={} into menu buffer", soul.getBlockPos(), filter.getMask());
            // Фильтр дружественных командеров
            net.minecraft.network.codec.ByteBufCodecs.collection(
                java.util.ArrayList::new,
                net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8
            ).encode(buf, new java.util.ArrayList<>(soul.getCommanderFilterData().getFriendlyIds()));
        });
    }

    /** Создаёт анонимный MenuProvider для openMenu, делегируя createMenu в BE. */
    private static MenuProvider simpleProvider(MachineSoulBlockEntity soul, Tab tab) {
        return new MenuProvider() {
            @Override public Component getDisplayName() { return Component.empty(); }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                return switch (tab) {
                    case VISION   -> new com.yourname.cbcautotarget.menu.MachineSoulVisionMenu(id, inv, soul);
                    case MOVEMENT -> new MachineSoulMoveMenu(id, inv, soul);
                    case ACTION   -> new com.yourname.cbcautotarget.menu.MachineSoulActionMenu(id, inv, soul);
                    case TARGET   -> new com.yourname.cbcautotarget.menu.MachineSoulTargetMenu(id, inv, soul);
                };
            }
        };
    }
}
