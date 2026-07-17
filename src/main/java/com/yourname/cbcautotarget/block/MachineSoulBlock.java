package com.yourname.cbcautotarget.block;

import com.mojang.serialization.MapCodec;
import com.yourname.cbcautotarget.ModBlockEntities;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity;
import com.yourname.cbcautotarget.network.OpenMachineSoulGuiPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

/**
 * MachineSoulBlock — «Душа машины».
 *
 * FACING указывает «передний» борт блока (красная грань).
 * При установке блок смотрит в сторону, противоположную игроку
 * (то есть «передом» от игрока — как большинство направленных блоков Create).
 */
public class MachineSoulBlock extends BaseEntityBlock {

    public static final MapCodec<MachineSoulBlock> CODEC = simpleCodec(MachineSoulBlock::new);

    /** Горизонтальное направление «перёд» блока. */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public MachineSoulBlock(Properties props) {
        super(props);
        // По умолчанию смотрит на юг
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH));
    }

    @Override
    public MapCodec<MachineSoulBlock> codec() { return CODEC; }

    // ── Blockstate ────────────────────────────────────────────────────────────

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /**
     * При установке блок «смотрит» на игрока (перед = сторона к игроку).
     * Это удобно: положил блок — красная грань сразу указывает на тебя,
     * и ты видишь, где у него перёд.
     */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        // getHorizontalDirection() — направление взгляда игрока.
        // Передняя (красная) грань должна смотреть НА игрока,
        // т.е. в сторону, ПРОТИВОПОЛОЖНУЮ взгляду игрока.
        // opposite(направление взгляда) = направление от блока к игроку.
        return defaultBlockState().setValue(FACING,
                ctx.getHorizontalDirection().getOpposite());
    }

    // ── BlockEntity ───────────────────────────────────────────────────────────

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MachineSoulBlockEntity(ModBlockEntities.MACHINE_SOUL.get(), pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return createTickerHelper(type, ModBlockEntities.MACHINE_SOUL.get(),
                MachineSoulBlockEntity::serverTick);
    }

    /**
     * Прямой клик по блоку MachineSoul (без предмета в руке) — открывает GUI.
     * Клик по Redstone Link с Soul в руке обрабатывается отдельно
     * в MachineSoulLinkInteractionHandler.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level,
                                               BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof MachineSoulBlockEntity soul) {
                soul.onPlayerOpened(sp);
                OpenMachineSoulGuiPacket.openFor(sp, soul);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }
}