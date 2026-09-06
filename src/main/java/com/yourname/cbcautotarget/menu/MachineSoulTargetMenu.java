package com.yourname.cbcautotarget.menu;

import com.yourname.cbcautotarget.ModBlockEntities;
import com.yourname.cbcautotarget.ModBlocks;
import com.yourname.cbcautotarget.ModMenus;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity;
import com.yourname.cbcautotarget.filter.CommanderFilterData;
import com.yourname.cbcautotarget.filter.TargetFilterData;
import com.yourname.cbcautotarget.filter.WhitelistMode;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Меню вкладки Target.
 * Содержит: переключатель targetPlayers, кнопку «Player Filter...», инвентарь.
 */
public class MachineSoulTargetMenu extends AbstractContainerMenu {

    private static final Logger LOGGER = LoggerFactory.getLogger("cbc_autotarget/TargetMenu");

    public static final int INV_X      = 8;
    // TOGGLE_Y=60 h=16 → 76; FILTER_BTN_Y=80 h=16 → 96; gap=4 → INV=100
    public static final int INV_Y_BASE = 100;

    public final MachineSoulBlockEntity blockEntity;
    public final BlockPos blockPos;
    private boolean targetPlayers;
    private WhitelistMode whitelistMode = WhitelistMode.TARGET;

    private final TargetFilterData playerFilterData = new TargetFilterData();
    private final CommanderFilterData commanderFilterData = new CommanderFilterData();

    // ── Клиентский конструктор ────────────────────────────────────────────────
    public MachineSoulTargetMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        this(id, inv, readDummy(buf));
    }

    private static MachineSoulBlockEntity readDummy(RegistryFriendlyByteBuf buf) {
        BlockPos pos          = buf.readBlockPos();
        boolean targetPlayers = buf.readBoolean();
        boolean wlEnabled     = buf.readBoolean();
        List<String> wl       = ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8).decode(buf);
        int modeId            = buf.readInt();
        // Маска категорий целей — читается сразу после modeId, в том же
        // порядке, в каком её пишет SwitchMachineSoulTabPacket.openTarget().
        // Без этого чтения TargetFilterData оставалась бы с маской по
        // умолчанию (все категории включены) при каждом открытии GUI.
        int mask              = buf.readInt();
        List<String> friendlyCommanders = ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8).decode(buf);

        LOGGER.info("[TargetMenu] readDummy (CLIENT) pos={} targetPlayers={} wlEnabled={} wlSize={} modeId={} mask={} friendlyCommandersSize={}",
                pos, targetPlayers, wlEnabled, wl.size(), modeId, mask, friendlyCommanders.size());

        MachineSoulBlockEntity dummy = new MachineSoulBlockEntity(
                ModBlockEntities.MACHINE_SOUL.get(), pos,
                ModBlocks.MACHINE_SOUL.get().defaultBlockState());
        dummy.setTargetPlayers(targetPlayers);
        dummy.getPlayerFilterData().setWhitelistEnabled(wlEnabled);
        dummy.getPlayerFilterData().replaceWhitelist(wl);
        dummy.setWhitelistMode(WhitelistMode.fromId(modeId));
        dummy.getPlayerFilterData().setMask(mask);
        dummy.getCommanderFilterData().replaceFriendlyIds(friendlyCommanders);
        LOGGER.info("[TargetMenu] readDummy (CLIENT) pos={} dummy BE built, dummy.getPlayerFilterData().getMask()={}",
                pos, dummy.getPlayerFilterData().getMask());
        return dummy;
    }

    // ── Серверный конструктор ─────────────────────────────────────────────────
    public MachineSoulTargetMenu(int id, Inventory inv, MachineSoulBlockEntity be) {
        super(ModMenus.MACHINE_SOUL_TARGET.get(), id);
        this.blockEntity   = be;
        this.blockPos      = be.getBlockPos();
        this.targetPlayers = be.isTargetPlayers();
        this.whitelistMode = be.getWhitelistMode();
        this.playerFilterData.setWhitelistEnabled(be.getPlayerFilterData().isWhitelistEnabled());
        this.playerFilterData.replaceWhitelist(new ArrayList<>(be.getPlayerFilterData().getWhitelist()));
        // ИСПРАВЛЕНО: раньше серверный конструктор НЕ копировал маску
        // (be.getPlayerFilterData().getMask()) в this.playerFilterData — по
        // умолчанию TargetFilterData() создаётся с ALL_MASK. На данный момент
        // ничто на сервере не читает menu.getPlayerFilterData().getMask()
        // (используется be.getPlayerFilterData() напрямую), но оставлять два
        // рассинхронизированных источника правды рискованно на будущее —
        // синхронизируем на всякий случай, как это уже делает клиентский
        // конструктор (readDummy).
        this.playerFilterData.setMask(be.getPlayerFilterData().getMask());
        this.commanderFilterData.replaceFriendlyIds(new ArrayList<>(be.getCommanderFilterData().getFriendlyIds()));
        LOGGER.info("[TargetMenu] SERVER constructor pos={} be.mask={} this.playerFilterData.mask(after ctor)={} be.targetPlayers={}",
                blockPos, be.getPlayerFilterData().getMask(), this.playerFilterData.getMask(), this.targetPlayers);
    }

    private void addPlayerInventory(Inventory inv) {
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                addSlot(new Slot(inv, col + row * 9 + 9, INV_X + col * 18, INV_Y_BASE + row * 18));
        for (int col = 0; col < 9; col++)
            addSlot(new Slot(inv, col, INV_X + col * 18, INV_Y_BASE + 58));
    }

    public boolean      isTargetPlayers()              { return targetPlayers; }
    public void         setTargetPlayers(boolean v)    { this.targetPlayers = v; }
    public TargetFilterData getPlayerFilterData()      { return playerFilterData; }
    public CommanderFilterData getCommanderFilterData() { return commanderFilterData; }
    public WhitelistMode getWhitelistMode()            { return whitelistMode; }
    public void         setWhitelistMode(WhitelistMode m) { this.whitelistMode = m; }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot s = slots.get(index);
        return s.hasItem() ? s.getItem() : ItemStack.EMPTY;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (blockEntity.getLevel() != null && !blockEntity.getLevel().isClientSide())
            blockEntity.onMenuClosed(player);
    }

    @Override
    public boolean stillValid(Player p) {
        if (blockEntity.getLevel() == null) return true;
        return blockEntity.getLevel().getBlockState(blockPos).is(ModBlocks.MACHINE_SOUL.get())
                || com.yourname.cbcautotarget.compat.SableCompat.isAvailable();
    }
}
