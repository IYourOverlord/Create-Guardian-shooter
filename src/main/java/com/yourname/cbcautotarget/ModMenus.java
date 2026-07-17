package com.yourname.cbcautotarget;

import com.yourname.cbcautotarget.menu.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, CBCAutoTarget.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<ControllerMenu>> CONTROLLER_MENU =
            MENU_TYPES.register("controller_menu",
                    () -> IMenuTypeExtension.create(ControllerMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<CommanderMenu>> COMMANDER_MENU =
            MENU_TYPES.register("commander_menu",
                    () -> IMenuTypeExtension.create(CommanderMenu::new));

    // Старое меню — оставляем для обратной совместимости (используется при первом открытии)
    public static final DeferredHolder<MenuType<?>, MenuType<MachineSoulMenu>> MACHINE_SOUL_MENU =
            MENU_TYPES.register("machine_soul_menu",
                    () -> IMenuTypeExtension.create(MachineSoulMenu::new));

    // Главная страница (хаб) — открывается при клике на блок
    public static final DeferredHolder<MenuType<?>, MenuType<MachineSoulHomeMenu>> MACHINE_SOUL_HOME =
            MENU_TYPES.register("machine_soul_home",
                    () -> IMenuTypeExtension.create(MachineSoulHomeMenu::new));

    // Три новых меню — по одному на вкладку
    public static final DeferredHolder<MenuType<?>, MenuType<MachineSoulVisionMenu>> MACHINE_SOUL_VISION =
            MENU_TYPES.register("machine_soul_vision",
                    () -> IMenuTypeExtension.create(MachineSoulVisionMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<MachineSoulMoveMenu>> MACHINE_SOUL_MOVE =
            MENU_TYPES.register("machine_soul_move",
                    () -> IMenuTypeExtension.create(MachineSoulMoveMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<MachineSoulActionMenu>> MACHINE_SOUL_ACTION =
            MENU_TYPES.register("machine_soul_action",
                    () -> IMenuTypeExtension.create(MachineSoulActionMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<MachineSoulTargetMenu>> MACHINE_SOUL_TARGET =
            MENU_TYPES.register("machine_soul_target",
                    () -> IMenuTypeExtension.create(MachineSoulTargetMenu::new));
}