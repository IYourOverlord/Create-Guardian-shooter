package com.yourname.cbcautotarget.block;

import com.mojang.serialization.MapCodec;
import com.yourname.cbcautotarget.ModBlockEntities;
import com.yourname.cbcautotarget.blockentity.CommanderBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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

public class CommanderBlock extends BaseEntityBlock {

    public static final MapCodec<CommanderBlock> CODEC = simpleCodec(CommanderBlock::new);

    /** Хранит, был ли блок запитан на прошлом тике — чтобы не слать дублирующий сигнал. */
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    @Override
    public MapCodec<CommanderBlock> codec() { return CODEC; }

    public CommanderBlock(Properties props) {
        super(props);
        // По умолчанию — не запитан
        registerDefaultState(this.stateDefinition.any().setValue(POWERED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(POWERED);
    }

    /**
     * Вызывается при изменении соседнего блока (в том числе редстоун-проводника).
     * Проверяем сигнал со всех 6 сторон. Если состояние изменилось — шлём
     * единоразовый импульс на активацию или деактивацию.
     */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos,
                                   net.minecraft.world.level.block.Block block,
                                   BlockPos fromPos, boolean isMoving) {
        if (level.isClientSide) return;

        boolean wasPowered = state.getValue(POWERED);
        boolean isPowered  = isReceivingRedstone(level, pos);

        if (isPowered == wasPowered) return; // состояние не изменилось — ничего не делаем

        // Обновляем BlockState
        level.setBlock(pos, state.setValue(POWERED, isPowered), 3);

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof CommanderBlockEntity commander)) return;

        if (isPowered) {
            commander.broadcastActivate();
        } else {
            commander.broadcastDeactivate();
        }
    }

    /**
     * Проверяет наличие редстоун-сигнала с любой из 6 сторон блока.
     * hasNeighborSignal учитывает прямой и косвенный сигнал со всех сторон.
     */
    private boolean isReceivingRedstone(Level level, BlockPos pos) {
        return level.hasNeighborSignal(pos);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CommanderBlockEntity(ModBlockEntities.COMMANDER.get(), pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return createTickerHelper(type, ModBlockEntities.COMMANDER.get(), CommanderBlockEntity::serverTick);
    }

    /**
     * Запоминаем UUID игрока, разместившего командер, чтобы потом использовать
     * при проверке союзник/враг между командерами.
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof Player player) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof CommanderBlockEntity commander) {
                commander.setOwnerUUID(player.getUUID());
                // Предзаполняем редактируемое поле ключа альянса случайными
                // 4 символами — игрок сразу видит их в GUI и может оставить
                // как есть или изменить/очистить на своё усмотрение.
                commander.setAllianceKey(CommanderBlockEntity.generateRandomAllianceKey());
            }
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof CommanderBlockEntity commander) {
                sp.openMenu(commander, buf -> {
                    buf.writeBlockPos(pos);
                    buf.writeInt(commander.getFilterMask());
                    buf.writeUtf(commander.getAllianceKey());
                });
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos,
                            BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide && !movedByPiston) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof CommanderBlockEntity commander && !commander.isSuppressingDeactivate()) {
                commander.onRemoved();
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}