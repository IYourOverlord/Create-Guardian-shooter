package com.yourname.cbcautotarget;

import com.mojang.logging.LogUtils;
import com.yourname.cbcautotarget.blockentity.CommanderBlockEntity;
import com.yourname.cbcautotarget.blockentity.ControllerBlockEntity;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity;
import com.yourname.cbcautotarget.network.ModPackets;
import com.simibubi.create.api.schematic.nbt.SafeNbtWriterRegistry;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;

@Mod(CBCAutoTarget.MOD_ID)
public class CBCAutoTarget {
    public static final String MOD_ID = "cbc_autotarget";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CBCAutoTarget(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlocks.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITY_TYPES.register(modEventBus);
        ModMenus.MENU_TYPES.register(modEventBus);
        ModPackets.register(modEventBus);
        ModCreativeTab.TABS.register(modEventBus);
        modContainer.registerConfig(ModConfig.Type.SERVER, CBCAutoTargetConfig.SPEC);
        modEventBus.addListener(this::commonSetup);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // SafeNbtWriter регистрируется в commonSetup (не в конструкторе):
        // в конструкторе COMMANDER.get() ещё unbound → NullPointerException.
        //
        // Create при deploy схематики вызывает для каждого зарегистрированного BE:
        //   writeSafe(be, emptyTag, registries) — BE должен записать своё состояние в tag.
        // Затем Create использует заполненный tag (через handleUpdateTag или второй loadAdditional).
        //
        // Данные фильтра уже корректно загружены первым loadAdditional() из NBT схематики
        // и защищены флагом schematicDataLoaded от перезаписи вторым loadAdditional().
        // Здесь мы просто записываем текущее состояние BE в tag — на случай если Create
        // всё-таки применит его через handleUpdateTag.
        SafeNbtWriterRegistry.REGISTRY.register(
                ModBlockEntities.COMMANDER.get(),
                (be, tag, registries) -> {
                    if (!(be instanceof CommanderBlockEntity cbe)) return;
                    // Записываем полное состояние BE в tag (направление BE→tag).
                    // К этому моменту filterData уже содержит правильные данные из схематики,
                    // загруженные первым loadAdditional и защищённые флагом schematicNbtApplied.
                    cbe.getFilterData().saveToNBT(tag);
                    tag.putString("AllianceKey", cbe.getAllianceKey());
                    // WasActive намеренно не сохраняем — блок стартует неактивным.
                    CBCAutoTarget.LOGGER.debug(
                            "[SafeNbtWriter/Commander] Writing mask={} allianceKey='{}' at {}",
                            Integer.toBinaryString(cbe.getFilterData().getMask()),
                            cbe.getAllianceKey(),
                            be.getBlockPos());
                }
        );
        // MachineSoulBlockEntity: при deploy схематики Create вызывает SafeNbtWriter
        // чтобы получить актуальный тег и применить его через handleUpdateTag.
        // К этому моменту CommandSlots уже загружены первым loadAdditional из схематики
        // и защищены от перезаписи счётчиком schematicLoadCount.
        // Записываем текущее состояние BE в tag — Create применит его корректно.
        SafeNbtWriterRegistry.REGISTRY.register(
                ModBlockEntities.MACHINE_SOUL.get(),
                (be, tag, registries) -> {
                    if (!(be instanceof MachineSoulBlockEntity soul)) return;
                    soul.writeSafeNbt(tag, registries);
                }
        );
        // ControllerBlockEntity: при deploy схематики сохраняем инвентарь (патроны)
        // и настройки фильтра. Позиционные данные (CannonMountPos и т.д.) не пишем —
        // они привязаны к конкретному миру и пересчитываются при активации.
        // Все тиры (T1–T4) используют один BlockEntityType — CONTROLLER.
        SafeNbtWriterRegistry.REGISTRY.register(
                ModBlockEntities.CONTROLLER.get(),
                (be, tag, registries) -> {
                    if (!(be instanceof ControllerBlockEntity ctrl)) return;
                    ctrl.writeSafeNbt(tag, registries);
                }
        );
    }
}