package com.yourname.cbcautotarget.blockentity;

import com.yourname.cbcautotarget.CBCAutoTarget;
import com.yourname.cbcautotarget.filter.TargetCategory;
import com.yourname.cbcautotarget.filter.TargetFilterData;
import com.yourname.cbcautotarget.CBCAutoTargetConfig;
import com.yourname.cbcautotarget.block.ControllerBlock;
import com.yourname.cbcautotarget.compat.SableCompat;
import com.yourname.cbcautotarget.menu.ControllerMenu;
import com.yourname.cbcautotarget.network.SyncWhitelistPacket;
import com.yourname.cbcautotarget.util.BallisticSolver;
import com.yourname.cbcautotarget.util.BigCannonBreechFeeder;
import com.yourname.cbcautotarget.util.LineOfSightUtil;
import com.yourname.cbcautotarget.util.ShipAimSolver;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.network.PacketDistributor;
import rbasamoyai.createbigcannons.cannon_control.ControlPitchContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedBigCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannons.CannonContraptionProviderBlock;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.AssemblyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ControllerBlockEntity extends BlockEntity implements MenuProvider, ControlPitchContraption.Block {


    public  static final double PITCH_TOLERANCE         = 1.0;
    private static final int    INVENTORY_SIZE           = 27;
    private static final int    TRANSFER_INTERVAL        = 10;
    private static final int    MIN_FIRE_COOLDOWN        = 20;
    private static final int    REQUIRED_ALIGNED_TICKS   = 3;
    // Адаптивная надбавка к допуску наведения по манёвренным (быстро
    // меняющим требуемый угол) целям. Пока цель бежит вокруг пушки — особенно
    // на короткой дистанции, где та же линейная скорость даёт куда большую
    // угловую скорость требуемого наведения — жёсткий фиксированный допуск
    // (YAW_TOLERANCE/PITCH_TOLERANCE) почти никогда не выполняется одновременно
    // REQUIRED_ALIGNED_TICKS тиков подряд: alignedTicks постоянно сбрасывается
    // в 0, и орудие «наводится, но не стреляет» бесконечно. Расширяем допуск
    // пропорционально скорости изменения wantedYaw/wantedPitch между тиками,
    // ограничивая надбавку разумным потолком, чтобы не стрелять совсем мимо.
    private static final double AIM_RATE_TOLERANCE_GAIN = 0.5;
    private static final double AIM_RATE_TOLERANCE_MAX  = 4.0;
    private static final int    LOS_GRACE_TICKS_MAX      = 5;
    private static final int    SUBLEVEL_CACHE_INTERVAL  = 40;
    // LOS-проверка раз в N тиков вместо каждого тика — главное исправление лагов с Sable
    private static final int    LOS_CHECK_INTERVAL       = 10;

    // ── Yaw state (was YawBlockEntity) ───────────────────────────────────────
    private static final double YAW_DEADBAND_DEG    = 0.15;
    // Физический лимит скорости доворота ствола. Поднят с исходных 9.6°/тик:
    // по диагностическим логам (см. [AimGate]) при близкой (2-5 блоков)
    // спиральной цели требуемая угловая скорость wantedYaw доходила до
    // 15-38°/тик — многократно выше старого лимита, из-за чего ствол
    // безнадёжно отставал вне зависимости от качества упреждения/допусков
    // (Traverse-Compensated Lead и адаптивный AIM_RATE_TOLERANCE не могут
    // компенсировать чисто кинематическое ограничение). 24°/тик = полный
    // оборот ствола за ~15 тиков (0.75 сек) — перекрывает типичный диапазон
    // близких манёвров, оставаясь физически правдоподобным для CBC-пушки.
    private static final float  YAW_MAX_DEG_PER_TICK = 24.0f;

    // ── Pitch state (was PitchBlockEntity) ───────────────────────────────────
    private static final float PITCH_DEADBAND_DEG     = 0.1f;
    // См. пояснение у YAW_MAX_DEG_PER_TICK — тот же кинематический предел
    // применяется симметрично к вертикальной оси наведения.
    private static final float PITCH_MAX_DEG_PER_TICK = 24.0f;

    // ── Rotation axis permissions ─────────────────────────────────────────────
    /** Разрешено ли горизонтальное вращение (yaw). По умолчанию включено. */
    private boolean allowHorizontal = true;
    /** Разрешено ли вертикальное вращение (pitch). По умолчанию включено. */
    private boolean allowVertical   = true;

    // ── Fire trigger frequency (синхронный залп по частоте) ────────────────────
    /**
     * Частота синхронного огня. 0 = отключено (частота не задана).
     * Диапазон 1-9999. Контроллеры с одинаковой (ненулевой) частотой,
     * находящиеся в радиусе {@link #FIRE_FREQUENCY_RADIUS} блоков друг от
     * друга, синхронизируют момент открытия огня: как только один из них
     * реально стреляет по своей цели, все остальные с той же частотой
     * тоже получают команду на открытие огня, даже если сами ни на кого
     * не навелись.
     */
    private int fireFrequency = 0;

    /** Радиус (в блоках, по прямой) для срабатывания синхронного огня по частоте. */
    private static final double FIRE_FREQUENCY_RADIUS    = 5.0;
    private static final double FIRE_FREQUENCY_RADIUS_SQ = FIRE_FREQUENCY_RADIUS * FIRE_FREQUENCY_RADIUS;

    // ── General state ─────────────────────────────────────────────────────────
    private boolean active         = false;

    // ══════════════════════════════════════════════════════════════════════════
    // ── Per-mount state ──────────────────────────────────────────────────────
    // Each cannon attached to the controller has its own independent
    // yaw/pitch, fire/cooldown, alignment, and ballistic aim cache.
    // Target selection (currentTargetUUID / commanderTargetPos) and smoothed
    // velocity tracking are shared controller-level — all mounts aim at the
    // same target but fire independently when individually aligned.
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Per-mount state for a single cannon attached to this controller.
     * Replaces the former scalar fields (mountedContraption, cannonYaw, etc.)
     * to support multiple cannons on different sides of the controller.
     */
    private static class MountState {
        PitchOrientedContraptionEntity contraption;
        /** Side of the controller block that this cannon is mounted on. */
        @Nullable Direction mountFacing;
        /** Position of the breech block from which this contraption was assembled. */
        @Nullable BlockPos assembledFromPos;

        float cannonYaw, cannonPitch, prevCannonYaw, prevCannonPitch;
        float targetYaw = 0f, targetPitch = 0f;
        boolean yawDirty = false, pitchDirty = false;

        boolean fireRequested = false, cancelRequested = false, broadcastFireRequested = false;
        int fireCooldown = 0;
        int alignedTicks = 0;

        boolean hasPrevWantedAim = false;
        float prevWantedYaw = 0f, prevWantedPitch = 0f;

        // ── Ballistic aim cache (entity target) ────────────────────────────
        @Nullable double[] entityAimCache;
        @Nullable Vec3 entityAimCacheMuzzle, entityAimCacheTarget, entityAimCacheRelVel;
        int entityAimCacheAge = 0;

        // ── Ballistic aim cache (commander target) ─────────────────────────
        @Nullable double[] cmdAimCache;
        @Nullable Vec3 cmdAimCacheMuzzle, cmdAimCacheTarget;
        int cmdAimCacheAge = 0;

        MountState(PitchOrientedContraptionEntity contraption,
                   @Nullable Direction mountFacing,
                   @Nullable BlockPos assembledFromPos) {
            this.contraption = contraption;
            this.mountFacing = mountFacing;
            this.assembledFromPos = assembledFromPos;
        }

        /**
         * Стартовые углы нового MountState. Раньше MountState, созданный из attach()
         * (клиент, либо сервер после перезагрузки мира), стартовал с yaw=0/pitch=0 —
         * клиентский applyRotation() тут же выставлял контрапшену этот нулевой угол,
         * и ВСЕ такие пушки визуально смотрели в одну сторону, хотя сервер стрелял
         * по настоящим углам. Теперь берём сохранённые/присланные углы, иначе
         * реальные углы самого контрапшена.
         */
        void initAngles(@Nullable float[] saved) {
            if (saved != null) {
                cannonYaw = saved[0];
                cannonPitch = saved[1];
            } else if (contraption != null) {
                cannonYaw = contraption.yaw;
                cannonPitch = contraption.pitch * getContraptionSign();
            }
            prevCannonYaw = cannonYaw;
            prevCannonPitch = cannonPitch;
        }

        void invalidateEntityAimCache() {
            entityAimCache = null;
            entityAimCacheMuzzle = null;
            entityAimCacheTarget = null;
            entityAimCacheRelVel = null;
            entityAimCacheAge = 0;
        }

        void invalidateCmdAimCache() {
            cmdAimCache = null;
            cmdAimCacheMuzzle = null;
            cmdAimCacheTarget = null;
            cmdAimCacheAge = 0;
        }

        void invalidateAllCaches() {
            invalidateEntityAimCache();
            invalidateCmdAimCache();
        }

        void doRequestFire() {
            fireRequested = true;
            cancelRequested = false;
        }

        void doCancelFire() {
            fireRequested = false;
            cancelRequested = true;
        }

        Direction getContraptionDirection() {
            return contraption == null ? Direction.NORTH : contraption.getInitialOrientation();
        }

        Direction getMountFacing() {
            return mountFacing != null ? mountFacing : getContraptionDirection();
        }

        /**
         * See CannonMountBlockEntity.applyRotation() in CBC source.
         */
        float getContraptionSign() {
            Direction d = getContraptionDirection();
            boolean flag = (d.getAxisDirection() == Direction.AxisDirection.POSITIVE)
                    == (d.getAxis() == Direction.Axis.X);
            return flag ? 1.0f : -1.0f;
        }
    }

    /** All currently assembled cannons. Empty when no cannon is assembled. */
    private final List<MountState> mounts = new ArrayList<>();
    /** Сохранённые/присланные углы по грани контроллера — для MountState, создаваемых позже в attach(). */
    private final Map<Direction, float[]> pendingMountAngles = new EnumMap<>(Direction.class);

    private boolean running = false;
    @Nullable private AssemblyException lastAssemblyException = null;
    /** Редстоун на предыдущем тике — фронт запускает попытку сборки/разборки. */
    private boolean prevAssemblyPowered = false;

    @Nullable private UUID     currentTargetUUID  = null;
    /**
     * true если текущая entity-цель находится на sublevel-объекте (чужом корабле).
     * В этом случае LOS через блоки не проверяется — цель видна сквозь стены корабля,
     * аналогично тому как враждебный командер обнаруживается без LOS-check.
     */
    private boolean currentTargetOnSubLevel = false;
    @Nullable private UUID     ownContraptionUUID = null;
    @Nullable private BlockPos commanderPos       = null;
    @Nullable private BlockPos commanderTargetPos = null;

    /**
     * UUID командера, который первым активировал этот контроллер.
     * Контроллер будет принимать команды деактивации только от командера
     * с таким же UUID. Сбрасывается при деактивации.
     */
    @Nullable private UUID ownerCommanderUUID = null;

    private final TargetFilterData filterData = new TargetFilterData();

    private int scanTickCounter        = 0;
    private int transferTickCounter    = 0;
    private int confirmTicks           = 0;
    private int losGraceTicks          = 0;
    private int losCheckCounter        = 0;

    // ── Ballistic aim cache ───────────────────────────────────────────────────
    /** Относительный порог смещения (смещение²/дистанция² до цели), при котором кэш инвалидируется. */
    private static final double AIM_CACHE_POS_THRESHOLD_SQ  = 0.0004; // ~2% дистанции
    /** Порог изменения относительной скорости цели (блоков/тик)², при котором кэш инвалидируется. */
    private static final double AIM_CACHE_VEL_THRESHOLD_SQ  = 0.001;
    /** Принудительный пересчёт раз в N тиков, даже если входные данные не изменились. */
    private static final int    AIM_CACHE_MAX_AGE            = 5;

    // ── Сглаженная скорость цели (для упреждения) ───────────────────────────
    private static final int    POS_HISTORY_TICKS   = 6;
    private static final double TARGET_VEL_EMA_ALPHA = 0.35;
    @Nullable private UUID   velTrackUUID     = null;
    private final       Vec3[] posHistory       = new Vec3[POS_HISTORY_TICKS];
    private              int   posHistoryCount  = 0;
    private              int   posHistoryHead   = 0;
    private        Vec3    smoothedTargetVel = Vec3.ZERO;

    /**
     * Обновляет сглаженную скорость цели по кольцевому буферу её мировых
     * позиций за последние POS_HISTORY_TICKS тиков, затем лёгкой EMA поверх
     * полученного вектора. При смене цели буфер сбрасывается, чтобы не
     * тащить упреждение от предыдущей, уже не актуальной цели.
     *
     * @param target        текущая цель (для UUID и fallback getDeltaMovement())
     * @param worldPos      актуальная МИРОВАЯ позиция цели в этом тике
     *                      (для sublevel-целей — уже сконвертированная)
     * @return сглаженный вектор скорости цели, блоков/тик, в мировых координатах
     */
    private Vec3 updateSmoothedTargetVelocity(Entity target, Vec3 worldPos) {
        UUID uuid = target.getUUID();
        if (!uuid.equals(velTrackUUID)) {
            // Новая цель — нет истории для дельты позиции, сбрасываем буфер
            // и стартуем с «сырой» скорости движка как разумного initial guess.
            velTrackUUID    = uuid;
            posHistoryCount = 0;
            posHistoryHead  = 0;
            posHistory[0]   = worldPos;
            posHistoryCount = 1;
            posHistoryHead  = 1 % POS_HISTORY_TICKS;
            smoothedTargetVel = target.getDeltaMovement();
            return smoothedTargetVel;
        }

        posHistory[posHistoryHead] = worldPos;
        posHistoryHead = (posHistoryHead + 1) % POS_HISTORY_TICKS;
        if (posHistoryCount < POS_HISTORY_TICKS) posHistoryCount++;

        Vec3 bufferVel;
        if (posHistoryCount < 2) {
            bufferVel = target.getDeltaMovement();
        } else {
            int oldestIdx = (posHistoryCount < POS_HISTORY_TICKS)
                    ? 0
                    : posHistoryHead;
            Vec3 oldest = posHistory[oldestIdx];
            int span = posHistoryCount - 1;
            bufferVel = worldPos.subtract(oldest).scale(1.0 / span);
        }

        smoothedTargetVel = new Vec3(
                smoothedTargetVel.x + (bufferVel.x - smoothedTargetVel.x) * TARGET_VEL_EMA_ALPHA,
                smoothedTargetVel.y + (bufferVel.y - smoothedTargetVel.y) * TARGET_VEL_EMA_ALPHA,
                smoothedTargetVel.z + (bufferVel.z - smoothedTargetVel.z) * TARGET_VEL_EMA_ALPHA);
        return smoothedTargetVel;
    }

    // Кэш для aimAndFireAtCommander
    private static final int    CMD_AIM_CACHE_MAX_AGE          = 10;
    private static final double CMD_AIM_CACHE_POS_THRESHOLD_SQ = 0.0009;

    @Nullable private ServerSubLevel controllerSubLevel = null;
    private int subLevelCacheTimer = 0;

    private int  scanRadiusCache    = -1;

    private static final int SUBLEVEL_COMMANDER_CACHE_INTERVAL = 100;
    private List<SableCompat.SubLevelCommanderEntry> subLevelCommanderCache = new ArrayList<>();
    private int subLevelCommanderCacheTimer = 0;
    private static final Logger LOGGER =
            LoggerFactory.getLogger("cbc_autotarget/Controller");

    private final ItemStackHandler inventory = new ItemStackHandler(INVENTORY_SIZE) {
        @Override protected void onContentsChanged(int slot) { setChanged(); }
    };

    public ControllerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /**
     * Возвращает радиус сканирования в зависимости от тира блока-контроллера.
     * Тир 1 = 25, Тир 2 = 50, Тир 3 = 100, Тир 4 = 200 блоков.
     */
    private int getScanRadius() {
        if (scanRadiusCache >= 0) return scanRadiusCache;
        if (getBlockState().getBlock() instanceof ControllerBlock cb) {
            scanRadiusCache = cb.getScanRadius();
        } else {
            scanRadiusCache = ControllerBlock.TIER_RADII[0]; // fallback: 25
        }
        return scanRadiusCache;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ControllerBlockEntity be) {
        be.tick(level, pos, state);
    }

    // Клиент никогда не считает цели/наводку сам — он лишь переносит
    // cannonYaw/cannonPitch в контрапшен для визуального поворота.
    public static void clientTick(Level level, BlockPos pos, BlockState state, ControllerBlockEntity be) {
        for (MountState ms : be.mounts) {
            if (ms.contraption == null && ms.assembledFromPos != null) {
                AABB searchBox = new AABB(ms.assembledFromPos).inflate(1.5);
                for (PitchOrientedContraptionEntity poce : level.getEntitiesOfClass(PitchOrientedContraptionEntity.class, searchBox)) {
                    if (poce.getContraption() != null && ms.assembledFromPos.equals(poce.getContraption().anchor)) {
                        ms.contraption = poce;
                        break;
                    }
                }
            }
            be.applyRotation(ms);
        }
    }

    private void tick(Level level, BlockPos pos, BlockState state) {
        if (SableCompat.isAvailable() && level instanceof ServerLevel sl) {
            if (++subLevelCacheTimer >= SUBLEVEL_CACHE_INTERVAL) {
                subLevelCacheTimer = 0;
                controllerSubLevel = SableCompat.getSubLevelForBlock(sl, pos);
            }
        }

        // ── Редстоун-сборка/разборка пушки ──────────────────────────────────
        boolean assemblyPowered = level.hasNeighborSignal(pos);
        if (assemblyPowered != prevAssemblyPowered) {
            prevAssemblyPowered = assemblyPowered;
            if (assemblyPowered) {
                assembleCannons(level, pos);
                setActive(true);
            } else {
                disassembleAllCannons();
                setActive(false);
            }
        }

        // Очистка мёртвых контрапшенов
        mounts.removeIf(ms -> {
            if (ms.contraption != null && !ms.contraption.isAlive()) {
                ms.contraption = null;
                return true;
            }
            return ms.contraption == null;
        });

        // Per-mount pre-tick: apply rotation, transfer ammo
        for (MountState ms : mounts) {
            ms.prevCannonYaw = ms.cannonYaw;
            ms.prevCannonPitch = ms.cannonPitch;
            applyRotation(ms);
        }

        if (++transferTickCounter >= TRANSFER_INTERVAL) {
            transferTickCounter = 0;
            for (MountState ms : mounts) {
                if (ms.contraption != null) tryTransferToCannon(level, ms);
            }
        }

        if (!active) return;
        if (mounts.isEmpty()) return;

        // Координаты контроллера считаем один раз за тик
        Vec3 tickWorldCenter = getControllerWorldPos();
        Vec3 tickMuzzlePos = tickWorldCenter; // default; overridden per-mount for aim

        // Per-mount yaw/pitch/fire ticking
        for (MountState ms : mounts) {
            if (ms.yawDirty)   tickYaw(ms);
            if (ms.pitchDirty) tickPitch(ms);
            tickFire(level, ms);

            // Sync rotation to client
            if (level instanceof ServerLevel
                    && (ms.cannonYaw != ms.prevCannonYaw || ms.cannonPitch != ms.prevCannonPitch)) {
                level.sendBlockUpdated(pos, state, state, 3);
            }
        }

        // Получаем реальный ServerLevel
        ServerLevel sl = resolveServerLevel(level);
        if (sl == null) return;

        // ── Target tracking ──────────────────────────────────────────────────
        if (currentTargetUUID != null) {
            Entity e = sl.getEntity(currentTargetUUID);
            if (e == null && controllerSubLevel != null)
                e = controllerSubLevel.getLevel().getEntity(currentTargetUUID);
            if (e == null && currentTargetOnSubLevel && SableCompat.isAvailable()) {
                int sr = getScanRadius();
                for (var entry : SableCompat.findLivingEntitiesInAllSubLevels(
                        sl, tickWorldCenter, sr * 2, LivingEntity.class,
                        en -> en.getUUID().equals(currentTargetUUID))) {
                    e = entry.entity();
                    break;
                }
            }

            int   r      = getScanRadius();
            ServerLevel main = mainLevel(level);

            boolean hardLost = e == null || !e.isAlive() || !filterData.isAllowed(e)
                    || (main != null && filterData.isNearAlly(e, main));
            boolean outOfRange = !hardLost && !currentTargetOnSubLevel &&
                    e.distanceToSqr(tickWorldCenter) > (double) r * r;

            if (hardLost) {
                dropEntityTarget(sl);
            } else if (outOfRange) {
                if (++losGraceTicks > LOS_GRACE_TICKS_MAX) dropEntityTarget(sl);
            } else {
                if (currentTargetOnSubLevel) {
                    losGraceTicks = 0;
                } else if (++losCheckCounter >= LOS_CHECK_INTERVAL) {
                    losCheckCounter = 0;
                    boolean hasLos = checkAnyMountHasLos(e, tickWorldCenter, main != null ? main : sl);
                    if (!hasLos) {
                        if (++losGraceTicks > LOS_GRACE_TICKS_MAX) dropEntityTarget(sl);
                    } else {
                        losGraceTicks = 0;
                    }
                }
            }
        }

        if (++scanTickCounter >= CBCAutoTargetConfig.SCAN_INTERVAL_TICKS.get()) {
            scanTickCounter = 0;
            Level scanLevel = (controllerSubLevel != null) ? controllerSubLevel.getLevel() : sl;
            UUID prev = currentTargetUUID;
            scanForTarget(level, sl, scanLevel, tickWorldCenter);
            if (currentTargetUUID != null && !currentTargetUUID.equals(prev)) {
                for (MountState ms : mounts) ms.alignedTicks = 0;
            }
        }

        if (currentTargetUUID != null && commanderTargetPos == null) {
            Entity e = sl.getEntity(currentTargetUUID);
            if (e == null && controllerSubLevel != null)
                e = controllerSubLevel.getLevel().getEntity(currentTargetUUID);
            if (e == null && currentTargetOnSubLevel && SableCompat.isAvailable()) {
                int sr = getScanRadius();
                for (var entry : SableCompat.findLivingEntitiesInAllSubLevels(
                        sl, tickWorldCenter, sr * 2, LivingEntity.class,
                        en -> en.getUUID().equals(currentTargetUUID))) {
                    e = entry.entity();
                    break;
                }
            }
            if (e != null && e.isAlive()) {
                // Compute snapshot ONCE per tick (velocity smoothing must not run N times)
                TargetSnapshot snap = computeTargetSnapshot(sl, e, tickWorldCenter);
                for (MountState ms : mounts) {
                    aimAndFireAtEntity(sl, e, ms, snap);
                }
            }
        }

        if (commanderTargetPos != null && currentTargetUUID == null) {
            for (MountState ms : mounts) {
                aimAndFireAtCommander(sl, ms);
            }
        }

        // Final per-mount rotation sync (after aim updates)
        for (MountState ms : mounts) {
            if (level instanceof ServerLevel
                    && (ms.cannonYaw != ms.prevCannonYaw || ms.cannonPitch != ms.prevCannonPitch)) {
                level.sendBlockUpdated(pos, state, state, 3);
            }
        }
    }

    // ── Inlined Yaw logic ─────────────────────────────────────────────────────
    private void tickYaw(MountState ms) {
        double currentYaw = Mth.wrapDegrees(ms.contraption.yaw);
        double desiredYaw = Mth.wrapDegrees(ms.targetYaw);
        double diff       = Mth.wrapDegrees(desiredYaw - currentYaw);
        boolean snapped   = Math.abs(diff) <= YAW_DEADBAND_DEG;

        if (snapped) {
            ms.cannonYaw = (float) desiredYaw;
            ms.yawDirty = false;
        } else {
            double step = Math.min(Math.abs(diff), YAW_MAX_DEG_PER_TICK) * Math.signum(diff);
            ms.cannonYaw = (float) Mth.wrapDegrees(currentYaw + step);
        }
    }

    // ── Inlined Pitch logic ───────────────────────────────────────────────────
    private void tickPitch(MountState ms) {
        float sgn               = ms.getContraptionSign();
        float currentWorldPitch = ms.contraption.pitch * sgn;
        float diff              = ms.targetPitch - currentWorldPitch;
        boolean snapped         = Math.abs(diff) <= PITCH_DEADBAND_DEG;

        if (snapped) {
            ms.cannonPitch = ms.targetPitch;
            ms.pitchDirty = false;
        } else {
            float step = Math.min(Math.abs(diff), PITCH_MAX_DEG_PER_TICK) * Math.signum(diff);
            ms.cannonPitch = currentWorldPitch + step;
        }
    }

    // ── Inlined Fire logic ────────────────────────────────────────────────────
    private void tickFire(Level level, MountState ms) {
        ServerLevel sl = resolveServerLevel(level);
        if (sl == null) return;
        if (!(ms.contraption.getContraption() instanceof AbstractMountedCannonContraption cannon)) return;

        if (ms.cancelRequested) {
            ms.cancelRequested = false;
            ms.fireRequested    = false;
            cannon.onRedstoneUpdate(sl, ms.contraption, false, 0, this);
            return;
        }
        if (!ms.fireRequested && !ms.broadcastFireRequested) return;
        ms.fireRequested          = false;
        ms.broadcastFireRequested = false;
        cannon.onRedstoneUpdate(sl, ms.contraption, true, 15, this);
    }

    // ── Aim setters (per-mount) ──────────────────────────────────────────────
    private void applyAim(MountState ms, float wantedYaw, float wantedPitch) {
        if (allowHorizontal) { ms.targetYaw = wantedYaw; ms.yawDirty = true; }
        if (allowVertical)   { ms.targetPitch = wantedPitch; ms.pitchDirty = true; }
    }

    private void requestFire(ServerLevel level, MountState ms) {
        ms.doRequestFire();
        ms.fireCooldown = MIN_FIRE_COOLDOWN;
        ms.alignedTicks = 0;
        broadcastFireToFrequencyPeers(level);
    }

    /**
     * Рассылает команду "открыть огонь" всем контроллерам с той же (ненулевой)
     * частотой fireFrequency в радиусе FIRE_FREQUENCY_RADIUS блоков.
     */
    private void broadcastFireToFrequencyPeers(ServerLevel level) {
        if (fireFrequency <= 0) return;

        Vec3 myWorldPos = getControllerWorldPos();

        for (ControllerBlockEntity peer : getControllersInDimension(level.dimension())) {
            if (peer == this) continue;
            if (peer.fireFrequency != this.fireFrequency) continue;
            if (!peer.isActive()) continue;

            Vec3 peerWorldPos = peer.getControllerWorldPos();
            if (myWorldPos.distanceToSqr(peerWorldPos) > FIRE_FREQUENCY_RADIUS_SQ) continue;

            peer.receiveBroadcastFire();
        }
    }

    /**
     * Вызывается на контроллере-получателе синхронного сигнала огня.
     */
    public void receiveBroadcastFire() {
        for (MountState ms : mounts) {
            ms.broadcastFireRequested = true;
        }
    }

    private void dropEntityTarget(ServerLevel level) {
        currentTargetUUID    = null;
        currentTargetOnSubLevel = false;
        confirmTicks         = 0;
        losGraceTicks        = 0;
        for (MountState ms : mounts) {
            ms.doCancelFire();
            ms.alignedTicks = 0;
            ms.invalidateEntityAimCache();
            ms.hasPrevWantedAim = false;
        }
        // Сбрасываем сглаженную скорость упреждения
        velTrackUUID      = null;
        posHistoryCount   = 0;
        posHistoryHead    = 0;
        smoothedTargetVel = Vec3.ZERO;
    }

    // ── Scanning ──────────────────────────────────────────────────────────────
    private boolean checkAnyMountHasLos(Entity entity, Vec3 worldCenter, ServerLevel mainLevel) {
        if (mounts.isEmpty()) {
            Vec3 eye = worldCenter.add(0, 0.5, 0);
            return (controllerSubLevel != null)
                    ? LineOfSightUtil.hasLineOfSightToEntityFromSubLevel(controllerSubLevel, eye, entity)
                    : LineOfSightUtil.hasLineOfSightToEntity(mainLevel, eye, entity);
        }
        for (MountState ms : mounts) {
            Vec3 muzzle = computeRealMuzzlePos(ms);
            boolean los = (controllerSubLevel != null)
                    ? LineOfSightUtil.hasLineOfSightToEntityFromSubLevel(controllerSubLevel, muzzle, entity)
                    : LineOfSightUtil.hasLineOfSightToEntity(mainLevel, muzzle, entity);
            if (los) return true;
        }
        return false;
    }

    private void scanForTarget(Level level, ServerLevel mainLevel, Level scanLevel, Vec3 worldCenter) {
        int  radius      = getScanRadius();
        UUID prevUUID    = currentTargetUUID;

        AABB worldBox = new AABB(
                worldCenter.x - radius, worldCenter.y - radius, worldCenter.z - radius,
                worldCenter.x + radius, worldCenter.y + radius, worldCenter.z + radius);

        List<LivingEntity> allInBox = mainLevel.getEntitiesOfClass(LivingEntity.class, worldBox, e -> e.isAlive());
        List<Entity> candidates = new ArrayList<>();
        for (LivingEntity e : allInBox) {
            boolean allowed = filterData.isAllowed(e);
            boolean nearAlly = allowed && filterData.isNearAlly(e, mainLevel);
            if (allowed && !nearAlly) candidates.add(e);
            else LOGGER.debug("[Scan] {} SKIP {} allowed={} nearAlly={} mask={}", worldPosition, e.getClass().getSimpleName(), allowed, nearAlly, Integer.toBinaryString(filterData.getMask()));
        }
        if (!allInBox.isEmpty()) {
            LOGGER.info("[Scan] {} radius={} mask={} inBox={} candidates={}", worldPosition, radius, Integer.toBinaryString(filterData.getMask()), allInBox.size(), candidates.size());
        }

        List<SableCompat.SubLevelEntityEntry<LivingEntity>> subLevelCandidates = new ArrayList<>();
        if (SableCompat.isAvailable()) {
            subLevelCandidates = SableCompat.findLivingEntitiesInAllSubLevels(
                    mainLevel, worldCenter, radius, LivingEntity.class,
                    e -> filterData.isAllowed(e) && !filterData.isNearAlly(e, mainLevel));
        }

        Comparator<Entity> byThreatThenDistance = Comparator
                .comparingInt((Entity e) -> filterData.isPriorityThreat(e) ? 0 : 1)
                .thenComparingDouble(e -> e.distanceToSqr(worldCenter));

        candidates.sort(byThreatThenDistance);

        int maxChecks = CBCAutoTargetConfig.MAX_RAYCAST_CANDIDATES.get();
        List<Entity> toCheck = candidates.size() > maxChecks
                ? candidates.subList(0, maxChecks) : candidates;

        Entity chosen = null;
        boolean chosenOnSubLevel = false;
        for (Entity candidate : toCheck) {
            boolean los = checkAnyMountHasLos(candidate, worldCenter, mainLevel);
            LOGGER.info("[Scan] {} LOS-check {} target={} subLevel={} los={}",
                    worldPosition, candidate.getClass().getSimpleName(), candidate.position(),
                    controllerSubLevel != null, los);
            if (los) { chosen = candidate; break; }
        }
        if (chosen == null && !toCheck.isEmpty()) {
            LOGGER.info("[Scan] {} no candidate passed LOS out of {} checked", worldPosition, toCheck.size());
        }

        if (chosen == null && !subLevelCandidates.isEmpty()) {
            subLevelCandidates.sort(Comparator
                    .comparingInt((SableCompat.SubLevelEntityEntry<LivingEntity> e) ->
                            filterData.isPriorityThreat(e.entity()) ? 0 : 1)
                    .thenComparingDouble(e -> e.worldPos().distanceToSqr(worldCenter)));
            int subChecks = Math.min(subLevelCandidates.size(), maxChecks);
            for (int i = 0; i < subChecks; i++) {
                chosen = subLevelCandidates.get(i).entity();
                chosenOnSubLevel = true;
                break;
            }
        }

        if (chosen != null) {
            if (chosen.getUUID().equals(currentTargetUUID)) {
                confirmTicks = Math.min(confirmTicks + 1, 3);
            } else {
                currentTargetUUID = chosen.getUUID();
                currentTargetOnSubLevel = chosenOnSubLevel;
                confirmTicks  = 1;
                for (MountState ms : mounts) ms.alignedTicks = 0;
                losGraceTicks = 0;
            }
            commanderTargetPos = null;
            return;
        }

        if (prevUUID != null) {
            Entity prevEntity = mainLevel.getEntity(prevUUID);
            if (prevEntity == null && controllerSubLevel != null)
                prevEntity = controllerSubLevel.getLevel().getEntity(prevUUID);
            if (prevEntity == null && currentTargetOnSubLevel && SableCompat.isAvailable()) {
                outer:
                for (var entry : SableCompat.findLivingEntitiesInAllSubLevels(
                        mainLevel, worldCenter, radius * 2, LivingEntity.class, e -> e.getUUID().equals(prevUUID))) {
                    prevEntity = entry.entity();
                    break outer;
                }
            }

            boolean hardLost = prevEntity == null || !prevEntity.isAlive()
                    || !filterData.isAllowed(prevEntity)
                    || filterData.isNearAlly(prevEntity, mainLevel);

            if (!hardLost && ++losGraceTicks <= LOS_GRACE_TICKS_MAX) {
                return;
            }
        }

        currentTargetUUID = null;
        confirmTicks      = 0;
        losGraceTicks     = 0;
        for (MountState ms : mounts) {
            ms.doCancelFire();
            ms.alignedTicks = 0;
            ms.hasPrevWantedAim = false;
        }
        velTrackUUID      = null;
        posHistoryCount   = 0;
        posHistoryHead    = 0;
        smoothedTargetVel = Vec3.ZERO;
        scanForCommanderTargets(scanLevel, mainLevel, worldCenter);
    }

    private void scanForCommanderTargets(Level scanLevel, ServerLevel mainLevel, Vec3 worldCenter) {
        if (!filterData.isEnabled(TargetCategory.ENEMY_COMMANDERS)) {
            commanderTargetPos = null;
            return;
        }

        int  radius      = getScanRadius();

        String myKey = "";
        if (commanderPos != null) {
            BlockEntity be = scanLevel.getBlockEntity(commanderPos);
            if (!(be instanceof CommanderBlockEntity) && controllerSubLevel != null)
                be = mainLevel.getBlockEntity(commanderPos);
            if (be instanceof CommanderBlockEntity myCmd) myKey = myCmd.getAllianceKey();
        }
        final String finalMyKey = myKey;

        java.util.LinkedHashMap<BlockPos, CommanderBlockEntity> seen = new java.util.LinkedHashMap<>();
        for (CommanderBlockEntity.CommanderHit hit :
                CommanderBlockEntity.findCommandersInRadius(scanLevel, worldPosition, worldCenter, radius, controllerSubLevel))
            seen.put(BlockPos.containing(hit.worldPos), hit.commander);

        if (controllerSubLevel != null) {
            BlockPos worldOriginBlock = BlockPos.containing(worldCenter);
            for (CommanderBlockEntity.CommanderHit hit :
                    CommanderBlockEntity.findCommandersInRadius(mainLevel, worldOriginBlock, worldCenter, radius, null))
                seen.putIfAbsent(BlockPos.containing(hit.worldPos), hit.commander);
        }

        if (SableCompat.isAvailable()) {
            for (SableCompat.SubLevelCommanderEntry entry : SableCompat.findCommandersInAllSubLevels(mainLevel, worldCenter, radius)) {
                if (entry.subLevel() == controllerSubLevel) continue;
                seen.putIfAbsent(BlockPos.containing(entry.worldPos()), entry.commander());
            }
        }

        seen.values().removeIf(cmd -> {
            if (cmd.getBlockPos().equals(commanderPos)) return true;
            return !filterData.isCommanderHostile(finalMyKey, cmd.getAllianceKey());
        });

        if (seen.isEmpty()) { commanderTargetPos = null; return; }

        java.util.Map.Entry<BlockPos, CommanderBlockEntity> nearest =
                Collections.min(seen.entrySet(),
                        Comparator.comparingDouble(e -> e.getKey().getCenter().distanceToSqr(worldCenter)));

        BlockPos chosen = nearest.getKey();

        if (chosen != null && !chosen.equals(commanderTargetPos)) {
            for (MountState ms : mounts) ms.invalidateCmdAimCache();
        }
        commanderTargetPos = chosen;
    }

    // ── Aim & fire ────────────────────────────────────────────────────────────
    /**
     * Общая для всех mount'ов часть: точка прицеливания и относительная
     * скорость цели. Считается ОДИН раз за тик (до цикла по mount'ам),
     * не per-mount — иначе updateSmoothedTargetVelocity() (EMA по буферу
     * позиций) вызывался бы N раз за тик при N пушках и сглаживание
     * скорости было бы испорчено (буфер сдвигался бы N раз вместо 1).
     */
    private record TargetSnapshot(Vec3 targetPos, Vec3 relVel) {}

    private TargetSnapshot computeTargetSnapshot(ServerLevel level, Entity target, Vec3 approxMuzzle) {
        Vec3 rawTarget = target.position();
        if (currentTargetOnSubLevel && SableCompat.isAvailable()) {
            ServerLevel ml = mainLevel(level);
            if (ml != null) {
                for (var _entry : SableCompat.findLivingEntitiesInAllSubLevels(
                        ml, approxMuzzle, getScanRadius() * 2, LivingEntity.class,
                        _e -> _e.getUUID().equals(currentTargetUUID))) {
                    rawTarget = _entry.worldPos();
                    break;
                }
            }
        }
        double aimY    = rawTarget.y + target.getBbHeight() * 0.2;
        Vec3 targetPos = new Vec3(rawTarget.x, aimY, rawTarget.z);

        Vec3 platVel = getPlatformVelocity();
        Vec3 targetVel;
        if (currentTargetOnSubLevel && SableCompat.isAvailable()) {
            Vec3 rawLocalVel = target.getDeltaMovement();
            targetVel = rawLocalVel;
            ServerLevel ml = mainLevel(level);
            if (ml != null) {
                for (var _entry : SableCompat.findLivingEntitiesInAllSubLevels(
                        ml, approxMuzzle, getScanRadius() * 2, LivingEntity.class,
                        _e -> _e.getUUID().equals(currentTargetUUID))) {
                    Vec3 shipVel = SableCompat.getShipVelocity(_entry.subLevel());
                    Vec3 wVel    = SableCompat.toWorldVelocity(_entry.subLevel(), rawLocalVel);
                    targetVel = wVel.add(shipVel);
                    break;
                }
            }
        } else {
            targetVel = updateSmoothedTargetVelocity(target, targetPos);
        }
        Vec3 relVel = new Vec3(
                targetVel.x - platVel.x,
                targetVel.y - platVel.y,
                targetVel.z - platVel.z);
        return new TargetSnapshot(targetPos, relVel);
    }

    private void aimAndFireAtEntity(ServerLevel level, Entity target, MountState ms,
                                    TargetSnapshot snap) {
        PitchOrientedContraptionEntity c = ms.contraption;
        if (!(c.getContraption() instanceof AbstractMountedCannonContraption)) return;
        ownContraptionUUID = c.getUUID();

        Vec3 muzzle    = computeRealMuzzlePos(ms);
        Vec3 targetPos = snap.targetPos();
        Vec3 relVel    = snap.relVel();
        Vec3 muzzleWorldPos = muzzle;

        // ── Ballistic cache ───────────────────────────────────────────────────
        double distSqToTargetE = Math.max(muzzle.distanceToSqr(targetPos), 1.0);
        boolean needRecalc = ms.entityAimCache == null
                || ++ms.entityAimCacheAge >= AIM_CACHE_MAX_AGE
                || muzzle.distanceToSqr(ms.entityAimCacheMuzzle) / distSqToTargetE > AIM_CACHE_POS_THRESHOLD_SQ
                || targetPos.distanceToSqr(ms.entityAimCacheTarget) / distSqToTargetE > AIM_CACHE_POS_THRESHOLD_SQ
                || relVel.subtract(ms.entityAimCacheRelVel).lengthSqr() > AIM_CACHE_VEL_THRESHOLD_SQ;

        float sgn          = ms.getContraptionSign();
        float currentPitch = c.pitch * sgn;

        if (needRecalc) {
            double traverseTicks = estimateTraverseTicks(c.yaw, currentPitch,
                    ms.prevWantedYaw, ms.prevWantedPitch, ms.hasPrevWantedAim);
            Vec3 traverseAdjustedTarget = traverseTicks > 0.0
                    ? targetPos.add(relVel.scale(traverseTicks))
                    : targetPos;
            float depLimit = worldMaxDepression(ms, sgn);
            float eleLimit = worldMaxElevation(ms, sgn);
            LOGGER.debug("[PitchLimits] pos={} maxDepression={} maxElevation={}", worldPosition, depLimit, eleLimit);
            ms.entityAimCache       = BallisticSolver.solve(muzzle, traverseAdjustedTarget, relVel,
                    CBCAutoTargetConfig.MUZZLE_SPEED_BLOCKS_PER_TICK.get(),
                    CBCAutoTargetConfig.DEFAULT_GRAVITY.get(),
                    CBCAutoTargetConfig.DEFAULT_DRAG.get(),
                    false, depLimit, eleLimit);
            ms.entityAimCacheMuzzle = muzzle;
            ms.entityAimCacheTarget = targetPos;
            ms.entityAimCacheRelVel = relVel;
            ms.entityAimCacheAge    = 0;
        }
        double[] aim = ms.entityAimCache;
        // ─────────────────────────────────────────────────────────────────────

        float[] local       = ShipAimSolver.toLocalAim(aim[0], aim[1], controllerSubLevel);
        YawClampResult yawClamp = clampYawToMountFacing(ms, local[0]);
        float   wantedYaw   = yawClamp.clampedYaw();
        float   wantedPitch = local[1];

        applyAim(ms, wantedYaw, wantedPitch);

        double yawTolExtra = 0, pitchTolExtra = 0;
        if (ms.hasPrevWantedAim) {
            double yawRate   = Math.abs(angleDiff(wantedYaw, ms.prevWantedYaw));
            double pitchRate = Math.abs(wantedPitch - ms.prevWantedPitch);
            yawTolExtra   = Math.min(yawRate   * AIM_RATE_TOLERANCE_GAIN, AIM_RATE_TOLERANCE_MAX);
            pitchTolExtra = Math.min(pitchRate * AIM_RATE_TOLERANCE_GAIN, AIM_RATE_TOLERANCE_MAX);
        }
        ms.prevWantedYaw   = wantedYaw;
        ms.prevWantedPitch = wantedPitch;
        ms.hasPrevWantedAim = true;

        boolean yawOk   = !allowHorizontal
                || (!yawClamp.unreachable()
                && Math.abs(angleDiff(wantedYaw, c.yaw)) < BallisticSolver.YAW_TOLERANCE + yawTolExtra);
        boolean pitchOk = !allowVertical
                || Math.abs(wantedPitch - currentPitch) < BallisticSolver.PITCH_TOLERANCE + pitchTolExtra;

        if (ms.fireCooldown > 0) ms.fireCooldown--;
        ms.alignedTicks = (yawOk && pitchOk) ? ms.alignedTicks + 1 : 0;

        LOGGER.debug("[AimGate] entity pos={} wantedYaw={} curYaw={} yawDiff={} yawTol={} yawOk={} yawUnreachable={} " +
                        "wantedPitch={} curPitch={} pitchDiff={} pitchTol={} pitchOk={} alignedTicks={} " +
                        "fireCooldown={} confirmTicks={}",
                worldPosition, wantedYaw, c.yaw, angleDiff(wantedYaw, c.yaw),
                BallisticSolver.YAW_TOLERANCE + yawTolExtra, yawOk, yawClamp.unreachable(),
                wantedPitch, currentPitch, wantedPitch - currentPitch,
                BallisticSolver.PITCH_TOLERANCE + pitchTolExtra, pitchOk,
                ms.alignedTicks, ms.fireCooldown, confirmTicks);

        if (yawOk && pitchOk && ms.alignedTicks >= REQUIRED_ALIGNED_TICKS
                && ms.fireCooldown == 0 && confirmTicks >= 1) {
            Entity check = level.getEntity(currentTargetUUID);
            if (check == null && currentTargetOnSubLevel && SableCompat.isAvailable()) {
                ServerLevel ml = mainLevel(level);
                if (ml != null) {
                    int r = getScanRadius();
                    for (var entry : SableCompat.findLivingEntitiesInAllSubLevels(
                            ml, muzzleWorldPos, r * 2, LivingEntity.class,
                            e -> e.getUUID().equals(currentTargetUUID))) {
                        check = entry.entity();
                        break;
                    }
                }
            }
            boolean fireLos;
            if (currentTargetOnSubLevel) {
                fireLos = check != null;
            } else {
                ServerLevel ml = mainLevel(level);
                fireLos = (check != null) && ((controllerSubLevel != null)
                        ? LineOfSightUtil.hasLineOfSightToEntityFromSubLevel(controllerSubLevel, muzzleWorldPos, check)
                        : LineOfSightUtil.hasLineOfSightToEntity(ml, muzzleWorldPos, check));
            }
            if (check == null || !check.isAlive()) {
                dropEntityTarget(level);
                return;
            }
            if (!fireLos) {
                ms.alignedTicks = 0;
                return;
            }
            requestFire(level, ms);
        }
    }

    private static final Logger LOGGER_AIM_CMD = LoggerFactory.getLogger("cbc_autotarget/AimAtCommander");

    private void aimAndFireAtCommander(ServerLevel level, MountState ms) {
        if (commanderTargetPos == null) return;

        Level scanLevel = (controllerSubLevel != null) ? controllerSubLevel.getLevel() : level;

        BlockEntity be = level.getBlockEntity(commanderTargetPos);
        if (!(be instanceof CommanderBlockEntity) && controllerSubLevel != null)
            be = scanLevel.getBlockEntity(commanderTargetPos);
        if (!(be instanceof CommanderBlockEntity) && SableCompat.isAvailable()) {
            Vec3 worldPos = Vec3.atCenterOf(commanderTargetPos);
            int fallbackRadius = 12;
            var candidates = SableCompat.findCommandersInAllSubLevels(level, worldPos, fallbackRadius);
            SableCompat.SubLevelCommanderEntry closest = null;
            double closestDistSq = Double.MAX_VALUE;
            for (SableCompat.SubLevelCommanderEntry entry : candidates) {
                double d = entry.worldPos().distanceToSqr(worldPos);
                if (d < closestDistSq) { closestDistSq = d; closest = entry; }
            }
            if (closest != null) {
                be = closest.commander();
                commanderTargetPos = BlockPos.containing(closest.worldPos());
            }
            LOGGER_AIM_CMD.info("[AimAtCommander] pos={} lastKnownWorldPos={} fallbackRadius={} candidatesFound={} resolved={}",
                    worldPosition, worldPos, fallbackRadius, candidates.size(), be != null ? be.getClass().getSimpleName() : "null");
        }
        if (!(be instanceof CommanderBlockEntity targetCmd)) {
            LOGGER_AIM_CMD.warn("[AimAtCommander] pos={} LOST TARGET — commanderTargetPos={} could not be resolved to a CommanderBlockEntity, clearing target",
                    worldPosition, commanderTargetPos);
            commanderTargetPos = null; return;
        }

        String myKey = "";
        if (commanderPos != null) {
            BlockEntity myBe = scanLevel.getBlockEntity(commanderPos);
            if (!(myBe instanceof CommanderBlockEntity) && controllerSubLevel != null)
                myBe = level.getBlockEntity(commanderPos);
            if (myBe instanceof CommanderBlockEntity myCmd) myKey = myCmd.getAllianceKey();
        }
        if (!filterData.isCommanderHostile(myKey, targetCmd.getAllianceKey())) {
            commanderTargetPos = null; return;
        }

        PitchOrientedContraptionEntity c = ms.contraption;
        if (!(c.getContraption() instanceof AbstractMountedCannonContraption)) return;
        ownContraptionUUID = c.getUUID();

        Vec3 muzzle    = computeRealMuzzlePos(ms);
        Vec3 targetPos = targetCmd.getWorldPos();
        Vec3 platVel   = getPlatformVelocity();
        Vec3 relVel    = Vec3.ZERO.subtract(platVel);

        // ── Ballistic cache (commander) ───────────────────────────────────────
        boolean needRecalc = ms.cmdAimCache == null
                || ++ms.cmdAimCacheAge >= CMD_AIM_CACHE_MAX_AGE
                || muzzle.distanceToSqr(ms.cmdAimCacheMuzzle) / Math.max(muzzle.distanceToSqr(targetPos), 1.0) > CMD_AIM_CACHE_POS_THRESHOLD_SQ
                || targetPos.distanceToSqr(ms.cmdAimCacheTarget) / Math.max(muzzle.distanceToSqr(targetPos), 1.0) > CMD_AIM_CACHE_POS_THRESHOLD_SQ;

        if (needRecalc) {
            float sgnC = ms.getContraptionSign();
            ms.cmdAimCache       = BallisticSolver.solve(muzzle, targetPos, relVel,
                    CBCAutoTargetConfig.MUZZLE_SPEED_BLOCKS_PER_TICK.get(),
                    CBCAutoTargetConfig.DEFAULT_GRAVITY.get(),
                    CBCAutoTargetConfig.DEFAULT_DRAG.get(),
                    false, worldMaxDepression(ms, sgnC), worldMaxElevation(ms, sgnC));
            ms.cmdAimCacheMuzzle = muzzle;
            ms.cmdAimCacheTarget = targetPos;
            ms.cmdAimCacheAge    = 0;
            LOGGER.debug("[AimCache] commander recalc at {}", worldPosition);
        }
        double[] aim = ms.cmdAimCache;
        // ─────────────────────────────────────────────────────────────────────

        float[] local       = ShipAimSolver.toLocalAim(aim[0], aim[1], controllerSubLevel);
        YawClampResult yawClamp = clampYawToMountFacing(ms, local[0]);
        float   wantedYaw   = yawClamp.clampedYaw();
        float   wantedPitch = local[1];

        applyAim(ms, wantedYaw, wantedPitch);

        float   sgn          = ms.getContraptionSign();
        float   currentPitch = c.pitch * sgn;
        boolean yawOk   = !allowHorizontal
                || (!yawClamp.unreachable()
                && Math.abs(angleDiff(wantedYaw, c.yaw)) < BallisticSolver.YAW_TOLERANCE);
        boolean pitchOk = !allowVertical
                || Math.abs(wantedPitch - currentPitch) < BallisticSolver.PITCH_TOLERANCE;

        ServerSubLevel targetSub = targetCmd.getCommanderSubLevel();
        boolean commanderLos;
        if (targetSub != null && targetSub != controllerSubLevel) {
            commanderLos = true;
        } else if (controllerSubLevel != null) {
            commanderLos = LineOfSightUtil.hasLineOfSightToBlockFromSubLevel(
                    controllerSubLevel, muzzle, targetPos, targetCmd.getBlockPos());
        } else {
            commanderLos = LineOfSightUtil.hasLineOfSightToBlock(
                    level, muzzle, targetPos, targetCmd.getBlockPos());
        }
        if (!commanderLos) {
            ms.alignedTicks = 0;
            return;
        }

        if (ms.fireCooldown > 0) ms.fireCooldown--;
        ms.alignedTicks = (yawOk && pitchOk) ? ms.alignedTicks + 1 : 0;

        if (yawOk && pitchOk && ms.alignedTicks >= REQUIRED_ALIGNED_TICKS && ms.fireCooldown == 0) {
            requestFire(level, ms);
        }
    }

    // ── Traverse-Compensated Lead ──────────────────────────────────────────────
    private static final int TRAVERSE_LEAD_MAX_TICKS = 40;

    private static double estimateTraverseTicks(float currentYaw, float currentPitch,
                                                float prevWantedYaw, float prevWantedPitch,
                                                boolean hasPrev) {
        if (!hasPrev) return 0.0;
        double yawDist   = Math.abs(angleDiff(prevWantedYaw, currentYaw));
        double pitchDist = Math.abs(prevWantedPitch - currentPitch);
        double yawTicks   = yawDist   / YAW_MAX_DEG_PER_TICK;
        double pitchTicks = pitchDist / PITCH_MAX_DEG_PER_TICK;
        return Math.min(Math.max(yawTicks, pitchTicks), TRAVERSE_LEAD_MAX_TICKS);
    }

    /** 45° clearance past the controller block for vertical mounts. */
    private static final float VERTICAL_MOUNT_CLEARANCE = 45.0f;

    private float worldMaxDepression(MountState ms, float sgn) {
        Direction mount = ms.getMountFacing();
        if (mount == Direction.UP) {
            return VERTICAL_MOUNT_CLEARANCE;
        } else if (mount == Direction.DOWN) {
            return 90.0f;
        }
        return CBCAutoTargetConfig.MAX_PITCH_DEPRESSION.get().floatValue();
    }

    private float worldMaxElevation(MountState ms, float sgn) {
        Direction mount = ms.getMountFacing();
        if (mount == Direction.DOWN) {
            return VERTICAL_MOUNT_CLEARANCE;
        } else if (mount == Direction.UP) {
            return 90.0f;
        }
        return CBCAutoTargetConfig.MAX_PITCH_ELEVATION.get().floatValue();
    }

    private record YawClampResult(float clampedYaw, boolean unreachable) {}

    private YawClampResult clampYawToMountFacing(MountState ms, float wantedYaw) {
        float maxOffset = CBCAutoTargetConfig.MAX_YAW_FROM_MOUNT_FACING.get().floatValue();
        if (maxOffset >= 180.0f) return new YawClampResult(wantedYaw, false);
        Direction mount = ms.getMountFacing();
        if (mount.getAxis().isVertical()) return new YawClampResult(wantedYaw, false);

        float facingYaw = mount.toYRot();
        float offset    = angleDiff(wantedYaw, facingYaw);
        if (offset > maxOffset) {
            float clamped = (float) Mth.wrapDegrees(facingYaw + maxOffset);
            return new YawClampResult(clamped, true);
        }
        if (offset < -maxOffset) {
            float clamped = (float) Mth.wrapDegrees(facingYaw - maxOffset);
            return new YawClampResult(clamped, true);
        }
        return new YawClampResult(wantedYaw, false);
    }

    private Vec3 getControllerWorldPos() {
        Vec3 local = Vec3.atCenterOf(worldPosition);
        return (controllerSubLevel != null) ? SableCompat.toWorldPos(controllerSubLevel, local) : local;
    }

    private static final double MIN_MUZZLE_OFFSET = 1.0;

    private Vec3 computeRealMuzzlePos(MountState ms) {
        PitchOrientedContraptionEntity c = ms.contraption;
        double len = Math.max(CBCAutoTargetConfig.BARREL_LENGTH.get(), MIN_MUZZLE_OFFSET);
        Direction dir = c.getInitialOrientation();
        Vec3 localMuzzle = Vec3.atCenterOf(BlockPos.ZERO).add(
                dir.getStepX() * len,
                dir.getStepY() * len,
                dir.getStepZ() * len
        );
        Vec3 worldMuzzle = c.toGlobalVector(localMuzzle, 0);
        if (controllerSubLevel != null && SableCompat.isAvailable()) {
            worldMuzzle = SableCompat.toWorldPos(controllerSubLevel, worldMuzzle);
        }
        return worldMuzzle;
    }

    private Vec3 getPlatformVelocity() {
        return (controllerSubLevel != null && SableCompat.isAvailable())
                ? SableCompat.getShipVelocity(controllerSubLevel) : Vec3.ZERO;
    }

    @Nullable
    private ServerLevel resolveServerLevel(Level level) {
        if (level instanceof ServerLevel sl) return sl;
        if (level.getServer() != null) {
            ServerLevel sl = level.getServer().getLevel(level.dimension());
            if (sl != null) return sl;
            return level.getServer().getLevel(Level.OVERWORLD);
        }
        return null;
    }

    private ServerLevel mainLevel(Level level) {
        if (level instanceof ServerLevel sl) return sl;
        if (level.getServer() != null) {
            ServerLevel sl = level.getServer().getLevel(Level.OVERWORLD);
            if (sl != null) return sl;
        }
        if (controllerSubLevel != null)
            return (ServerLevel) level.getServer().getLevel(level.dimension());
        return null;
    }

    // ── ControlPitchContraption / ControlPitchContraption.Block ────────────────

    @Override
    public BlockState getControllerState() {
        return getBlockState();
    }

    @Override
    public net.minecraft.resources.ResourceLocation getTypeId() {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CBCAutoTarget.MOD_ID, "controller");
    }

    @Override
    public boolean isAttachedTo(AbstractContraptionEntity entity) {
        for (MountState ms : mounts) {
            if (ms.contraption == entity) return true;
        }
        return false;
    }

    @Override
    public void attach(PitchOrientedContraptionEntity contraption) {
        if (!(contraption.getContraption() instanceof AbstractMountedCannonContraption)) return;
        for (MountState ms : mounts) {
            if (ms.contraption == contraption) return; // already attached
        }

        Direction dir = null;
        BlockPos anchor = null;
        if (contraption.getContraption() != null && contraption.getContraption().anchor != null) {
            anchor = contraption.getContraption().anchor;
            BlockPos diff = anchor.subtract(worldPosition);
            dir = Direction.fromDelta(diff.getX(), diff.getY(), diff.getZ());
        }

        // Match existing placeholder MountState (e.g. created on client from network packet)
        MountState matched = null;
        for (MountState ms : mounts) {
            if (ms.contraption == null) {
                if (dir != null && ms.mountFacing == dir) { matched = ms; break; }
                if (anchor != null && anchor.equals(ms.assembledFromPos)) { matched = ms; break; }
            }
        }
        if (matched != null) {
            matched.contraption = contraption;
            if (matched.mountFacing == null) matched.mountFacing = dir;
            if (matched.assembledFromPos == null) matched.assembledFromPos = anchor;
        } else {
            MountState created = new MountState(contraption, dir, anchor);
            created.initAngles(dir != null ? pendingMountAngles.get(dir) : null);
            mounts.add(created);
        }

        if (level != null && !level.isClientSide) {
            this.running = true;
            setChanged();
            // Сообщаем клиентам о новом mount и его реальных углах.
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public void onStall() {
        if (level != null && !level.isClientSide) setChanged();
    }

    @Override
    public void disassemble() {
        disassembleAllCannons();
    }

    @Override
    public BlockPos getControllerBlockPos() {
        return worldPosition;
    }

    @Override
    public void markForReassembly() {
        for (MountState ms : mounts) {
            if (ms.contraption != null) {
                ms.contraption.disassemble();
            }
        }
        mounts.clear();
        setChanged();
    }

    @Override
    public Vec3 getDismountPositionForContraption(PitchOrientedContraptionEntity poce) {
        Direction back = poce.getInitialOrientation().getOpposite();
        return Vec3.atBottomCenterOf(worldPosition.relative(back));
    }

    /**
     * Самостоятельная сборка ВСЕХ пушек — ищем казённик на каждой из 6 граней
     * Controller'а и собираем контрапшен для каждой найденной.
     */
    private void assembleCannons(Level level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            // Skip direction if we already have a mount on this face
            boolean alreadyMounted = false;
            for (MountState ms : mounts) {
                if (ms.mountFacing == dir) { alreadyMounted = true; break; }
            }
            if (alreadyMounted) continue;

            BlockPos assemblyPos = pos.relative(dir);
            if (level.isOutsideBuildHeight(assemblyPos)) continue;
            if (!(level.getBlockState(assemblyPos).getBlock() instanceof CannonContraptionProviderBlock provBlock)) continue;

            AbstractMountedCannonContraption mountedCannon = provBlock.getCannonContraption();
            if (mountedCannon == null) continue;
            try {
                if (!mountedCannon.assemble(level, assemblyPos)) continue;
            } catch (AssemblyException e) {
                lastAssemblyException = e;
                LOGGER.debug("[assemble] dir={} failed at {}: {}", dir, assemblyPos, e.getMessage());
                continue;
            }

            Direction facing1 = mountedCannon.initialOrientation();
            mountedCannon.removeBlocksFromWorld(level, BlockPos.ZERO);
            PitchOrientedContraptionEntity contraptionEntity =
                    PitchOrientedContraptionEntity.create(level, mountedCannon, facing1, this);

            MountState ms = new MountState(contraptionEntity, dir, assemblyPos);
            ms.cannonYaw = facing1.toYRot();
            ms.prevCannonYaw = ms.cannonYaw;
            mounts.add(ms);

            resetContraptionToOffset(ms);
            level.addFreshEntity(contraptionEntity);

            this.running = true;
            this.lastAssemblyException = null;
            setChanged();

            AllSoundEvents.CONTRAPTION_ASSEMBLE.playOnServer(level, pos);
            level.sendBlockUpdated(pos, getBlockState(), getBlockState(), 3);
            // Do NOT return — continue checking remaining faces
        }
    }

    public void disassembleAllCannons() {
        if (!running && mounts.isEmpty()) return;

        for (MountState ms : mounts) {
            if (ms.contraption != null) {
                // Backup controller block state before disassembly (same protection as before)
                BlockState controllerStateBackup = level != null ? level.getBlockState(worldPosition) : null;
                CompoundTag controllerNbtBackup = null;
                HolderLookup.Provider controllerNbtBackupReg = null;
                if (level != null) {
                    CompoundTag self = new CompoundTag();
                    saveAdditional(self, level.registryAccess());
                    controllerNbtBackup = self;
                    controllerNbtBackupReg = level.registryAccess();
                }

                resetContraptionToOffset(ms);
                ms.contraption.save(new CompoundTag());
                ms.contraption.disassemble();

                if (level instanceof ServerLevel serverLevel && controllerStateBackup != null
                        && !level.getBlockState(worldPosition).is(controllerStateBackup.getBlock())) {
                    LOGGER.warn("[disassembleCannon] Controller block at {} was overwritten by contraption disassembly " +
                                    "(now {}), restoring it to prevent data/block loss.",
                            worldPosition, level.getBlockState(worldPosition));
                    serverLevel.setBlock(worldPosition, controllerStateBackup, 3);
                    BlockEntity restoredBe = serverLevel.getBlockEntity(worldPosition);
                    if (restoredBe instanceof ControllerBlockEntity restoredController
                            && controllerNbtBackup != null) {
                        restoredController.loadAdditional(controllerNbtBackup, controllerNbtBackupReg);
                        restoredController.setChanged();
                    }
                }

                AllSoundEvents.CONTRAPTION_DISASSEMBLE.playOnServer(level, worldPosition);
            }
        }
        mounts.clear();
        running = false;
        setChanged();
    }

    /** Аналог CannonMountBlockEntity.resetContraptionToOffset(), per-mount. */
    private void resetContraptionToOffset(MountState ms) {
        if (ms.contraption == null) return;
        ms.cannonPitch     = 0;
        ms.cannonYaw       = ms.getContraptionDirection().toYRot();
        ms.prevCannonPitch = ms.cannonPitch;
        ms.prevCannonYaw   = ms.cannonYaw;

        ms.contraption.pitch     = ms.cannonPitch;
        ms.contraption.yaw       = ms.cannonYaw;
        ms.contraption.prevPitch = ms.contraption.pitch;
        ms.contraption.prevYaw   = ms.contraption.yaw;

        float vanillaYaw = ms.getContraptionDirection().toYRot();
        ms.contraption.setXRot(ms.cannonPitch);
        ms.contraption.setYRot(vanillaYaw);
        ms.contraption.xRotO = ms.contraption.getXRot();
        ms.contraption.yRotO = ms.contraption.getYRot();

        ms.contraption.setPos(Vec3.atBottomCenterOf(resolveAssemblyAnchor(ms)));
    }

    private BlockPos resolveAssemblyAnchor(MountState ms) {
        if (ms.contraption != null && ms.contraption.getContraption() != null) {
            BlockPos anchor = ms.contraption.getContraption().anchor;
            if (anchor != null) {
                if (ms.assembledFromPos == null) ms.assembledFromPos = anchor;
                return anchor;
            }
        }
        if (ms.assembledFromPos != null) return ms.assembledFromPos;
        if (ms.mountFacing != null) return worldPosition.relative(ms.mountFacing);
        return worldPosition;
    }

    private void applyRotation(MountState ms) {
        if (ms.contraption == null) return;
        float sgn = ms.getContraptionSign();

        if (!ms.contraption.canBeTurnedByController(this)) {
            float d = -ms.contraption.maximumDepression();
            float e = ms.contraption.maximumElevation();
            ms.cannonPitch = net.minecraft.util.Mth.clamp(ms.contraption.pitch, d, e) * sgn;
            ms.cannonYaw   = ms.contraption.yaw;
        } else {
            ms.contraption.prevPitch = ms.contraption.pitch;
            ms.contraption.prevYaw   = ms.contraption.yaw;
            ms.contraption.pitch = ms.cannonPitch * sgn;
            ms.contraption.yaw   = ms.cannonYaw;
        }
    }

    private void tryTransferToCannon(Level level, MountState ms) {
        if (ms.contraption == null) return;
        if (ms.contraption.getContraption() instanceof MountedBigCannonContraption bigCannon) {
            if (BigCannonBreechFeeder.feed(bigCannon, ms.contraption, inventory)) setChanged();
            return;
        }

        IItemHandler h = ms.contraption.getCapability(Capabilities.ItemHandler.ENTITY);
        if (h == null) return;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            for (int cs = 0; cs < h.getSlots(); cs++) {
                ItemStack rem = h.insertItem(cs, stack.copy(), false);
                if (rem.getCount() < stack.getCount()) {
                    inventory.setStackInSlot(slot, rem);
                    setChanged();
                    break;
                }
            }
        }
    }

    // ── Activation ────────────────────────────────────────────────────────────
    public boolean isActive() { return active; }
    @Nullable public UUID getOwnerCommanderUUID() { return ownerCommanderUUID; }

    public void setActive(boolean newActive) {
        if (active == newActive) return;
        LOGGER.debug("[setActive] {} -> {} at {} (level={})", active, newActive, worldPosition,
                level == null ? "null" : level.getClass().getSimpleName());
        active = newActive;
        if (level == null || level.isClientSide) return;
        if (level instanceof ServerLevel) {
            level.setBlock(worldPosition, getBlockState().setValue(ControllerBlock.ACTIVE, active), 3);
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }

        if (!active) {
            for (MountState ms : mounts) {
                ms.doCancelFire();
                ms.broadcastFireRequested = false;
                ms.invalidateAllCaches();
            }
            ownerCommanderUUID = null;
        }
        currentTargetUUID       = null;
        currentTargetOnSubLevel = false;
        commanderTargetPos = null;
        confirmTicks  = 0;
        losGraceTicks = 0;
        for (MountState ms : mounts) {
            ms.alignedTicks = 0;
            ms.yawDirty     = false;
            ms.pitchDirty   = false;
        }

        if (newActive) {
            int iv   = CBCAutoTargetConfig.SCAN_INTERVAL_TICKS.get();
            int hash = (worldPosition.getX() * 73856093) ^ (worldPosition.getY() * 19349663)
                    ^ (worldPosition.getZ() * 83492791);
            scanTickCounter = Math.abs(hash % iv);

            int transferHash = (worldPosition.getX() * 19349663) ^ (worldPosition.getY() * 83492791)
                    ^ (worldPosition.getZ() * 73856093);
            transferTickCounter = Math.abs(transferHash % TRANSFER_INTERVAL);

            if (SableCompat.isAvailable() && level instanceof ServerLevel sl) {
                controllerSubLevel = SableCompat.getSubLevelForBlock(sl, worldPosition);
                subLevelCacheTimer = 0;
            }
        } else {
            scanTickCounter     = 0;
            transferTickCounter = 0;
            controllerSubLevel  = null;
            subLevelCacheTimer  = SUBLEVEL_CACHE_INTERVAL;
        }
        setChanged();
    }

    public void onPlaced()  { }
    public void onRemoved() { disassembleAllCannons(); }

    // ── Клиентский реестр для рендерера оверлея ───────────────────────────────
    private static final java.util.concurrent.ConcurrentHashMap<BlockPos, Boolean> CLIENT_REGISTRY =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static java.util.Set<BlockPos> getClientRegistry() {
        return CLIENT_REGISTRY.keySet();
    }

    // ── Серверный реестр ───────────────────────────────────────────────────────
    private static final java.util.concurrent.ConcurrentHashMap<
            net.minecraft.resources.ResourceKey<Level>,
            java.util.concurrent.ConcurrentHashMap<BlockPos, ControllerBlockEntity>
            > SERVER_REGISTRY = new java.util.concurrent.ConcurrentHashMap<>();

    public static java.util.Collection<ControllerBlockEntity> getControllersInDimension(
            net.minecraft.resources.ResourceKey<Level> dim) {
        java.util.concurrent.ConcurrentHashMap<BlockPos, ControllerBlockEntity> map =
                SERVER_REGISTRY.get(dim);
        return map != null ? map.values() : Collections.emptyList();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide) {
            CLIENT_REGISTRY.put(worldPosition, Boolean.TRUE);
        } else if (level != null) {
            SERVER_REGISTRY
                    .computeIfAbsent(level.dimension(),
                            k -> new java.util.concurrent.ConcurrentHashMap<>())
                    .put(worldPosition, this);

            if (level instanceof ServerLevel sl) {
                controllerSubLevel = SableCompat.getSubLevelForBlock(sl, worldPosition);
                subLevelCacheTimer = 0;
                LOGGER.debug("[onLoad] Pre-cached SubLevel={} at {}",
                        controllerSubLevel == null ? "null" : "present", worldPosition);

                if (active) {
                    int iv   = CBCAutoTargetConfig.SCAN_INTERVAL_TICKS.get();
                    int hash = (worldPosition.getX() * 73856093) ^ (worldPosition.getY() * 19349663)
                            ^ (worldPosition.getZ() * 83492791);
                    scanTickCounter = Math.abs(hash % iv);

                    int transferHash = (worldPosition.getX() * 19349663) ^ (worldPosition.getY() * 83492791)
                            ^ (worldPosition.getZ() * 73856093);
                    transferTickCounter = Math.abs(transferHash % TRANSFER_INTERVAL);

                    LOGGER.debug("[onLoad] Restored active state at {}", worldPosition);
                }
            }
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && level.isClientSide) {
            CLIENT_REGISTRY.remove(worldPosition);
        } else if (level != null) {
            java.util.concurrent.ConcurrentHashMap<BlockPos, ControllerBlockEntity> map =
                    SERVER_REGISTRY.get(level.dimension());
            if (map != null) map.remove(worldPosition, this);
        }
    }

    // ── MenuProvider ──────────────────────────────────────────────────────────
    @Override public Component getDisplayName() {
        return this.getBlockState().getBlock().getName();
    }

    @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        if (player instanceof ServerPlayer sp) {
            PacketDistributor.sendToPlayer(sp, new SyncWhitelistPacket(
                    worldPosition, filterData.isWhitelistEnabled(),
                    new ArrayList<>(filterData.getWhitelist())));
        }
        return new ControllerMenu(id, inv, this);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────
    public ItemStackHandler getInventory()       { return inventory; }
    public int              getFilterMask()       { return filterData.getMask(); }
    public void             setFilterMask(int m)  { filterData.setMask(m); setChanged(); }
    public TargetFilterData getFilterData()       { return filterData; }

    public boolean isAllowHorizontal() { return allowHorizontal; }
    public boolean isAllowVertical()   { return allowVertical; }

    public void setAllowHorizontal(boolean v) {
        this.allowHorizontal = v;
        if (!v) {
            for (MountState ms : mounts) ms.yawDirty = false;
        }
        setChanged();
    }

    public void setAllowVertical(boolean v) {
        this.allowVertical = v;
        if (!v) {
            for (MountState ms : mounts) ms.pitchDirty = false;
        }
        setChanged();
    }

    public int  getFireFrequency() { return fireFrequency; }

    /** Устанавливает частоту синхронного огня. 0 = выключено. Диапазон 0-9999. */
    public void setFireFrequency(int freq) {
        this.fireFrequency = Math.max(0, Math.min(9999, freq));
        setChanged();
    }

    public void applyFromCommander(TargetFilterData cf, boolean activate, BlockPos srcCommanderPos, @Nullable UUID srcCommanderUUID) {
        if (activate) {
            if (ownerCommanderUUID != null && !ownerCommanderUUID.equals(srcCommanderUUID)) {
                LOGGER.info("[applyFromCommander] IGNORED activate from {} (owner={}), already owned at {}",
                        srcCommanderUUID, ownerCommanderUUID, worldPosition);
                return;
            }
            ownerCommanderUUID = srcCommanderUUID;
            filterData.setMask(cf.getMask());
            filterData.setWhitelistEnabled(cf.isWhitelistEnabled());
            filterData.replaceWhitelist(new ArrayList<>(cf.getWhitelist()));
            this.commanderPos = srcCommanderPos;
            LOGGER.info("[applyFromCommander] APPLY activate={} active={} mountCount={} owner={} newMask={} at {}",
                    activate, active, mounts.size(), ownerCommanderUUID, Integer.toBinaryString(filterData.getMask()), worldPosition);
            if (SableCompat.isAvailable() && level instanceof ServerLevel sl) {
                controllerSubLevel = SableCompat.getSubLevelForBlock(sl, worldPosition);
                subLevelCacheTimer = 0;
                LOGGER.debug("[applyFromCommander] refreshed controllerSubLevel={} at {}",
                        controllerSubLevel == null ? "null" : "present", worldPosition);
            }
            if (!active) setActive(true);
        } else {
            if (ownerCommanderUUID != null && !ownerCommanderUUID.equals(srcCommanderUUID)) {
                LOGGER.info("[applyFromCommander] IGNORED deactivate from {} (owner={}), not our commander at {}",
                        srcCommanderUUID, ownerCommanderUUID, worldPosition);
                return;
            }
            filterData.setMask(cf.getMask());
            filterData.setWhitelistEnabled(cf.isWhitelistEnabled());
            filterData.replaceWhitelist(new ArrayList<>(cf.getWhitelist()));
            this.commanderPos = srcCommanderPos;
            ownerCommanderUUID = null;
            LOGGER.info("[applyFromCommander] deactivate accepted from {} at {}",
                    srcCommanderUUID, worldPosition);
            setActive(false);
        }
        setChanged();
    }

    public void applyFromCommander(TargetFilterData cf, boolean activate, BlockPos srcCommanderPos) {
        applyFromCommander(cf, activate, srcCommanderPos, null);
    }

    public void applyFromCommander(TargetFilterData cf, boolean activate) {
        applyFromCommander(cf, activate, this.commanderPos, null);
    }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider reg) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Active", active);
        tag.putBoolean("AllowHorizontal", allowHorizontal);
        tag.putBoolean("AllowVertical",   allowVertical);
        tag.putInt("FireFrequency", fireFrequency);
        // Serialize per-mount yaw/pitch for client rendering
        net.minecraft.nbt.ListTag mountsList = new net.minecraft.nbt.ListTag();
        for (MountState ms : mounts) {
            CompoundTag mt = new CompoundTag();
            mt.putFloat("CannonYaw",   ms.cannonYaw);
            mt.putFloat("CannonPitch", ms.cannonPitch);
            if (ms.mountFacing != null) mt.putInt("MountFacing", ms.mountFacing.get3DDataValue());
            if (ms.assembledFromPos != null) mt.putLong("AssembledFromPos", ms.assembledFromPos.asLong());
            if (ms.contraption != null) mt.putInt("ContraptionId", ms.contraption.getId());
            mountsList.add(mt);
        }
        tag.put("Mounts", mountsList);
        return tag;
    }

    /**
     * Вызывается из SafeNbtWriterRegistry при deploy схематики Create.
     */
    public void writeSafeNbt(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("Inventory", inventory.serializeNBT(registries));
        filterData.saveToNBT(tag);
        schematicBackup = null;
        schematicBackupRegistries = null;
    }

    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider reg) {
        super.saveAdditional(tag, reg);
        tag.put("Inventory", inventory.serializeNBT(reg));
        tag.putBoolean("Active", active);
        if (currentTargetUUID   != null) tag.putUUID("CurrentTargetUUID",  currentTargetUUID);
        if (commanderPos        != null) tag.putLong("CommanderPos",        commanderPos.asLong());
        if (commanderTargetPos  != null) tag.putLong("CommanderTargetPos",  commanderTargetPos.asLong());
        if (ownerCommanderUUID  != null) tag.putUUID("OwnerCommanderUUID",  ownerCommanderUUID);
        tag.putBoolean("AllowHorizontal", allowHorizontal);
        tag.putBoolean("AllowVertical",   allowVertical);
        tag.putInt("FireFrequency", fireFrequency);
        // Save per-mount facing directions (contraptions are transient — not saved)
        net.minecraft.nbt.ListTag mountsList = new net.minecraft.nbt.ListTag();
        for (MountState ms : mounts) {
            CompoundTag mt = new CompoundTag();
            if (ms.mountFacing != null) mt.putInt("MountFacing", ms.mountFacing.get3DDataValue());
            if (ms.assembledFromPos != null) mt.putLong("AssembledFromPos", ms.assembledFromPos.asLong());
            mt.putFloat("CannonYaw",   ms.cannonYaw);
            mt.putFloat("CannonPitch", ms.cannonPitch);
            mountsList.add(mt);
        }
        tag.put("Mounts", mountsList);
        filterData.saveToNBT(tag);
    }

    @Nullable private CompoundTag schematicBackup = null;
    private HolderLookup.Provider schematicBackupRegistries = null;

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider reg) {
        super.loadAdditional(tag, reg);

        // Load per-mount yaw/pitch from client update packets
        if (tag.contains("Mounts")) {
            net.minecraft.nbt.ListTag mountsList = tag.getList("Mounts", net.minecraft.nbt.Tag.TAG_COMPOUND);
            for (int i = 0; i < mountsList.size(); i++) {
                CompoundTag mt = mountsList.getCompound(i);
                Direction facing = mt.contains("MountFacing") ? Direction.from3DDataValue(mt.getInt("MountFacing")) : null;
                BlockPos assembledPos = mt.contains("AssembledFromPos") ? BlockPos.of(mt.getLong("AssembledFromPos")) : null;
                int contraptionId = mt.contains("ContraptionId") ? mt.getInt("ContraptionId") : -1;

                // Строгое сопоставление: сначала по id контрапшена, затем по грани
                // (одна пушка на грань), затем по позиции казённика. Никакого
                // «по индексу в списке» — при разном порядке на клиенте и сервере
                // это записывало углы одной пушки в другую.
                MountState target = null;
                if (contraptionId != -1) {
                    for (MountState ms : mounts) {
                        if (ms.contraption != null && ms.contraption.getId() == contraptionId) { target = ms; break; }
                    }
                }
                if (target == null && facing != null) {
                    for (MountState ms : mounts) {
                        if (ms.mountFacing == facing) { target = ms; break; }
                    }
                }
                if (target == null && assembledPos != null) {
                    for (MountState ms : mounts) {
                        if (assembledPos.equals(ms.assembledFromPos)) { target = ms; break; }
                    }
                }
                if (target == null && facing != null && mt.contains("CannonYaw") && mt.contains("CannonPitch")) {
                    // MountState ещё не создан (attach() придёт позже) — запоминаем углы.
                    pendingMountAngles.put(facing, new float[]{mt.getFloat("CannonYaw"), mt.getFloat("CannonPitch")});
                }
                if (target == null && level != null && level.isClientSide) {
                    target = new MountState(null, facing, assembledPos);
                    mounts.add(target);
                }
                if (target != null) {
                    if (mt.contains("CannonYaw"))   target.cannonYaw   = mt.getFloat("CannonYaw");
                    if (mt.contains("CannonPitch")) target.cannonPitch = mt.getFloat("CannonPitch");
                    if (facing != null)             target.mountFacing = facing;
                    if (assembledPos != null)       target.assembledFromPos = assembledPos;
                }
            }
        }

        // Legacy single-mount support
        if (tag.contains("CannonYaw") && !mounts.isEmpty()) {
            mounts.get(0).cannonYaw = tag.getFloat("CannonYaw");
        }
        if (tag.contains("CannonPitch") && !mounts.isEmpty()) {
            mounts.get(0).cannonPitch = tag.getFloat("CannonPitch");
        }

        boolean hasRealData = tag.contains("Inventory") || tag.contains("FilterMask");

        if (!hasRealData && schematicBackup != null) {
            CompoundTag backup = schematicBackup;
            HolderLookup.Provider backupReg = schematicBackupRegistries;
            if (backup.contains("Inventory")) inventory.deserializeNBT(backupReg, backup.getCompound("Inventory"));
            filterData.loadFromNBT(backup);
            subLevelCacheTimer = SUBLEVEL_CACHE_INTERVAL;
            return;
        }

        if (tag.contains("Inventory")) inventory.deserializeNBT(reg, tag.getCompound("Inventory"));
        active             = tag.getBoolean("Active");
        currentTargetUUID  = tag.hasUUID("CurrentTargetUUID")  ? tag.getUUID("CurrentTargetUUID")             : null;
        commanderPos       = tag.contains("CommanderPos")      ? BlockPos.of(tag.getLong("CommanderPos"))      : null;
        commanderTargetPos = tag.contains("CommanderTargetPos")? BlockPos.of(tag.getLong("CommanderTargetPos")): null;
        ownerCommanderUUID = tag.hasUUID("OwnerCommanderUUID") ? tag.getUUID("OwnerCommanderUUID")            : null;
        allowHorizontal    = !tag.contains("AllowHorizontal") || tag.getBoolean("AllowHorizontal");
        allowVertical      = !tag.contains("AllowVertical")   || tag.getBoolean("AllowVertical");
        fireFrequency      = tag.contains("FireFrequency") ? tag.getInt("FireFrequency") : 0;

        // Legacy single-mount MountFacing
        if (tag.contains("MountFacing") && !tag.contains("Mounts")) {
            Direction legacyFacing = Direction.from3DDataValue(tag.getInt("MountFacing"));
            if (!mounts.isEmpty()) {
                mounts.get(0).mountFacing = legacyFacing;
            }
        }

        filterData.loadFromNBT(tag);
        subLevelCacheTimer = SUBLEVEL_CACHE_INTERVAL;

        if (hasRealData) {
            schematicBackup = tag.copy();
            schematicBackupRegistries = reg;
        }
    }

    private static float angleDiff(float target, float current) {
        float d = target - current;
        while (d >  180f) d -= 360f;
        while (d < -180f) d += 360f;
        return d;
    }
}