package com.yourname.cbcautotarget.event;

import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.CommandRole;
import com.yourname.cbcautotarget.compat.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Привязка прямого редстоун-выхода Machine Soul: после нажатия «+» на вкладке Move
 * следующий ПКМ пустой рукой по стороне любого блока добавляет (или снимает) эту сторону
 * как выход выбранной роли движения.
 */
@EventBusSubscriber(modid = "cbc_autotarget", bus = EventBusSubscriber.Bus.GAME)
public class SoulDirectBindHandler {

    private static final long TIMEOUT_TICKS = 1200L;

    private record Session(BlockPos soulPos, CommandRole role, long expiresAt) { }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    public static void begin(ServerPlayer sp, MachineSoulBlockEntity soul, CommandRole role) {
        SESSIONS.put(sp.getUUID(), new Session(soul.getBlockPos(), role, sp.level().getGameTime() + TIMEOUT_TICKS));
        sp.displayClientMessage(Component.translatable("gui.cbc_autotarget.soul.direct.start", roleName(role)), true);
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;

        Session session = SESSIONS.get(sp.getUUID());
        if (session == null) return;
        if (sp.level().getGameTime() > session.expiresAt()) {
            SESSIONS.remove(sp.getUUID());
            return;
        }
        Direction face = event.getFace();
        if (face == null || !sp.getMainHandItem().isEmpty()) return;

        SESSIONS.remove(sp.getUUID());
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);

        BlockPos clicked = event.getPos();
        BlockEntity be = event.getLevel().getBlockEntity(session.soulPos());
        if (be == null && SableCompat.isAvailable()) {
            be = SableCompat.findBEInAnyLevel(sp.getServer(), session.soulPos());
        }
        if (!(be instanceof MachineSoulBlockEntity soul)
                || clicked.equals(soul.getBlockPos())
                || (soul.isCreativeLocked() && sp.gameMode.getGameModeForPlayer() != GameType.CREATIVE)) {
            sp.displayClientMessage(Component.translatable("gui.cbc_autotarget.soul.direct.cancelled"), true);
            return;
        }

        boolean added = soul.toggleDirectTarget(session.role(), clicked, face);
        sp.displayClientMessage(Component.translatable(
                added ? "gui.cbc_autotarget.soul.direct.bound" : "gui.cbc_autotarget.soul.direct.unbound",
                roleName(session.role())), true);
    }

    private static Component roleName(CommandRole role) {
        return Component.translatable("gui.cbc_autotarget.soul.role."
                + role.name().substring("MOVE_".length()).toLowerCase(Locale.ROOT));
    }
}
