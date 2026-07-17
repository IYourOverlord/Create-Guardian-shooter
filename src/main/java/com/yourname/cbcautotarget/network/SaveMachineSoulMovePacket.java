package com.yourname.cbcautotarget.network;

import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.CommandRole;
import com.yourname.cbcautotarget.menu.MachineSoulMoveMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.LinkedHashMap;
import java.util.Map;

/** Клиент → Сервер: сохранить freq-слоты движения (вкладка Move). */
public record SaveMachineSoulMovePacket(
        BlockPos pos,
        Map<CommandRole, ItemStack[]> slots
) implements CustomPacketPayload {

    public static final Type<SaveMachineSoulMovePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cbc_autotarget", "save_soul_move"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SaveMachineSoulMovePacket> CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBlockPos(p.pos());
                        for (CommandRole role : MachineSoulMoveMenu.MOVE_ROLES) {
                            ItemStack[] pair = p.slots().get(role);
                            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, pair != null ? pair[0] : ItemStack.EMPTY);
                            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, pair != null ? pair[1] : ItemStack.EMPTY);
                        }
                    },
                    buf -> {
                        BlockPos pos = buf.readBlockPos();
                        Map<CommandRole, ItemStack[]> map = new LinkedHashMap<>();
                        for (CommandRole role : MachineSoulMoveMenu.MOVE_ROLES) {
                            ItemStack f0 = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                            ItemStack f1 = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                            map.put(role, new ItemStack[]{f0, f1});
                        }
                        return new SaveMachineSoulMovePacket(pos, map);
                    }
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SaveMachineSoulMovePacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            BlockEntity be = SaveMachineSoulConfigPacket.findBE(sp, pkt.pos());
            if (!(be instanceof MachineSoulBlockEntity soul)) return;
            for (CommandRole role : MachineSoulMoveMenu.MOVE_ROLES) {
                ItemStack[] pair = pkt.slots().get(role);
                if (pair != null) soul.assignSlot(role, pair[0], pair[1]);
            }
            soul.onPlayerSaved(sp);
        });
    }
}
