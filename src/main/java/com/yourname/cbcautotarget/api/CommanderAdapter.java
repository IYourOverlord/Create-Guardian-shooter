package com.yourname.cbcautotarget.api;

import com.yourname.cbcautotarget.block.CommanderBlock;
import com.yourname.cbcautotarget.blockentity.CommanderBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Внутренний адаптер. Не используй напрямую — получай через {@link CbcAutotargetAPI}.
 */
final class CommanderAdapter implements ICommander {

    private final CommanderBlockEntity be;

    CommanderAdapter(CommanderBlockEntity be) {
        this.be = be;
    }

    @Override
    public void activate() {
        Level level = be.getLevel();
        if (level == null || level.isClientSide) return;
        // Обновляем BlockState (POWERED=true) чтобы не рассинхронизироваться с редстоуном
        BlockPos pos = be.getBlockPos();
        level.setBlock(pos,
                level.getBlockState(pos).setValue(CommanderBlock.POWERED, true), 3);
        be.broadcastActivate();
    }

    @Override
    public void deactivate() {
        Level level = be.getLevel();
        if (level == null || level.isClientSide) return;
        BlockPos pos = be.getBlockPos();
        level.setBlock(pos,
                level.getBlockState(pos).setValue(CommanderBlock.POWERED, false), 3);
        be.broadcastDeactivate();
    }

    @Override
    public void broadcastFilterUpdate()         { be.broadcastFilterUpdate(); }

    @Override public @Nullable UUID getOwnerUUID()           { return be.getOwnerUUID(); }
    @Override public void           setOwnerUUID(@Nullable UUID uuid) { be.setOwnerUUID(uuid); }

    @Override public String getAllianceKey()                  { return be.getAllianceKey(); }
    @Override public void   setAllianceKey(@Nullable String key) { be.setAllianceKey(key); }

    @Override public int  getFilterMask()                    { return be.getFilterMask(); }
    @Override public void setFilterMask(int mask)            { be.setFilterMask(mask); }

    @Override public BlockPos getBlockPos() { return be.getBlockPos(); }
    @Override public Level    getLevel()    { return be.getLevel(); }
}
