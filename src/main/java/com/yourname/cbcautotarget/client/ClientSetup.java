package com.yourname.cbcautotarget.client;

import com.yourname.cbcautotarget.ModBlockEntities;
import com.yourname.cbcautotarget.ModBlocks;
import com.yourname.cbcautotarget.ModMenus;
import com.yourname.cbcautotarget.client.renderer.CannonMountOverlayRenderer;
import com.yourname.cbcautotarget.client.renderer.CartridgeCollectorBlockEntityRenderer;
import com.yourname.cbcautotarget.client.renderer.CommanderBlockEntityRenderer;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = "cbc_autotarget", bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientSetup {

    @SubscribeEvent
    public static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.CONTROLLER_MENU.get(),      ControllerScreen::new);
        event.register(ModMenus.COMMANDER_MENU.get(),       CommanderScreen::new);
        // Главная страница (хаб) — открывается при клике на блок
        event.register(ModMenus.MACHINE_SOUL_HOME.get(),    MachineSoulHomeScreen::new);
        // Три вкладочных экрана
        event.register(ModMenus.MACHINE_SOUL_VISION.get(),  MachineSoulVisionScreen::new);
        event.register(ModMenus.MACHINE_SOUL_MOVE.get(),    MachineSoulMoveScreen::new);
        event.register(ModMenus.MACHINE_SOUL_ACTION.get(),  MachineSoulActionScreen::new);
        event.register(ModMenus.MACHINE_SOUL_TARGET.get(),  MachineSoulTargetScreen::new);
        // Старый экран — для обратной совместимости
        event.register(ModMenus.MACHINE_SOUL_MENU.get(),    MachineSoulScreen::new);
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.CONTROLLER.get(),          CannonMountOverlayRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.COMMANDER.get(),           CommanderBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.CARTRIDGE_COLLECTOR.get(), CartridgeCollectorBlockEntityRenderer::new);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ItemBlockRenderTypes.setRenderLayer(ModBlocks.CONTROLLER_T1.get(), RenderType.cutout());
            ItemBlockRenderTypes.setRenderLayer(ModBlocks.CONTROLLER_T2.get(), RenderType.cutout());
            ItemBlockRenderTypes.setRenderLayer(ModBlocks.CONTROLLER_T3.get(), RenderType.cutout());
            ItemBlockRenderTypes.setRenderLayer(ModBlocks.CONTROLLER_T4.get(), RenderType.cutout());
            ItemBlockRenderTypes.setRenderLayer(ModBlocks.COMMANDER.get(),     RenderType.cutout());
        });
    }
}