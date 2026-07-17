package com.yourname.cbcautotarget.blockentity;

import com.simibubi.create.Create;
import com.simibubi.create.content.redstone.link.IRedstoneLinkable;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;
import com.yourname.cbcautotarget.block.MachineSoulBlock;
import com.yourname.cbcautotarget.compat.SableCompat;
import com.yourname.cbcautotarget.filter.CommanderFilterData;
import com.yourname.cbcautotarget.filter.TargetCategory;
import com.yourname.cbcautotarget.filter.TargetFilterData;
import com.yourname.cbcautotarget.filter.WhitelistMode;
import com.yourname.cbcautotarget.menu.MachineSoulMenu;
import com.yourname.cbcautotarget.network.SyncMachineSoulStatusPacket;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import javax.annotation.Nullable;

public class MachineSoulBlockEntity extends BlockEntity implements MenuProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(MachineSoulBlockEntity.class);

    // ── Роли команд ───────────────────────────────────────────────────────────

    /**
     * Роли сгруппированы по вкладкам GUI:
     *   VISION tab  : (нет ролей — только настройка радиуса)
     *   MOVEMENT tab: MOVE_FORWARD, MOVE_BACKWARD, MOVE_LEFT, MOVE_RIGHT, MOVE_UP, MOVE_DOWN
     *   ACTION tab  : FIRE
     */
    public enum CommandRole {
        // Movement
        MOVE_FORWARD,
        MOVE_BACKWARD,
        MOVE_LEFT,
        MOVE_RIGHT,
        MOVE_UP,
        MOVE_DOWN,
        // Action
        FIRE;

        public String nbtKey() { return name(); }

        /** Вкладка GUI, к которой принадлежит эта роль. */
        public Tab tab() {
            return switch (this) {
                case MOVE_FORWARD, MOVE_BACKWARD, MOVE_LEFT, MOVE_RIGHT,
                     MOVE_UP, MOVE_DOWN -> Tab.MOVEMENT;
                case FIRE -> Tab.ACTION;
            };
        }
    }

    /** Вкладки GUI Machine Soul. */
    public enum Tab { VISION, MOVEMENT, ACTION, TARGET }

    // ── CommandSlot ───────────────────────────────────────────────────────────

    public static class CommandSlot {
        public final CommandRole role;
        public ItemStack freq0;
        public ItemStack freq1;

        public CommandSlot(CommandRole role) {
            this.role  = role;
            this.freq0 = ItemStack.EMPTY;
            this.freq1 = ItemStack.EMPTY;
        }

        @Nullable
        public Couple<Frequency> toFrequency() {
            if (freq0.isEmpty() && freq1.isEmpty()) return null;
            return Couple.create(Frequency.of(freq0), Frequency.of(freq1));
        }

        public boolean isAssigned() {
            return !freq0.isEmpty() || !freq1.isEmpty();
        }

        public CompoundTag save(HolderLookup.Provider registries) {
            CompoundTag tag = new CompoundTag();
            tag.putString("Role", role.name());
            tag.put("Freq0", freq0.saveOptional(registries));
            tag.put("Freq1", freq1.saveOptional(registries));
            return tag;
        }

        public static CommandSlot load(CompoundTag tag, HolderLookup.Provider registries) {
            CommandRole role = CommandRole.valueOf(tag.getString("Role"));
            CommandSlot slot = new CommandSlot(role);
            slot.freq0 = ItemStack.parseOptional(registries, tag.getCompound("Freq0"));
            slot.freq1 = ItemStack.parseOptional(registries, tag.getCompound("Freq1"));
            return slot;
        }
    }

    // ── Константы ─────────────────────────────────────────────────────────────

    private static final int    SCAN_INTERVAL           = 10;
    public  static final int    DEFAULT_DETECTION_RADIUS = 30;
    public  static final int    MIN_DETECTION_RADIUS     = 5;
    public  static final int    MAX_DETECTION_RADIUS     = 128;
    public  static final int    DEFAULT_KEEP_DISTANCE    = 0;
    public  static final int    MIN_KEEP_DISTANCE        = 0;
    public  static final int    MAX_KEEP_DISTANCE        = 64;

    public  static final int    DEFAULT_STAND_STILL_DISTANCE = 0;
    public  static final int    MIN_STAND_STILL_DISTANCE     = 0;
    public  static final int    MAX_STAND_STILL_DISTANCE     = 64;
    private static final int    LINK_SEARCH_RADIUS       = 128;
    private static final float  YAW_DEADBAND_DEG         = 25f;
    private static final int    SUBLEVEL_CACHE_INTERVAL  = 40;

    /**
     * Угол обзора для ДЕЙСТВИЯ (FIRE).
     * Действие активируется только если цель находится перед блоком в пределах ±90°.
     * Движение (MOVE_*) работает на полные 360° — отдельного ограничения нет.
     */
    private static final double ACTION_FOV_DEG = 90.0;

    // ── Состояние ─────────────────────────────────────────────────────────────

    private final Map<CommandRole, CommandSlot>  slots         = new EnumMap<>(CommandRole.class);
    private final Map<CommandRole, ActiveSignal> activeSignals = new EnumMap<>(CommandRole.class);

    private final Set<UUID> viewingPlayers = new HashSet<>();

    /** Радиус обнаружения целей (настраивается в GUI, вкладка «Зрение»). */
    private int detectionRadius = DEFAULT_DETECTION_RADIUS;

    /**
     * Дистанция удержания от цели (настраивается в GUI, вкладка «Зрение»).
     * Если цель находится ближе этого значения И в зоне прямого обзора (±90°),
     * блок активирует MOVE_BACKWARD вместо MOVE_FORWARD для отступления.
     * 0 — функция отключена.
     */
    private int keepDistance = DEFAULT_KEEP_DISTANCE;

    /**
     * Дистанция «стоять на месте».
     * Если цель в диапазоне [keepDistance, standStillDistance] —
     * блок только поворачивается (LEFT/RIGHT), движение вперёд/назад/вверх/вниз отключено.
     * 0 — функция отключена.
     */
    private int standStillDistance = DEFAULT_STAND_STILL_DISTANCE;

    /**
     * Режим поиска цели. Когда выключен — doScan() ничего не делает и все
     * активные сигналы (движение/огонь) снимаются.
     * По умолчанию true: новый (только что скрафченный/размещённый) блок активен.
     */
    private boolean targetSearchActive = true;

    /**
     * Режим "Только на физической конструкции". Когда включён, обычная кнопка
     * активации (targetSearchActive) полностью игнорируется — работа блока
     * (сканирование/движение/огонь) зависит ИСКЛЮЧИТЕЛЬНО от того, находится
     * ли блок сейчас на Sable sub-level (физической конструкции). Если блок
     * не на sub-level, он не работает независимо от состояния обычной кнопки;
     * сама кнопка при этом остаётся видимой и кликабельной в GUI.
     * По умолчанию выключен — поведение без Sable не меняется.
     */
    private boolean requireSubLevel = false;

    /**
     * Разрешён ли поиск/таргетинг игроков. Если выключено — doScan()
     * игнорирует игроков (как будто их нет в радиусе) и снимает сигналы.
     * По умолчанию true.
     */
    private boolean targetPlayers = true;

    /**
     * Фильтр игроков: вайтлист имён.
     * Если вайтлист включён, атакуются только перечисленные игроки.
     * Если выключен — атакуются все игроки (при targetPlayers=true).
     */
    private final TargetFilterData playerFilterData = new TargetFilterData();

    /**
     * Режим поведения вайтлиста (TARGET / IGNORE / FOLLOW).
     * Актуален только когда whitelist включён.
     */
    private WhitelistMode whitelistMode = WhitelistMode.TARGET;

    /**
     * Фильтр "дружественных" блоков-командеров (см. CommanderFilterData).
     * Любой обнаруженный в радиусе командер, чей короткий ID НЕ входит в этот
     * список, становится целью — наравне с враждебными игроками. Список
     * "друзей" НЕ подчиняется whitelistMode (нет режимов IGNORE/FOLLOW для
     * командеров — только простое разделение свой/чужой).
     */
    private final CommanderFilterData commanderFilterData = new CommanderFilterData();

    private int scanCounter     = 0;
    private int guiCheckCounter = 0;

    private boolean triggerSentThisCycle  = false;
    private boolean wasInSubLevelLastTick = false;

    @Nullable private ServerSubLevel cachedSubLevel = null;
    private int subLevelCacheTimer                  = 0;

    // ── Конструктор ───────────────────────────────────────────────────────────

    public MachineSoulBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        for (CommandRole role : CommandRole.values()) {
            slots.put(role, new CommandSlot(role));
        }
    }

    // ── Публичное API (для GUI) ───────────────────────────────────────────────

    public CommandSlot getSlot(CommandRole role) { return slots.get(role); }

    public int getDetectionRadius() { return detectionRadius; }

    public void setDetectionRadius(int radius) {
        this.detectionRadius = Math.max(MIN_DETECTION_RADIUS,
                Math.min(MAX_DETECTION_RADIUS, radius));
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public int getKeepDistance() { return keepDistance; }

    public void setKeepDistance(int distance) {
        this.keepDistance = Math.max(MIN_KEEP_DISTANCE,
                Math.min(MAX_KEEP_DISTANCE, distance));
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public int getStandStillDistance() { return standStillDistance; }

    public void setStandStillDistance(int distance) {
        this.standStillDistance = Math.max(MIN_STAND_STILL_DISTANCE,
                Math.min(MAX_STAND_STILL_DISTANCE, distance));
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void assignSlot(CommandRole role, ItemStack freq0, ItemStack freq1) {
        CommandSlot slot = slots.get(role);
        slot.freq0 = freq0.copy();
        slot.freq1 = freq1.copy();
        deactivateSignal(role);
        setChanged();
        LOGGER.info("[MachineSoul] assignSlot pos={} role={} freq0={} freq1={} | levelClass={}",
                worldPosition, role,
                freq0.isEmpty() ? "EMPTY" : freq0.getItem().toString(),
                freq1.isEmpty() ? "EMPTY" : freq1.getItem().toString(),
                level != null ? level.getClass().getSimpleName() : "null");
        // Явно уведомляем уровень об изменении блока.
        // Когда блок находится внутри Sable SubLevel, Sable перехватывает этот вызов
        // и обновляет свой внутренний NBT-снимок BE. Без этого изменения слотов
        // не попадают в снимок, и данные теряются при следующем hotswap или
        // перезапуске мира.
        if (level != null && !level.isClientSide) {
            LOGGER.info("[MachineSoul] assignSlot sendBlockUpdated → pos={}", worldPosition);
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        } else {
            LOGGER.warn("[MachineSoul] assignSlot: sendBlockUpdated SKIPPED pos={} level={} isClient={}",
                    worldPosition,
                    level == null ? "null" : level.getClass().getSimpleName(),
                    level != null && level.isClientSide);
        }
    }

    public void clearSlot(CommandRole role) {
        CommandSlot slot = slots.get(role);
        slot.freq0 = ItemStack.EMPTY;
        slot.freq1 = ItemStack.EMPTY;
        deactivateSignal(role);
        setChanged();
        LOGGER.info("[MachineSoul] clearSlot pos={} role={}", worldPosition, role);
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // ── Режим поиска цели ────────────────────────────────────────────────────

    public boolean isTargetSearchActive() { return targetSearchActive; }

    /**
     * Включает/выключает поиск цели. При выключении сразу снимаются
     * все активные redstone-link сигналы (движение/огонь).
     */
    public void setTargetSearchActive(boolean active) {
        this.targetSearchActive = active;
        if (!active) deactivateAll();
        setChanged();
        LOGGER.info("[MachineSoul] setTargetSearchActive pos={} -> {}", worldPosition, active);
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public boolean isRequireSubLevel() { return requireSubLevel; }

    /**
     * Включает/выключает режим "Только на физической конструкции".
     * Приоритетнее обычной кнопки активации: пока этот режим включён,
     * targetSearchActive не влияет на работу блока в serverTick — проверяется
     * только наличие cachedSubLevel. Сама обычная кнопка не блокируется и не
     * меняется — пользователь может переключать её, просто она ничего не решает.
     */
    public void setRequireSubLevel(boolean require) {
        this.requireSubLevel = require;
        setChanged();
        LOGGER.info("[MachineSoul] setRequireSubLevel pos={} -> {}", worldPosition, require);
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // ── Таргетинг игроков ────────────────────────────────────────────────────

    public boolean isTargetPlayers() { return targetPlayers; }

    /**
     * Включает/выключает таргетинг игроков. При выключении сразу снимаются
     * все активные redstone-link сигналы (движение/огонь), так как сейчас
     * игроки — единственный тип цели.
     */
    public void setTargetPlayers(boolean enabled) {
        this.targetPlayers = enabled;
        if (!enabled) deactivateAll();
        setChanged();
        LOGGER.info("[MachineSoul] setTargetPlayers pos={} -> {}", worldPosition, enabled);
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // ── Фильтр игроков ───────────────────────────────────────────────────────

    public TargetFilterData getPlayerFilterData() { return playerFilterData; }

    public WhitelistMode getWhitelistMode() { return whitelistMode; }
    public void setWhitelistMode(WhitelistMode mode) { this.whitelistMode = mode; }

    // ── Фильтр командеров ─────────────────────────────────────────────────────

    public CommanderFilterData getCommanderFilterData() { return commanderFilterData; }

    // ── Трекинг зрителей GUI ─────────────────────────────────────────────────

    public void onPlayerOpened(ServerPlayer player) { viewingPlayers.add(player.getUUID()); }
    public void onPlayerSaved(ServerPlayer player)  { viewingPlayers.remove(player.getUUID()); }
    public void onMenuClosed(Player player)          { viewingPlayers.remove(player.getUUID()); }

    // ── MenuProvider ──────────────────────────────────────────────────────────

    @Override
    public Component getDisplayName() {
        return Component.translatable("gui.cbc_autotarget.soul.title");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new MachineSoulMenu(containerId, playerInventory, this);
    }

    // ── Server tick ───────────────────────────────────────────────────────────

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  MachineSoulBlockEntity be) {
        if (level.isClientSide) return;
        if (!(level instanceof ServerLevel sl)) return;

        // ── Кэш SubLevel ─────────────────────────────────────────────────────
        // Обновляем кэш ДО проверки активности — она зависит от него,
        // когда включён режим requireSubLevel.
        if (SableCompat.isAvailable()) {
            if (++be.subLevelCacheTimer >= SUBLEVEL_CACHE_INTERVAL) {
                be.subLevelCacheTimer = 0;
                be.cachedSubLevel = SableCompat.getSubLevelForBlock(sl, pos);
            }
            if (be.cachedSubLevel == null && be.subLevelCacheTimer == 0) {
                be.cachedSubLevel = SableCompat.getSubLevelForBlock(sl, pos);
            }
        }

        // ── Режим поиска цели ────────────────────────────────────────────────
        // Если включён requireSubLevel, обычная кнопка (targetSearchActive)
        // полностью игнорируется: блок работает только когда находится на
        // Sable sub-level (физической конструкции), независимо от того, в каком
        // положении находится обычная кнопка в GUI.
        boolean operational = be.requireSubLevel
                ? (SableCompat.isAvailable() && be.cachedSubLevel != null)
                : (be.targetSearchActive || level.hasNeighborSignal(pos));
        if (!operational) {
            be.deactivateAll();
            return;
        }

        // ── Логика SubLevel (triggerNearestCommander при входе) ───────────────
        if (SableCompat.isAvailable()) {
            boolean inSubLevel = be.cachedSubLevel != null;
            if (inSubLevel && !be.wasInSubLevelLastTick) {
                if (!be.triggerSentThisCycle) {
                    be.triggerSentThisCycle  = true;
                    be.wasInSubLevelLastTick = true;
                    be.triggerNearestCommander(sl);
                }
            } else if (!inSubLevel && be.wasInSubLevelLastTick) {
                be.wasInSubLevelLastTick = false;
                be.triggerSentThisCycle  = false;
            } else {
                be.wasInSubLevelLastTick = inSubLevel;
            }
        }

        // ── GUI-проверка ──────────────────────────────────────────────────────
        if (!be.viewingPlayers.isEmpty()) {
            if (++be.guiCheckCounter >= SCAN_INTERVAL) {
                be.guiCheckCounter = 0;
                be.doGuiLinkCheck(sl);
            }
        } else {
            be.guiCheckCounter = 0;
        }

        // ── Основное сканирование ─────────────────────────────────────────────
        if (++be.scanCounter < SCAN_INTERVAL) return;
        be.scanCounter = 0;
        be.doScan(sl, pos, state);
    }

    // ── Мировая позиция блока ─────────────────────────────────────────────────

    private Vec3 getWorldCenter() {
        Vec3 local = Vec3.atCenterOf(worldPosition);
        if (cachedSubLevel != null) {
            return SableCompat.toWorldPos(cachedSubLevel, local);
        }
        return local;
    }

    private Vec3 getWorldFacingVector(Direction facing) {
        Vec3 local = Vec3.atLowerCornerOf(facing.getNormal());
        if (cachedSubLevel != null) {
            return SableCompat.toWorldVelocity(cachedSubLevel, local).normalize();
        }
        return local;
    }

    /**
     * Возвращает ServerLevel в котором нужно искать игроков.
     * Если блок на SubLevel — игроки находятся в основном мире,
     * поэтому ищем тот уровень сервера где есть SubLevelContainer.
     * Если блок в обычном мире — возвращаем тот же sl.
     */
    private ServerLevel getPlayerSearchLevel(ServerLevel sl) {
        if (!SableCompat.isAvailable() || cachedSubLevel == null) return sl;
        // Блок на SubLevel — ищем основной уровень через сервер
        for (ServerLevel candidate : sl.getServer().getAllLevels()) {
            if (SubLevelContainer.getContainer(candidate) != null) return candidate;
        }
        return sl; // fallback
    }

    // ── Логика сканирования ───────────────────────────────────────────────────

    private void doScan(ServerLevel sl, BlockPos pos, BlockState state) {
        Direction facing = state.hasProperty(MachineSoulBlock.FACING)
                ? state.getValue(MachineSoulBlock.FACING)
                : Direction.SOUTH;

        Vec3 worldCenter = getWorldCenter();
        Vec3 worldFacing = getWorldFacingVector(facing);

        double facingYawDeg = Math.toDegrees(Math.atan2(-worldFacing.x, worldFacing.z));

        // Ищем игроков в правильном уровне (основной мир, даже если блок на SubLevel)
        ServerLevel searchLevel = getPlayerSearchLevel(sl);
        double r = detectionRadius;
        AABB box = AABB.ofSize(worldCenter, r * 2, r * 2, r * 2);
        // Если таргетинг игроков отключён вкладкой Target — считаем, что целей нет.
        // ── Фильтрация игроков по режиму вайтлиста ───────────────────────────
        //
        // Режимы (WhitelistMode) применяются только если вайтлист включён:
        //   TARGET — атаковать только тех кто в списке
        //   IGNORE — атаковать всех КРОМЕ тех кто в списке
        //   FOLLOW — следовать за тем кто в списке; если рядом враг (не в списке) —
        //            переключиться на него как приоритетную боевую цель
        //
        final boolean wlEnabled = targetPlayers && playerFilterData.isWhitelistEnabled();
        final Set<String> wl = playerFilterData.getWhitelist();

        // Все игроки в радиусе (спектаторов исключаем всегда)
        List<ServerPlayer> allPlayers = !targetPlayers ? List.of()
                : searchLevel.getEntitiesOfClass(ServerPlayer.class, box,
                p -> !p.isSpectator() && p.position().distanceTo(worldCenter) <= r);

        // Разбиваем на «в списке» и «не в списке»
        List<ServerPlayer> inList  = new ArrayList<>();
        List<ServerPlayer> outList = new ArrayList<>();
        for (ServerPlayer p : allPlayers) {
            if (wlEnabled && wl.contains(p.getName().getString())) inList.add(p);
            else outList.add(p);
        }

        // Определяем финальный список целей и флаг «режим сопровождения»
        List<ServerPlayer> players;   // кого будем преследовать/атаковать
        boolean followMode;           // true → не стреляем, держимся рядом

        if (!wlEnabled) {
            // Вайтлист выключен — атакуем всех
            players    = allPlayers;
            followMode = false;
        } else {
            switch (whitelistMode) {
                case TARGET -> {
                    // Атакуем только тех кто в списке
                    players    = inList;
                    followMode = false;
                }
                case IGNORE -> {
                    // Атакуем всех КРОМЕ тех кто в списке
                    players    = outList;
                    followMode = false;
                }
                case FOLLOW -> {
                    if (!outList.isEmpty()) {
                        // Есть враг вне списка → переключаемся в боевой режим
                        players    = outList;
                        followMode = false;
                    } else {
                        // Нет врагов → сопровождаем того кто в списке
                        players    = inList;
                        followMode = true;   // не стреляем (пока не найдётся враг ниже)
                    }
                }
                default -> {
                    players    = allPlayers;
                    followMode = false;
                }
            }
        }

        // ── Недружественные командеры как дополнительные кандидаты в цель ────
        // Работают в том же боевом режиме, что и обычные враждебные игроки:
        // Machine Soul сближается с ними (MOVE_*) и ведёт огонь (FIRE) при
        // попадании в сектор обзора. Список "друзей" (commanderFilterData)
        // задаётся по Alliance Key командера (та же строка, что вручную
        // вводится в поле "Alliance Key:" внутри блока командера) — любой
        // командер в радиусе, чей Alliance Key НЕ в списке друзей (включая
        // случай, когда ключ вообще не задан), становится целью.
        //
        // Управляется тумблером "Command Blocks" (TargetCategory.ENEMY_COMMANDERS)
        // на вкладке Target → Filter — тот же флаг, что уже используется
        // ControllerBlockEntity. Раньше этот тумблер отсутствовал в UI, и
        // командеры таргетились безусловно; теперь поведение согласовано.
        //
        // ИСПРАВЛЕНО: раньше это условие было "if (!followMode && ...)" — то
        // есть враждебные командеры вообще НЕ искались, пока Machine Soul
        // находился в режиме сопровождения (FOLLOW, нет врагов-игроков вне
        // списка). Из-за этого сопровождение имело АБСОЛЮТНЫЙ приоритет над
        // атакой командеров — Machine Soul продолжал следовать за игроком,
        // даже если рядом появлялся враждебный Command Block, вместо того
        // чтобы прервать сопровождение и атаковать его — именно то поведение,
        // которое ожидалось (враг должен быть приоритетнее сопровождаемой
        // цели). Теперь враждебные командеры ищутся ВСЕГДА, когда включён
        // тумблер ENEMY_COMMANDERS, независимо от текущего followMode —
        // и, как и с враждебными игроками (outList) чуть выше, их появление
        // прерывает режим сопровождения (followMode сбрасывается в false).
        List<CommanderBlockEntity.CommanderHit> hostileCommanders = List.of();
        if (playerFilterData.isEnabled(TargetCategory.ENEMY_COMMANDERS)) {
            // ВАЖНО: searchLevel здесь уже приведён к ГЛАВНОМУ МИРУ через
            // getPlayerSearchLevel(sl) выше — поэтому selfSubLevel передаём
            // null: командеры в searchLevel.dimension() (ветка 1 метода) —
            // это командеры именно overworld, их координаты уже мировые без
            // всякой конвертации. "Свой" командер на ТОМ ЖЕ корабле, что и
            // этот Machine Soul, найдётся через ветку 2 метода (перебор всех
            // SubLevel'ов главного мира, включая свой собственный корабль).
            List<CommanderBlockEntity.CommanderHit> nearbyCommanders =
                    CommanderBlockEntity.findCommandersInRadius(searchLevel, pos, worldCenter, (int) Math.ceil(r), null);
            if (!nearbyCommanders.isEmpty()) {
                LOGGER.info("[MachineSoul] doScan pos={} findCommandersInRadius found {} commander(s) total (before friendly-filter): [{}]",
                        worldPosition, nearbyCommanders.size(),
                        nearbyCommanders.stream()
                                .map(h -> h.commander.getBlockPos() + " allianceKey='" + h.commander.getAllianceKey() + "' worldPos=" + h.worldPos)
                                .reduce((a, b) -> a + ", " + b).orElse(""));
                hostileCommanders = new ArrayList<>();
                for (CommanderBlockEntity.CommanderHit hit : nearbyCommanders) {
                    if (commanderFilterData.isFriendly(hit.commander.getAllianceKey())) continue;
                    // hit.worldPos уже сконвертирована в мировые координаты
                    // findCommandersInRadius (главный уровень / Sable SubLevel /
                    // Create Contraption) — сравнивать напрямую с worldCenter
                    // корректно независимо от того, на каком корабле физически
                    // стоит командер.
                    double dist = hit.worldPos.distanceTo(worldCenter);
                    if (dist <= r) hostileCommanders.add(hit);
                }
                if (followMode && !hostileCommanders.isEmpty()) {
                    LOGGER.info("[MachineSoul] doScan pos={} hostile commander detected during FOLLOW -> interrupting follow, switching to combat",
                            worldPosition);
                    followMode = false;
                    // ВАЖНО: players в этой ветке всё ещё = inList (сопровождаемый
                    // союзник) — оставлять его так нельзя: ниже followMode=false
                    // включает огонь (inActionFov), и Machine Soul может открыть
                    // стрельбу по СОЮЗНИКУ, если тот случайно окажется ближе
                    // врага. Раз причина выхода из FOLLOW — исключительно
                    // враждебный командер (в outList по-прежнему пусто, иначе
                    // followMode уже был бы false веткой выше), целей-игроков
                    // сейчас нет вообще — обнуляем players, атакуем только
                    // hostileCommanders.
                    players = List.of();
                }
            }
        }

        if (players.isEmpty() && hostileCommanders.isEmpty()) { deactivateAll(); return; }

        // ── Мобы (Hostile Mobs / Passive Mobs) ──────────────────────────────
        // ИСПРАВЛЕНО: категории HOSTILE и PASSIVE существовали как переключатели
        // в TargetFilter (чекбоксы "Hostile Mobs" / "Passive Mobs" в GUI, тот же
        // filterMask, что уже используется для ENEMY_COMMANDERS), но НИКОГДА не
        // были реализованы в самом сканировании MachineSoulBlockEntity — маска
        // сохранялась/применялась только для игроков и командеров. В результате
        // даже с включённой категорией "Hostile Mobs" Machine Soul физически не
        // искал ванильных враждебных мобов (зомби, хасков, скелетов и т.п.) —
        // GUI обещал функциональность, которой не было за ним, хотя нужная
        // классификация УЖЕ существовала и использовалась в ControllerBlockEntity
        // (см. TargetFilterData.isAllowed) — просто никогда не вызывалась отсюда.
        // Переиспользуем тот же isAllowed(entity), а не дублируем логику заново:
        // он уже корректно учитывает Monster/MobCategory/моды/теги/эвристику
        // урона (см. TargetFilterData.isAllowed) для HOSTILE и PASSIVE обеих,
        // и сам проверяет, включена ли соответствующая категория в маске.
        List<LivingEntity> allowedMobs = searchLevel.getEntitiesOfClass(LivingEntity.class, box,
                le -> !(le instanceof Player) && le.isAlive() && !le.isRemoved()
                        && le.position().distanceTo(worldCenter) <= r
                        && playerFilterData.isAllowed(le));

        List<LivingEntity> hostileMobs = new ArrayList<>();
        List<LivingEntity> passiveMobs = new ArrayList<>();
        for (LivingEntity le : allowedMobs) {
            if (isHostileLike(le)) hostileMobs.add(le); else passiveMobs.add(le);
        }

        if (!hostileMobs.isEmpty()) {
            LOGGER.info("[MachineSoul] doScan pos={} found {} hostile mob(s): [{}]",
                    worldPosition, hostileMobs.size(),
                    hostileMobs.stream().map(m -> m.getType().toString() + "@" + m.blockPosition())
                            .reduce((a, b) -> a + ", " + b).orElse(""));
            if (followMode) {
                LOGGER.info("[MachineSoul] doScan pos={} hostile mob detected during FOLLOW -> interrupting follow, switching to combat",
                        worldPosition);
                followMode = false;
                players = List.of();
            }
        }

        if (players.isEmpty() && hostileCommanders.isEmpty()
                && hostileMobs.isEmpty() && passiveMobs.isEmpty()) { deactivateAll(); return; }

        // Ближайшая цель среди игроков, враждебных командеров и мобов —
        // движение работает на 360°, без фильтра по углу. followMode на этом
        // этапе уже сброшен в false, если был обнаружен враждебный командер
        // или враждебный моб (см. выше) — так что ниже followMode=true
        // означает "нет вообще никаких врагов", и Machine Soul действительно
        // просто сопровождает союзника, не стреляя.
        Player  nearestPlayer    = players.stream()
                .min(Comparator.comparingDouble(p -> p.position().distanceToSqr(worldCenter)))
                .orElse(null);
        CommanderBlockEntity.CommanderHit nearestCommander = hostileCommanders.stream()
                .min(Comparator.comparingDouble(h -> h.worldPos.distanceToSqr(worldCenter)))
                .orElse(null);
        LivingEntity nearestHostileMob = hostileMobs.stream()
                .min(Comparator.comparingDouble(m -> m.position().distanceToSqr(worldCenter)))
                .orElse(null);
        LivingEntity nearestPassiveMob = passiveMobs.stream()
                .min(Comparator.comparingDouble(m -> m.position().distanceToSqr(worldCenter)))
                .orElse(null);

        double playerDistSq    = nearestPlayer      != null ? nearestPlayer.position().distanceToSqr(worldCenter)      : Double.MAX_VALUE;
        double commanderDistSq = nearestCommander   != null ? nearestCommander.worldPos.distanceToSqr(worldCenter)     : Double.MAX_VALUE;
        double hostileMobDistSq = nearestHostileMob != null ? nearestHostileMob.position().distanceToSqr(worldCenter)  : Double.MAX_VALUE;

        // ИСПРАВЛЕНО: раньше выбор шёл по чистой дистанции —
        // "nearestCommander != null && commanderDistSq < playerDistSq" —
        // то есть враждебный командер становился целью, ТОЛЬКО ЕСЛИ он
        // физически ближе игрока. По требуемой логике враждебный командер
        // должен быть БЕЗУСЛОВНО приоритетнее сопровождаемого/преследуемого
        // игрока — если он вообще обнаружен (прошёл friendly-фильтр и попал
        // в радиус обзора), он сразу становится первой целью независимо от
        // того, кто физически ближе. Такой же безусловный приоритет теперь
        // и у враждебных мобов — они опаснее сопровождаемого союзника, но
        // "менее приоритетны", чем вражеский Command Block (структурная
        // угроза важнее одиночного моба). Игрок вне списка (реальный враг)
        // становится целью, только если нет ни командеров, ни враждебных
        // мобов. Пассивный моб — самый низкий приоритет, только если вообще
        // больше не на кого навестись (и он вообще нужен только тем, кто
        // явно просил атаковать Passive Mobs).
        Object pickedTarget; // CommanderHit | Mob | Player
        if (nearestCommander != null)      pickedTarget = nearestCommander;
        else if (nearestHostileMob != null) pickedTarget = nearestHostileMob;
        else if (nearestPlayer != null)     pickedTarget = nearestPlayer;
        else if (nearestPassiveMob != null) pickedTarget = nearestPassiveMob;
        else                                 pickedTarget = null;

        if (!hostileCommanders.isEmpty() || !hostileMobs.isEmpty() || nearestPlayer != null || nearestPassiveMob != null) {
            LOGGER.info("[MachineSoul] doScan TARGET SELECT pos={} hostileCommandersFound={} hostileMobsFound={} passiveMobsFound={} nearestPlayer={} playerDistSq={} nearestCommanderWorldPos={} commanderDistSq={} nearestHostileMob={} hostileMobDistSq={} -> picking {}",
                    worldPosition, hostileCommanders.size(), hostileMobs.size(), passiveMobs.size(),
                    nearestPlayer != null ? nearestPlayer.getGameProfile().getName() : "none",
                    playerDistSq == Double.MAX_VALUE ? "N/A" : String.format("%.1f", playerDistSq),
                    nearestCommander != null ? nearestCommander.worldPos : "N/A",
                    commanderDistSq == Double.MAX_VALUE ? "N/A" : String.format("%.1f", commanderDistSq),
                    nearestHostileMob != null ? nearestHostileMob.getType().toString() : "none",
                    hostileMobDistSq == Double.MAX_VALUE ? "N/A" : String.format("%.1f", hostileMobDistSq),
                    pickedTarget == null ? "NONE"
                            : pickedTarget instanceof CommanderBlockEntity.CommanderHit ? "COMMANDER (priority)"
                            : pickedTarget == nearestHostileMob ? "HOSTILE_MOB"
                            : pickedTarget == nearestPlayer ? "PLAYER"
                            : "PASSIVE_MOB");
        }

        final Vec3 toTarget;
        if (pickedTarget instanceof CommanderBlockEntity.CommanderHit hit) {
            toTarget = hit.worldPos.subtract(worldCenter);
        } else if (pickedTarget instanceof Player player) {
            // ВАЖНО: Player — подкласс LivingEntity, поэтому эта проверка
            // должна идти РАНЬШЕ общей "instanceof LivingEntity" ниже —
            // иначе игрок-цель всегда перехватывался бы веткой для мобов
            // (сработало бы, просто с неверной семантикой в логах/коде).
            toTarget = player.position().subtract(worldCenter);
        } else if (pickedTarget instanceof LivingEntity mob) {
            toTarget = mob.position().subtract(worldCenter);
        } else {
            deactivateAll();
            return;
        }
        double targetYawDeg = Math.toDegrees(Math.atan2(-toTarget.x, toTarget.z));
        double yawDiff = wrapDeg(targetYawDeg - facingYawDeg);

        // ── ДВИЖЕНИЕ (360°) ───────────────────────────────────────────────────

        // Горизонтальный поворот — только за пределами мёртвой зоны
        if (Math.abs(yawDiff) > YAW_DEADBAND_DEG) {
            if (yawDiff > 0) {
                setSignalActive(CommandRole.MOVE_LEFT,  true,  sl);
                setSignalActive(CommandRole.MOVE_RIGHT, false, sl);
            } else {
                setSignalActive(CommandRole.MOVE_RIGHT, true,  sl);
                setSignalActive(CommandRole.MOVE_LEFT,  false, sl);
            }
        } else {
            setSignalActive(CommandRole.MOVE_LEFT,  false, sl);
            setSignalActive(CommandRole.MOVE_RIGHT, false, sl);
        }

        // Движение вперёд/назад — с учётом дистанции удержания
        double hDist = Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z);

        // Приоритет 1: keepDistance — цель слишком близко, отступаем назад
        if (keepDistance > 0 && hDist < keepDistance - 1.5) {
            setSignalActive(CommandRole.MOVE_FORWARD,  false, sl);
            setSignalActive(CommandRole.MOVE_BACKWARD, true,  sl);
            setSignalActive(CommandRole.MOVE_UP,       false, sl);
            setSignalActive(CommandRole.MOVE_DOWN,     false, sl);

            // Приоритет 2: standStillDistance — цель в зоне «только поворот»
        } else if (standStillDistance > 0
                && hDist <= standStillDistance + 1.5
                && hDist >= (keepDistance > 0 ? keepDistance - 1.5 : 0.0)) {
            setSignalActive(CommandRole.MOVE_FORWARD,  false, sl);
            setSignalActive(CommandRole.MOVE_BACKWARD, false, sl);
            setSignalActive(CommandRole.MOVE_UP,       false, sl);
            setSignalActive(CommandRole.MOVE_DOWN,     false, sl);

            // Приоритет 3: keepDistance активен, цель дальше зоны удержания — сближаемся
        } else if (keepDistance > 0 && hDist > keepDistance + 1.5) {
            setSignalActive(CommandRole.MOVE_FORWARD,  true,  sl);
            setSignalActive(CommandRole.MOVE_BACKWARD, false, sl);
            double dy3 = toTarget.y;
            double vDeadband3 = 2.0;
            if      (dy3 >  vDeadband3) { setSignalActive(CommandRole.MOVE_UP,   true,  sl); setSignalActive(CommandRole.MOVE_DOWN, false, sl); }
            else if (dy3 < -vDeadband3) { setSignalActive(CommandRole.MOVE_UP,   false, sl); setSignalActive(CommandRole.MOVE_DOWN, true,  sl); }
            else                        { setSignalActive(CommandRole.MOVE_UP,   false, sl); setSignalActive(CommandRole.MOVE_DOWN, false, sl); }

            // Приоритет 4: стандартное сближение (keepDistance=0 и standStillDistance=0 или цель вне FOV)
        } else {
            setSignalActive(CommandRole.MOVE_FORWARD,  hDist > 5.0, sl);
            setSignalActive(CommandRole.MOVE_BACKWARD, false,       sl);
            double dy4 = toTarget.y;
            double vDeadband4 = 2.0;
            if      (dy4 >  vDeadband4) { setSignalActive(CommandRole.MOVE_UP,   true,  sl); setSignalActive(CommandRole.MOVE_DOWN, false, sl); }
            else if (dy4 < -vDeadband4) { setSignalActive(CommandRole.MOVE_UP,   false, sl); setSignalActive(CommandRole.MOVE_DOWN, true,  sl); }
            else                        { setSignalActive(CommandRole.MOVE_UP,   false, sl); setSignalActive(CommandRole.MOVE_DOWN, false, sl); }
        }

        // ── ДЕЙСТВИЕ (±90° от направления блока) ─────────────────────────────
        // В режиме FOLLOW (сопровождение союзника) огонь не ведём
        boolean inActionFov = !followMode && Math.abs(wrapDeg(yawDiff)) <= ACTION_FOV_DEG;
        setSignalActive(CommandRole.FIRE, inActionFov, sl);
    }

    // ── Управление сигналами ─────────────────────────────────────────────────

    private void setSignalActive(CommandRole role, boolean active, ServerLevel sl) {
        if (active) activateSignal(role, sl);
        else deactivateSignal(role);
    }

    private void activateSignal(CommandRole role, ServerLevel sl) {
        CommandSlot slot = slots.get(role);
        if (!slot.isAssigned()) return;
        Couple<Frequency> freq = slot.toFrequency();
        if (freq == null) return;

        BlockPos signalPos = BlockPos.containing(getWorldCenter());

        ActiveSignal existing = activeSignals.get(role);
        if (existing != null && existing.isAlive()) {
            existing.updatePosition(signalPos);
            return;
        }
        if (existing != null) {
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(sl, existing);
            activeSignals.remove(role);
        }
        ActiveSignal signal = new ActiveSignal(signalPos, freq);
        Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(sl, signal);
        activeSignals.put(role, signal);
    }

    private void deactivateSignal(CommandRole role) {
        ActiveSignal signal = activeSignals.remove(role);
        if (signal == null) return;
        signal.kill();
        if (level instanceof ServerLevel sl) {
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(sl, signal);
        }
    }

    private void deactivateAll() {
        for (CommandRole role : CommandRole.values()) deactivateSignal(role);
    }

    // ── Очистка ───────────────────────────────────────────────────────────────

    @Override
    public void setRemoved() {
        deactivateAll();
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        deactivateAll();
        super.onChunkUnloaded();
    }

    // ── GUI: проверка линков ──────────────────────────────────────────────────

    private void doGuiLinkCheck(ServerLevel sl) {
        Map<CommandRole, Boolean> result = new EnumMap<>(CommandRole.class);

        for (CommandRole role : CommandRole.values()) {
            CommandSlot slot = slots.get(role);
            boolean found = false;
            if (slot.isAssigned()) {
                Couple<Frequency> freq = slot.toFrequency();
                if (freq != null) {
                    found = isLinkPresentInRadius(sl, freq, LINK_SEARCH_RADIUS);
                }
            }
            result.put(role, found);
        }

        SyncMachineSoulStatusPacket packet = new SyncMachineSoulStatusPacket(result);
        Iterator<UUID> it = viewingPlayers.iterator();
        while (it.hasNext()) {
            UUID uuid = it.next();
            ServerPlayer sp = sl.getServer().getPlayerList().getPlayer(uuid);
            if (sp == null) { it.remove(); continue; }
            PacketDistributor.sendToPlayer(sp, packet);
        }
    }

    private boolean isLinkPresentInRadius(ServerLevel sl, Couple<Frequency> freq, int radius) {
        IRedstoneLinkable probe = new IRedstoneLinkable() {
            @Override public int getTransmittedStrength()        { return 0; }
            @Override public void setReceivedStrength(int power) { }
            @Override public boolean isListening()               { return false; }
            @Override public boolean isAlive()                   { return false; }
            @Override public BlockPos getLocation()              { return worldPosition; }
            @Override public Couple<Frequency> getNetworkKey()   { return freq; }
        };

        Set<IRedstoneLinkable> network =
                Create.REDSTONE_LINK_NETWORK_HANDLER.getNetworkOf(sl, probe);
        if (network == null || network.isEmpty()) return false;

        double radiusSq = (double) radius * radius;
        Vec3 center = getWorldCenter();

        for (IRedstoneLinkable link : network) {
            if (link == probe || !link.isAlive()) continue;
            BlockPos lp = link.getLocation();
            double dx = lp.getX() - center.x;
            double dy = lp.getY() - center.y;
            double dz = lp.getZ() - center.z;
            if (dx*dx + dy*dy + dz*dz <= radiusSq) return true;
        }
        return false;
    }

    // ── Поиск Commander ───────────────────────────────────────────────────────

    /**
     * Является ли эта (уже прошедшая playerFilterData.isAllowed) сущность
     * "враждебной" для целей приоритезации выбора цели, а не мирной.
     * Отражает ту же классификацию, что и TargetFilterData.isAllowed (Monster /
     * MobCategory.MONSTER / CBCAutoTargetTags.TARGETED_ENTITIES / эвристика по
     * ATTACK_DAMAGE для нестандартных модовых мобов) — но здесь она нужна не
     * для решения "разрешить/запретить", а только для того, чтобы отличить
     * "опасного" моба (прерывает FOLLOW, приоритетнее преследуемого игрока)
     * от "мирного" (самый низкий приоритет цели).
     */
    private static boolean isHostileLike(LivingEntity entity) {
        if (entity instanceof Monster) return true;
        MobCategory cat = entity.getType().getCategory();
        if (cat == MobCategory.MONSTER) return true;
        if (entity.getType().is(com.yourname.cbcautotarget.CBCAutoTargetTags.TARGETED_ENTITIES)) return true;
        if (entity instanceof net.minecraft.world.entity.animal.Animal
                || entity instanceof net.minecraft.world.entity.npc.AbstractVillager) return false;
        if (cat == MobCategory.CREATURE || cat == MobCategory.AMBIENT
                || cat == MobCategory.WATER_CREATURE || cat == MobCategory.WATER_AMBIENT
                || cat == MobCategory.UNDERGROUND_WATER_CREATURE) return false;
        var attackAttr = entity.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        return attackAttr != null && attackAttr.getBaseValue() > 0.0;
    }

    private void triggerNearestCommander(ServerLevel sl) {
        Vec3 worldCenter = getWorldCenter();
        // ВАЖНО: sl здесь — уровень, в котором ФИЗИЧЕСКИ тикается этот блок
        // (для блока на Sable-корабле это сам SubLevel, а НЕ главный мир).
        // findCommandersInRadius ожидает главный уровень как searchLevel
        // (для веток "главный мир" и "обход всех SubLevel'ов" через
        // SubLevelContainer.getContainer) — так же, как это уже делает
        // doScan() через getPlayerSearchLevel(sl). Без этого приведения
        // здесь повторился бы тот же баг с неверной точкой отсчёта.
        ServerLevel searchLevel = getPlayerSearchLevel(sl);
        // selfSubLevel=null: searchLevel уже приведён к главному миру (см.
        // комментарий в doScan) — свой корабль найдётся через ветку 2 метода.
        List<CommanderBlockEntity.CommanderHit> commanders =
                CommanderBlockEntity.findCommandersInRadius(searchLevel, worldPosition, worldCenter, 32, null);
        if (commanders.isEmpty()) return;
        // ИСПРАВЛЕНО: раньше сравнение шло через a.getBlockPos().distSqr(worldPosition) —
        // то есть ЛОКАЛЬНЫЕ координаты командера против ЛОКАЛЬНЫХ координат этого
        // Machine Soul. Если хотя бы один из двух стоит на другом корабле/SubLevel,
        // это сравнение бессмысленно (разные системы отсчёта). Используем
        // hit.worldPos, уже сконвертированную findCommandersInRadius.
        commanders.stream()
                .min(Comparator.comparingDouble(h -> h.worldPos.distanceToSqr(worldCenter)))
                .map(h -> h.commander)
                .ifPresent(CommanderBlockEntity::broadcastActivate);
    }

    // ── NBT ───────────────────────────────────────────────────────────────────

    // Резервная копия слотов — обновляется при КАЖДОМ loadAdditional/handleUpdateTag
    // с реальными данными (тег содержит CommandSlots).
    //
    // Назначение: защита от двойного вызова loadAdditional при деплое схематики Create.
    //
    // Порядок вызовов Create при deploy схематики:
    //   1. loadAdditional(тег из схематики)     — CommandSlots присутствует → слоты загружены,
    //                                             schematicBackup = тег
    //   2. onLoad()                              — блок помещён в мир; резерв не трогаем
    //   3. loadAdditional(пустой тег)            — Create перезаписывает → резерв != null →
    //                                             восстанавливаем слоты из резерва; резерв НЕ очищаем
    //   4. handleUpdateTag(тег SafeNbtWriter)    — CommandSlots присутствует → слоты обновлены,
    //                                             schematicBackup обновлён; резерв НЕ очищаем
    //   5. writeSafeNbt()                        — ЕДИНСТВЕННОЕ место, где резерв обнуляется
    //
    // Важно: handleUpdateTag НЕ обнуляет резерв — это было первопричиной потери данных.
    // Если handleUpdateTag вызывался до п.3 (с тегом без CommandSlots), старый код
    // уничтожал резерв, и п.3 уже не мог восстановить слоты.
    @Nullable private CompoundTag schematicBackup = null;
    private HolderLookup.Provider schematicBackupRegistries = null;

    /** Вызывается из SafeNbtWriter (CBCAutoTarget.commonSetup) — направление BE→tag. */
    public void writeSafeNbt(CompoundTag tag, HolderLookup.Provider registries) {
        LOGGER.info("[MachineSoul] writeSafeNbt CALLED pos={} | slotsBefore={} backupHeld={}",
                worldPosition, describeSlots(), schematicBackup != null);
        saveSlotsToTag(tag, registries);
        tag.putInt("DetectionRadius", detectionRadius);
        tag.putInt("KeepDistance", keepDistance);
        tag.putInt("StandStillDistance", standStillDistance);
        // Принудительно: блок, размещённый из блюпринта тулгана, всегда стартует
        // с включённым поиском цели — независимо от состояния на момент сохранения.
        tag.putBoolean("SearchActive", true);
        // Режим "Только на физической конструкции" сохраняется как есть —
        // это настройка поведения блока, а не его текущей активности.
        tag.putBoolean("RequireSubLevel", requireSubLevel);
        // Таргетинг игроков сохраняется как есть (не форсируется).
        tag.putBoolean("TargetPlayers", targetPlayers);
        // Фильтр игроков (вайтлист).
        {
            CompoundTag pf = new CompoundTag();
            playerFilterData.saveToNBT(pf);
            pf.putInt("WhitelistMode", whitelistMode.id());
            tag.put("PlayerFilter", pf);
        }
        // Фильтр дружественных командеров.
        {
            CompoundTag cf = new CompoundTag();
            commanderFilterData.saveToNBT(cf);
            tag.put("CommanderFilter", cf);
        }
        // Резерв больше не нужен — SafeNbtWriter вызывается последним при деплое.
        schematicBackup = null;
        schematicBackupRegistries = null;
        LOGGER.info("[MachineSoul] writeSafeNbt DONE pos={} | tagKeys={}", worldPosition, tag.getAllKeys());
    }

    private void saveSlotsToTag(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag slotsTag = new ListTag();
        for (CommandSlot slot : slots.values()) slotsTag.add(slot.save(registries));
        tag.put("CommandSlots", slotsTag);
    }

    private void loadSlotsFromTag(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.contains("CommandSlots", Tag.TAG_LIST)) {
            ListTag slotsTag = tag.getList("CommandSlots", Tag.TAG_COMPOUND);
            for (int i = 0; i < slotsTag.size(); i++) {
                CompoundTag entry = slotsTag.getCompound(i);
                // Безопасно пропускаем роли которых нет в текущей версии enum
                // (совместимость при даунгрейде или загрузке старых миров)
                String roleName = entry.getString("Role");
                CommandRole role;
                try {
                    role = CommandRole.valueOf(roleName);
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("[MachineSoul] Unknown CommandRole '{}' in NBT — skipped", roleName);
                    continue;
                }
                CommandSlot slot = CommandSlot.load(entry, registries);
                slots.put(slot.role, slot);
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        saveSlotsToTag(tag, registries);
        tag.putInt("DetectionRadius", detectionRadius);
        tag.putInt("KeepDistance", keepDistance);
        tag.putInt("StandStillDistance", standStillDistance);
        tag.putBoolean("SearchActive", targetSearchActive);
        tag.putBoolean("RequireSubLevel", requireSubLevel);
        tag.putBoolean("TargetPlayers", targetPlayers);
        {
            CompoundTag pf = new CompoundTag();
            playerFilterData.saveToNBT(pf);
            pf.putInt("WhitelistMode", whitelistMode.id());
            tag.put("PlayerFilter", pf);
        }
        {
            CompoundTag cf = new CompoundTag();
            commanderFilterData.saveToNBT(cf);
            tag.put("CommanderFilter", cf);
        }
        LOGGER.info("[MachineSoul] saveAdditional pos={} slots=[{}] radius={} searchActive={} targetPlayers={} filterMask={}",
                worldPosition, describeSlots(), detectionRadius, targetSearchActive, targetPlayers, playerFilterData.getMask());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        boolean hasSlots = tag.contains("CommandSlots", Tag.TAG_LIST);
        LOGGER.info("[MachineSoul] loadAdditional pos={} hasCommandSlots={} tagKeys={} backupHeld={} | caller={}",
                worldPosition, hasSlots, tag.getAllKeys(), schematicBackup != null,
                callerClassName());
        if (hasSlots) {
            // Тег содержит реальные данные — загружаем и сохраняем резервную копию.
            loadSlotsFromTag(tag, registries);
            if (tag.contains("DetectionRadius")) {
                detectionRadius = tag.getInt("DetectionRadius");
            }
            if (tag.contains("KeepDistance")) {
                keepDistance = tag.getInt("KeepDistance");
            }
            if (tag.contains("StandStillDistance")) {
                standStillDistance = tag.getInt("StandStillDistance");
            }
            // Отсутствие ключа (старые сохранения/блюпринты без этого поля) → по умолчанию включено.
            targetSearchActive = !tag.contains("SearchActive") || tag.getBoolean("SearchActive");
            requireSubLevel = tag.contains("RequireSubLevel") && tag.getBoolean("RequireSubLevel");
            targetPlayers = !tag.contains("TargetPlayers") || tag.getBoolean("TargetPlayers");
            if (tag.contains("PlayerFilter", Tag.TAG_COMPOUND)) {
                CompoundTag pf = tag.getCompound("PlayerFilter");
                playerFilterData.loadFromNBT(pf);
                whitelistMode = pf.contains("WhitelistMode")
                        ? WhitelistMode.fromId(pf.getInt("WhitelistMode"))
                        : WhitelistMode.TARGET;
            }
            if (tag.contains("CommanderFilter", Tag.TAG_COMPOUND)) {
                commanderFilterData.loadFromNBT(tag.getCompound("CommanderFilter"));
            }
            schematicBackup = tag.copy();
            schematicBackupRegistries = registries;
            LOGGER.info("[MachineSoul] loadAdditional → REAL DATA path pos={} slots=[{}] radius={} searchActive={} targetPlayers={} filterMask={} hadPlayerFilterTag={}",
                    worldPosition, describeSlots(), detectionRadius, targetSearchActive, targetPlayers,
                    playerFilterData.getMask(), tag.contains("PlayerFilter", Tag.TAG_COMPOUND));
        } else if (schematicBackup != null) {
            // Пустой тег пришёл ПОСЛЕ того как мы уже загрузили данные.
            // Create вызвал второй loadAdditional при деплое — восстанавливаем из резерва.
            LOGGER.info("[MachineSoul] loadAdditional → BACKUP RESTORE path pos={} backupSlots=[{}]",
                    worldPosition, describeSlotsFromTag(schematicBackup, registries));
            loadSlotsFromTag(schematicBackup, schematicBackupRegistries);
            if (schematicBackup.contains("DetectionRadius")) {
                detectionRadius = schematicBackup.getInt("DetectionRadius");
            }
            if (schematicBackup.contains("KeepDistance")) {
                keepDistance = schematicBackup.getInt("KeepDistance");
            }
            if (schematicBackup.contains("StandStillDistance")) {
                standStillDistance = schematicBackup.getInt("StandStillDistance");
            }
            targetSearchActive = !schematicBackup.contains("SearchActive") || schematicBackup.getBoolean("SearchActive");
            requireSubLevel = schematicBackup.contains("RequireSubLevel") && schematicBackup.getBoolean("RequireSubLevel");
            targetPlayers = !schematicBackup.contains("TargetPlayers") || schematicBackup.getBoolean("TargetPlayers");
            if (schematicBackup.contains("PlayerFilter", Tag.TAG_COMPOUND)) {
                CompoundTag pf = schematicBackup.getCompound("PlayerFilter");
                playerFilterData.loadFromNBT(pf);
                whitelistMode = pf.contains("WhitelistMode")
                        ? WhitelistMode.fromId(pf.getInt("WhitelistMode"))
                        : WhitelistMode.TARGET;
            }
            if (schematicBackup.contains("CommanderFilter", Tag.TAG_COMPOUND)) {
                commanderFilterData.loadFromNBT(schematicBackup.getCompound("CommanderFilter"));
            }
            LOGGER.info("[MachineSoul] loadAdditional → BACKUP RESTORE done pos={} slots=[{}] searchActive={} targetPlayers={} filterMask={}",
                    worldPosition, describeSlots(), targetSearchActive, targetPlayers, playerFilterData.getMask());
        } else {
            // Ни данных, ни резерва — новый пустой блок или проблема.
            LOGGER.warn("[MachineSoul] loadAdditional → NO DATA, NO BACKUP pos={} — slots remain as-is: [{}]",
                    worldPosition, describeSlots());
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // Намеренно НЕ сбрасываем schematicBackup здесь.
        // onLoad() вызывается между двумя loadAdditional при деплое схематики,
        // резерв должен дожить до второго loadAdditional и до writeSafeNbt().
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveSlotsToTag(tag, registries);
        tag.putInt("DetectionRadius", detectionRadius);
        tag.putInt("KeepDistance", keepDistance);
        tag.putInt("StandStillDistance", standStillDistance);
        // Принудительно: при размещении из .nbt-структуры (Create-схематика/принтер)
        // блок стартует с включённым поиском цели — независимо от состояния на
        // момент сохранения. На клиентский GUI это не влияет: статус кнопки
        // синхронизируется отдельно, через OpenMachineSoulHomePacket.
        tag.putBoolean("SearchActive", true);
        tag.putBoolean("RequireSubLevel", requireSubLevel);
        tag.putBoolean("TargetPlayers", targetPlayers);
        {
            CompoundTag pf = new CompoundTag();
            playerFilterData.saveToNBT(pf);
            pf.putInt("WhitelistMode", whitelistMode.id());
            tag.put("PlayerFilter", pf);
        }
        {
            CompoundTag cf = new CompoundTag();
            commanderFilterData.saveToNBT(cf);
            tag.put("CommanderFilter", cf);
        }
        LOGGER.info("[MachineSoul] getUpdateTag pos={} slots=[{}] filterMask={} whitelistMode={}",
                worldPosition, describeSlots(), playerFilterData.getMask(), whitelistMode);
        return tag;
    }

    /**
     * Без этого переопределения getUpdatePacket() возвращает null (поведение по умолчанию),
     * и sendBlockUpdated() не отправляет клиенту НИКАКИХ данных блока.
     * ClientLevel BE остаётся с пустыми слотами навсегда.
     * Create при создании схематики читает из ClientLevel → получает EMPTY.
     *
     * Это переопределение гарантирует что после каждого вызова sendBlockUpdated()
     * клиент получает ClientboundBlockEntityDataPacket с данными getUpdateTag()
     * (в котором есть CommandSlots), и handleUpdateTag() правильно обновляет
     * ClientLevel BE нужными данными.
     */
    @Override
    @Nullable
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        boolean hasSlots = tag.contains("CommandSlots", Tag.TAG_LIST);
        LOGGER.info("[MachineSoul] handleUpdateTag pos={} hasCommandSlots={} tagKeys={} backupHeld={} | caller={}",
                worldPosition, hasSlots, tag.getAllKeys(), schematicBackup != null,
                callerClassName());
        // НЕ вызываем super.handleUpdateTag() — базовый метод вызывает loadAdditional(tag),
        // что взаимодействует с логикой schematicBackup в loadAdditional и может
        // непредсказуемо затронуть резерв во время окна деплоя схематики.
        //
        // Правила:
        //   • Если tag содержит CommandSlots — загружаем слоты и обновляем резерв
        //     (это либо финальный SafeNbtWriter тег, либо штатная синхронизация).
        //   • Если tag НЕ содержит CommandSlots — ничего не трогаем. В частности,
        //     НЕ обнуляем schematicBackup: он должен дожить до финального
        //     loadAdditional(пустой тег) при деплое.
        //   • Очищать schematicBackup разрешено ТОЛЬКО в writeSafeNbt().
        if (tag.contains("CommandSlots", Tag.TAG_LIST)) {
            loadSlotsFromTag(tag, registries);
            if (tag.contains("DetectionRadius")) {
                detectionRadius = tag.getInt("DetectionRadius");
            }
            if (tag.contains("KeepDistance")) {
                keepDistance = tag.getInt("KeepDistance");
            }
            if (tag.contains("StandStillDistance")) {
                standStillDistance = tag.getInt("StandStillDistance");
            }
            targetSearchActive = !tag.contains("SearchActive") || tag.getBoolean("SearchActive");
            requireSubLevel = tag.contains("RequireSubLevel") && tag.getBoolean("RequireSubLevel");
            targetPlayers = !tag.contains("TargetPlayers") || tag.getBoolean("TargetPlayers");
            if (tag.contains("PlayerFilter", Tag.TAG_COMPOUND)) {
                CompoundTag pf = tag.getCompound("PlayerFilter");
                playerFilterData.loadFromNBT(pf);
                whitelistMode = pf.contains("WhitelistMode")
                        ? WhitelistMode.fromId(pf.getInt("WhitelistMode"))
                        : WhitelistMode.TARGET;
            }
            if (tag.contains("CommanderFilter", Tag.TAG_COMPOUND)) {
                commanderFilterData.loadFromNBT(tag.getCompound("CommanderFilter"));
            }
            // Обновляем резерв, чтобы он всегда отражал последнее известное
            // состояние с реальными данными.
            schematicBackup = tag.copy();
            schematicBackupRegistries = registries;
            LOGGER.info("[MachineSoul] handleUpdateTag → SLOTS LOADED pos={} slots=[{}] searchActive={} targetPlayers={} filterMask={} hadPlayerFilterTag={}",
                    worldPosition, describeSlots(), targetSearchActive, targetPlayers,
                    playerFilterData.getMask(), tag.contains("PlayerFilter", Tag.TAG_COMPOUND));
        } else {
            // ВАЖНО: если tag не содержит CommandSlots, весь этот блок (включая
            // загрузку PlayerFilter/маски!) пропускается целиком — даже если
            // PlayerFilter в теге присутствует. Если когда-нибудь появится путь
            // synced-обновления БЕЗ CommandSlots в теге, маска фильтра тут молча
            // "потеряется" на клиенте, хотя формально была отправлена сервером.
            LOGGER.warn("[MachineSoul] handleUpdateTag → SKIPPED (no CommandSlots) pos={} slots unchanged=[{}] "
                            + "tagHadPlayerFilter={} (IGNORED because CommandSlots missing) currentFilterMask={}",
                    worldPosition, describeSlots(),
                    tag.contains("PlayerFilter", Tag.TAG_COMPOUND), playerFilterData.getMask());
        }
        // schematicBackup намеренно НЕ обнуляется.
        // Только writeSafeNbt() очищает его по завершении деплоя.
    }

    // ── Вспомогательные ───────────────────────────────────────────────────────

    /**
     * Возвращает строку вида "FIRE:item1+item2 MOVE_FORWARD:EMPTY MOVE_LEFT:item3+EMPTY ..."
     * для записи в лог. Показывает реальное содержимое слотов в памяти.
     */
    private String describeSlots() {
        StringBuilder sb = new StringBuilder();
        for (CommandRole role : CommandRole.values()) {
            CommandSlot s = slots.get(role);
            if (sb.length() > 0) sb.append(", ");
            sb.append(role.name()).append(":[");
            sb.append(s.freq0.isEmpty() ? "EMPTY" : s.freq0.getItem().toString());
            sb.append("+");
            sb.append(s.freq1.isEmpty() ? "EMPTY" : s.freq1.getItem().toString());
            sb.append("]");
        }
        return sb.toString();
    }

    /**
     * То же самое, но читает слоты из произвольного тега (не из текущего состояния).
     * Используется для описания содержимого schematicBackup в логах.
     */
    private String describeSlotsFromTag(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.contains("CommandSlots", Tag.TAG_LIST)) return "<no CommandSlots in tag>";
        StringBuilder sb = new StringBuilder();
        ListTag list = tag.getList("CommandSlots", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            String role = entry.getString("Role");
            ItemStack f0 = ItemStack.parseOptional(registries, entry.getCompound("Freq0"));
            ItemStack f1 = ItemStack.parseOptional(registries, entry.getCompound("Freq1"));
            if (sb.length() > 0) sb.append(", ");
            sb.append(role).append(":[");
            sb.append(f0.isEmpty() ? "EMPTY" : f0.getItem().toString());
            sb.append("+");
            sb.append(f1.isEmpty() ? "EMPTY" : f1.getItem().toString());
            sb.append("]");
        }
        return sb.toString();
    }

    /**
     * Возвращает имя класса непосредственного вызывающего (2 уровня выше callerClassName).
     * Используется в логах, чтобы понять, кто именно вызвал loadAdditional / handleUpdateTag.
     */
    private static String callerClassName() {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        // [0]=getStackTrace, [1]=callerClassName, [2]=logging site, [3]=actual caller
        if (st.length > 3) {
            StackTraceElement e = st[3];
            return e.getClassName() + "." + e.getMethodName() + ":" + e.getLineNumber();
        }
        return "unknown";
    }

    private static double wrapDeg(double deg) {
        deg = deg % 360.0;
        if (deg > 180.0)   deg -= 360.0;
        if (deg <= -180.0) deg += 360.0;
        return deg;
    }

    // ── ActiveSignal ──────────────────────────────────────────────────────────

    private static class ActiveSignal implements IRedstoneLinkable {
        private BlockPos pos;
        private final Couple<Frequency> freq;
        private boolean alive = true;

        ActiveSignal(BlockPos pos, Couple<Frequency> freq) {
            this.pos  = pos;
            this.freq = freq;
        }

        void updatePosition(BlockPos pos) { this.pos = pos; }
        void kill()                        { this.alive = false; }

        @Override public int getTransmittedStrength()        { return alive ? 15 : 0; }
        @Override public void setReceivedStrength(int power) { }
        @Override public boolean isListening()               { return false; }
        @Override public boolean isAlive()                   { return alive; }
        @Override public BlockPos getLocation()              { return pos; }
        @Override public Couple<Frequency> getNetworkKey()   { return freq; }
    }
}