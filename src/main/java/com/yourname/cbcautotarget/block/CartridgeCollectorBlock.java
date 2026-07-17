package com.yourname.cbcautotarget.block;

import com.mojang.serialization.MapCodec;
import com.yourname.cbcautotarget.ModBlockEntities;
import com.yourname.cbcautotarget.blockentity.CartridgeCollectorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

public class CartridgeCollectorBlock extends BaseEntityBlock {

    public static final MapCodec<CartridgeCollectorBlock> CODEC = simpleCodec(CartridgeCollectorBlock::new);

    /**
     * true  → хранилище непустое → модель раздутой рыбы
     * false → хранилище пустое   → модель сжатой рыбы
     */
    public static final BooleanProperty FULL = BlockStateProperties.ENABLED;

    public CartridgeCollectorBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(FULL, false));
    }

    @Override
    public MapCodec<CartridgeCollectorBlock> codec() { return CODEC; }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FULL);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CartridgeCollectorBlockEntity(ModBlockEntities.CARTRIDGE_COLLECTOR.get(), pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return createTickerHelper(type, ModBlockEntities.CARTRIDGE_COLLECTOR.get(),
                CartridgeCollectorBlockEntity::serverTick);
    }

    /**
     * ПКМ по блоку — выбросить все гильзы на землю.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof CartridgeCollectorBlockEntity collector) {
            collector.dumpContents(player);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        // Используем пользовательский рендерер, не MODEL и не INVISIBLE
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos,
                            BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof CartridgeCollectorBlockEntity collector) {
                collector.dropAllItems(level, pos);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
