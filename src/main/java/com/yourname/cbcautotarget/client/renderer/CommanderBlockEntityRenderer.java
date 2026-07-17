package com.yourname.cbcautotarget.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.cbcautotarget.blockentity.CommanderBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.level.Level;

public class CommanderBlockEntityRenderer implements BlockEntityRenderer<CommanderBlockEntity> {

    public CommanderBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(CommanderBlockEntity be, float partialTick,
                       PoseStack ps, MultiBufferSource buffers,
                       int light, int overlay) {
        // модель рендерится через blockstate/JSON, доп. рендер не нужен
    }
}
