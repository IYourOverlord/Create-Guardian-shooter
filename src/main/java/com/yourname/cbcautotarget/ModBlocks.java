package com.yourname.cbcautotarget;

import com.yourname.cbcautotarget.block.CartridgeCollectorBlock;
import com.yourname.cbcautotarget.block.MachineSoulBlock;
import com.yourname.cbcautotarget.block.CommanderBlock;
import com.yourname.cbcautotarget.block.ControllerBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CBCAutoTarget.MOD_ID);
    public static final DeferredRegister.Items  ITEMS  = DeferredRegister.createItems(CBCAutoTarget.MOD_ID);

    // ── Controller tier properties ────────────────────────────────────────────

    private static BlockBehaviour.Properties controllerProps() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.SAND)
                .strength(2.0f)
                .sound(SoundType.CORAL_BLOCK)
                .noCollission();
    }

    // Tier 1 — 25 blocks scan radius
    public static final DeferredBlock<ControllerBlock> CONTROLLER_T1 =
            BLOCKS.register("controller_t1", () -> new ControllerBlock(controllerProps(), 1));
    public static final DeferredItem<BlockItem> CONTROLLER_T1_ITEM =
            ITEMS.registerSimpleBlockItem("controller_t1", CONTROLLER_T1);

    // Tier 2 — 50 blocks scan radius
    public static final DeferredBlock<ControllerBlock> CONTROLLER_T2 =
            BLOCKS.register("controller_t2", () -> new ControllerBlock(controllerProps(), 2));
    public static final DeferredItem<BlockItem> CONTROLLER_T2_ITEM =
            ITEMS.registerSimpleBlockItem("controller_t2", CONTROLLER_T2);

    // Tier 3 — 100 blocks scan radius
    public static final DeferredBlock<ControllerBlock> CONTROLLER_T3 =
            BLOCKS.register("controller_t3", () -> new ControllerBlock(controllerProps(), 3));
    public static final DeferredItem<BlockItem> CONTROLLER_T3_ITEM =
            ITEMS.registerSimpleBlockItem("controller_t3", CONTROLLER_T3);

    // Tier 4 — 200 blocks scan radius
    public static final DeferredBlock<ControllerBlock> CONTROLLER_T4 =
            BLOCKS.register("controller_t4", () -> new ControllerBlock(controllerProps(), 4));
    public static final DeferredItem<BlockItem> CONTROLLER_T4_ITEM =
            ITEMS.registerSimpleBlockItem("controller_t4", CONTROLLER_T4);

    // ── Machine Soul ──────────────────────────────────────────────────────────

    public static final DeferredBlock<MachineSoulBlock> MACHINE_SOUL =
            BLOCKS.register("machine_soul", () -> new MachineSoulBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_BLACK)
                            .strength(3.5f)
                            .sound(SoundType.ANCIENT_DEBRIS)
                            .requiresCorrectToolForDrops()));
    public static final DeferredItem<BlockItem> MACHINE_SOUL_ITEM =
            ITEMS.registerSimpleBlockItem("machine_soul", MACHINE_SOUL);

    // ── Commander ─────────────────────────────────────────────────────────────

    public static final DeferredBlock<CommanderBlock> COMMANDER =
            BLOCKS.register("commander", () -> new CommanderBlock(
                    BlockBehaviour.Properties.ofFullCopy(Blocks.BEACON).strength(3.0f)));
    public static final DeferredItem<BlockItem> COMMANDER_ITEM =
            ITEMS.registerSimpleBlockItem("commander", COMMANDER);

    // ── Cartridge Collector ───────────────────────────────────────────────────

    public static final DeferredBlock<CartridgeCollectorBlock> CARTRIDGE_COLLECTOR =
            BLOCKS.register("cartridge_collector", () -> new CartridgeCollectorBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_YELLOW)
                            .strength(1.0f)
                            .sound(SoundType.SLIME_BLOCK)
                            .noCollission()));
    public static final DeferredItem<BlockItem> CARTRIDGE_COLLECTOR_ITEM =
            ITEMS.registerSimpleBlockItem("cartridge_collector", CARTRIDGE_COLLECTOR);
}
