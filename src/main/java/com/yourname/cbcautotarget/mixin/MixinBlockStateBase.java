package com.yourname.cbcautotarget.mixin;

import com.yourname.cbcautotarget.signal.SoulDirectSignals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Добавляет сигнал Machine Soul к слабому питанию, которое блок-потребитель читает через getSignal соседа. */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class MixinBlockStateBase {

    @Inject(method = "getSignal", at = @At("RETURN"), cancellable = true)
    private void cbc_autotarget$soulDirectSignal(BlockGetter getter, BlockPos pos, Direction direction,
                                                 CallbackInfoReturnable<Integer> cir) {
        if (!(getter instanceof Level level) || level.isClientSide) return;
        int soul = SoulDirectSignals.getSignal(level, pos.relative(direction.getOpposite()), direction);
        if (soul > cir.getReturnValue()) cir.setReturnValue(soul);
    }
}
