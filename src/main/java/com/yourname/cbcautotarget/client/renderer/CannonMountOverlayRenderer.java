package com.yourname.cbcautotarget.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yourname.cbcautotarget.block.ControllerBlock;
import com.yourname.cbcautotarget.blockentity.ControllerBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.List;

/**
 * Рендерит куб dark_prismarine (scale 1.02) поверх CannonMount когда Controller активен.
 *
 * Использует BlockEntityRenderer вместо RenderLevelStageEvent — движок сам
 * позиционирует PoseStack относительно позиции блока с правильным partialTick,
 * что полностью устраняет визуальный сдвиг при движении камеры.
 *
 * Файл перемещён в пакет client.renderer (рядом с CommanderBlockEntityRenderer).
 */
public class CannonMountOverlayRenderer implements BlockEntityRenderer<ControllerBlockEntity> {

    private static final float SCALE  = 1.02f;
    private static final float OFFSET = -0.01f;

    private static final RandomSource RAND = RandomSource.create(42L);

    private static final Direction[] DIRS = {
            null,
            Direction.DOWN, Direction.UP,
            Direction.NORTH, Direction.SOUTH,
            Direction.EAST, Direction.WEST
    };

    public CannonMountOverlayRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(ControllerBlockEntity be, float partialTick,
                       PoseStack ps, MultiBufferSource buffers,
                       int light, int overlay) {

        BlockState state = be.getBlockState();
        if (!state.hasProperty(ControllerBlock.ACTIVE)) return;
        if (!state.getValue(ControllerBlock.ACTIVE)) return;

        BlockPos mountPos = be.getCannonMountPos();
        if (mountPos == null) return;

        // PoseStack здесь уже позиционирован движком в начало блока Controller.
        // Смещаем к позиции CannonMount относительно Controller.
        BlockPos ctrlPos = be.getBlockPos();
        int dx = mountPos.getX() - ctrlPos.getX();
        int dy = mountPos.getY() - ctrlPos.getY();
        int dz = mountPos.getZ() - ctrlPos.getZ();

        BlockState prismarine = Blocks.DARK_PRISMARINE.defaultBlockState();
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(prismarine);

        ps.pushPose();
        ps.translate(dx + OFFSET, dy + OFFSET, dz + OFFSET);
        ps.scale(SCALE, SCALE, SCALE);

        VertexConsumer buf = buffers.getBuffer(RenderType.solid());
        PoseStack.Pose pose = ps.last();

        for (Direction dir : DIRS) {
            RAND.setSeed(42L);
            List<BakedQuad> quads = model.getQuads(
                    prismarine, dir, RAND, ModelData.EMPTY, RenderType.solid());
            for (BakedQuad quad : quads) {
                buf.putBulkData(
                        pose, quad,
                        1.0f, 1.0f, 1.0f, 1.0f,
                        LightTexture.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY
                );
            }
        }

        ps.popPose();
    }

    @Override
    public boolean shouldRenderOffScreen(ControllerBlockEntity be) {
        // Рендерим даже когда Controller вне экрана — CannonMount может быть виден.
        return true;
    }
}