package com.yourname.cbcautotarget.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.cbcautotarget.block.CartridgeCollectorBlock;
import com.yourname.cbcautotarget.blockentity.CartridgeCollectorBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pufferfish;
import net.minecraft.world.level.Level;

public class CartridgeCollectorBlockEntityRenderer
        implements BlockEntityRenderer<CartridgeCollectorBlockEntity> {

    // Два entity заготавливаем заранее — один сжатый, один раздутый.
    // Lazy-инициализация при первом рендере когда ClientLevel уже доступен.
    private Pufferfish pufferSmall = null;
    private Pufferfish pufferBig   = null;

    public CartridgeCollectorBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {}

    private void initEntities(ClientLevel level) {
        if (pufferSmall != null) return;

        pufferSmall = new Pufferfish(EntityType.PUFFERFISH, level);
        pufferSmall.setPuffState(0); // сжатая

        pufferBig = new Pufferfish(EntityType.PUFFERFISH, level);
        pufferBig.setPuffState(2);   // полностью раздутая

        // Фиксируем вращение чтобы entity не дёргалось
        for (Pufferfish p : new Pufferfish[]{pufferSmall, pufferBig}) {
            p.setYRot(0f);
            p.yRotO     = 0f;
            p.yHeadRot  = 0f;
            p.yHeadRotO = 0f;
        }
    }

    @Override
    public void render(CartridgeCollectorBlockEntity be, float partialTick,
                       PoseStack ps, MultiBufferSource buffers,
                       int light, int overlay) {

        Level level = be.getLevel();
        if (!(level instanceof ClientLevel cl)) return;

        initEntities(cl);

        boolean full = be.getBlockState().getValue(CartridgeCollectorBlock.FULL);
        Pufferfish puffer = full ? pufferBig : pufferSmall;

        ps.pushPose();
        ps.translate(0.5, 0.25, 0.5);

        long tick = level.getGameTime();
        float angle = (tick % 360) * 1.0f + partialTick;
        ps.mulPose(com.mojang.math.Axis.YP.rotationDegrees(angle));

        float scale = full ? 0.55f : 0.45f;
        ps.scale(scale, scale, scale);

        Minecraft.getInstance().getEntityRenderDispatcher().render(
                puffer, 0, 0, 0,
                0f, partialTick,
                ps, buffers, light
        );

        ps.popPose();
    }
}

