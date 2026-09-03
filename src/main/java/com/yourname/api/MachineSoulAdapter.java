package com.yourname.cbcautotarget.api;

import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Внутренний адаптер. Не используй напрямую — получай через {@link CbcAutotargetAPI}.
 */
final class MachineSoulAdapter implements IMachineSoul {

    private final MachineSoulBlockEntity be;

    MachineSoulAdapter(MachineSoulBlockEntity be) {
        this.be = be;
    }

    @Override public boolean isActive()                  { return be.isTargetSearchActive(); }
    @Override public void    setActive(boolean active)   { be.setTargetSearchActive(active); }

    @Override public int  getDetectionRadius()           { return be.getDetectionRadius(); }
    @Override public void setDetectionRadius(int radius) { be.setDetectionRadius(radius); }

    @Override public int  getKeepDistance()              { return be.getKeepDistance(); }
    @Override public void setKeepDistance(int distance)  { be.setKeepDistance(distance); }

    @Override public boolean isTargetPlayers()               { return be.isTargetPlayers(); }
    @Override public void    setTargetPlayers(boolean e)     { be.setTargetPlayers(e); }

    @Override public boolean isRequireSubLevel()             { return be.isRequireSubLevel(); }
    @Override public void    setRequireSubLevel(boolean req) { be.setRequireSubLevel(req); }

    @Override public BlockPos getBlockPos() { return be.getBlockPos(); }
    @Override public Level    getLevel()    { return be.getLevel(); }
}
