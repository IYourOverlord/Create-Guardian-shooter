package com.yourname.cbcautotarget.menu;

import com.yourname.cbcautotarget.ModBlockEntities;
import com.yourname.cbcautotarget.ModBlocks;
import com.yourname.cbcautotarget.ModMenus;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Меню вкладки Vision — только инвентарь игрока, без freq-слотов.
 * Передаёт через буфер: detectionRadius и keepDistance.
 */
public class MachineSoulVisionMenu extends AbstractContainerMenu {

    public final MachineSoulBlockEntity blockEntity;
    public final BlockPos blockPos;
    private int detectionRadius;
    private int keepDistance;
    private int standStillDistance;

    public static final int INV_X      = 8;
    public static final int INV_Y_BASE = 160;  // компактный layout, см. MachineSoulVisionScreen

    // ── Клиентский конструктор ────────────────────────────────────────────────
    public MachineSoulVisionMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        this(id, inv, readDummy(buf));
    }

    private static MachineSoulBlockEntity readDummy(RegistryFriendlyByteBuf buf) {
        BlockPos pos        = buf.readBlockPos();
        int radius          = buf.readInt();
        int keepDist        = buf.readInt();
        int standStill      = buf.readInt();
        MachineSoulBlockEntity dummy = new MachineSoulBlockEntity(
                ModBlockEntities.MACHINE_SOUL.get(), pos,
                ModBlocks.MACHINE_SOUL.get().defaultBlockState());
        dummy.setDetectionRadius(radius);
        dummy.setKeepDistance(keepDist);
        dummy.setStandStillDistance(standStill);
        return dummy;
    }

    // ── Серверный конструктор ─────────────────────────────────────────────────
    public MachineSoulVisionMenu(int id, Inventory inv, MachineSoulBlockEntity be) {
        super(ModMenus.MACHINE_SOUL_VISION.get(), id);
        this.blockEntity       = be;
        this.blockPos          = be.getBlockPos();
        this.detectionRadius   = be.getDetectionRadius();
        this.keepDistance      = be.getKeepDistance();
        this.standStillDistance = be.getStandStillDistance();
    }

    private void addPlayerInventory(Inventory inv) {
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                addSlot(new Slot(inv, col + row * 9 + 9, INV_X + col * 18, INV_Y_BASE + row * 18));
        for (int col = 0; col < 9; col++)
            addSlot(new Slot(inv, col, INV_X + col * 18, INV_Y_BASE + 58));
    }

    public int getDetectionRadius()       { return detectionRadius; }
    public void setDetectionRadius(int r) { detectionRadius = r; }

    public int getKeepDistance()          { return keepDistance; }
    public void setKeepDistance(int d)    { keepDistance = d; }

    public int getStandStillDistance()       { return standStillDistance; }
    public void setStandStillDistance(int d) { standStillDistance = d; }

    @Override public ItemStack quickMoveStack(Player p, int i) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player p) {
        if (blockEntity.getLevel() == null) return true;
        return blockEntity.getLevel().getBlockState(blockPos).is(ModBlocks.MACHINE_SOUL.get())
                || com.yourname.cbcautotarget.compat.SableCompat.isAvailable();
    }
}