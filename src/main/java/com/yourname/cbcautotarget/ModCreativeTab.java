package com.yourname.cbcautotarget;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;

public class ModCreativeTab {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CBCAutoTarget.MOD_ID);

    public static final Supplier<CreativeModeTab> TAB = TABS.register("main", () ->
            CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.cbcautotarget"))
                    .icon(() -> ModBlocks.CONTROLLER_T1.get().asItem().getDefaultInstance())
                    .displayItems((params, output) -> {
                        output.accept(ModBlocks.CONTROLLER_T1.get());
                        output.accept(ModBlocks.CONTROLLER_T2.get());
                        output.accept(ModBlocks.CONTROLLER_T3.get());
                        output.accept(ModBlocks.CONTROLLER_T4.get());
                        output.accept(ModBlocks.MACHINE_SOUL.get());
                        output.accept(ModBlocks.COMMANDER.get());
                        output.accept(ModBlocks.CARTRIDGE_COLLECTOR.get());
                    })
                    .build()
    );
}
