package com.yourname.cbcautotarget;

import com.yourname.cbcautotarget.blockentity.CartridgeCollectorBlockEntity;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity;
import com.yourname.cbcautotarget.blockentity.CommanderBlockEntity;
import com.yourname.cbcautotarget.blockentity.ControllerBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CBCAutoTarget.MOD_ID);

    /**
     * Один BlockEntityType для всех четырёх тиров контроллера.
     * Тир считывается из BlockState.getBlock() как ControllerBlock.getTier().
     */
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ControllerBlockEntity>> CONTROLLER =
            BLOCK_ENTITY_TYPES.register("controller", () ->
                    BlockEntityType.Builder.<ControllerBlockEntity>of(
                            (pos, state) -> new ControllerBlockEntity(
                                    ModBlockEntities.CONTROLLER.get(), pos, state),
                            ModBlocks.CONTROLLER_T1.get(),
                            ModBlocks.CONTROLLER_T2.get(),
                            ModBlocks.CONTROLLER_T3.get(),
                            ModBlocks.CONTROLLER_T4.get()
                    ).build(null));

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CommanderBlockEntity>> COMMANDER =
            BLOCK_ENTITY_TYPES.register("commander", () ->
                    BlockEntityType.Builder.<CommanderBlockEntity>of(
                            (pos, state) -> new CommanderBlockEntity(
                                    ModBlockEntities.COMMANDER.get(), pos, state),
                            ModBlocks.COMMANDER.get()
                    ).build(null));

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MachineSoulBlockEntity>> MACHINE_SOUL =
            BLOCK_ENTITY_TYPES.register("machine_soul", () ->
                    BlockEntityType.Builder.<MachineSoulBlockEntity>of(
                            (pos, state) -> new MachineSoulBlockEntity(
                                    ModBlockEntities.MACHINE_SOUL.get(), pos, state),
                            ModBlocks.MACHINE_SOUL.get()
                    ).build(null));

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CartridgeCollectorBlockEntity>> CARTRIDGE_COLLECTOR =
            BLOCK_ENTITY_TYPES.register("cartridge_collector", () ->
                    BlockEntityType.Builder.<CartridgeCollectorBlockEntity>of(
                            (pos, state) -> new CartridgeCollectorBlockEntity(
                                    ModBlockEntities.CARTRIDGE_COLLECTOR.get(), pos, state),
                            ModBlocks.CARTRIDGE_COLLECTOR.get()
                    ).build(null));
}
