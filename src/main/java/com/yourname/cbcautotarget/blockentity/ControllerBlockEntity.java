package com.yourname.cbcautotarget.blockentity;

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
import java.util.List;
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

    // ── Fire state (was FireBlockEntity) ─────────────────────────────────────

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
    private int     fireCooldown   = 0;
    private int     alignedTicks   = 0;

    // ── Наведение/огонь: один Controller теперь = одна собираемая им пушка,
    // поэтому состояние снова прямые поля блока (без списка MountState —
    // мульти-пушечность вернём отдельной задачей позже).
    private float   targetYaw   = 0f;
    private boolean yawDirty    = false;
    private float   targetPitch = 0f;
    private boolean pitchDirty  = false;
    private boolean fireRequested          = false;
    private boolean cancelRequested        = false;
    private boolean broadcastFireRequested = false;
    private boolean hasPrevWantedAim = false;
    private float   prevWantedYaw    = 0f;
    private float   prevWantedPitch = 0f;
    @Nullable private double[] entityAimCache;
    @Nullable private Vec3     entityAimCacheMuzzle, entityAimCacheTarget, entityAimCacheRelVel;
    private int entityAimCacheAge = 0;
    @Nullable private double[] cmdAimCache;
    @Nullable private Vec3     cmdAimCacheMuzzle, cmdAimCacheTarget;
    private int cmdAimCacheAge = 0;

    // ── Самостоятельная сборка пушки (аналог CannonMountBlockEntity/
    // FixedCannonMountBlockEntity, но без отдельного блока-крепления).
    // Controller сам хранит собранный контрапшен и сам реализует
    // ControlPitchContraption.Block — см. конец класса.
    @Nullable private PitchOrientedContraptionEntity mountedContraption = null;
    private boolean   running   = false;
    private float     cannonYaw, cannonPitch, prevCannonYaw, prevCannonPitch;
    @Nullable private AssemblyException lastAssemblyException = null;
    /** Позиция казённика, из которого собран текущий контрапшен — нужна для resetContraptionToOffset(). */
    @Nullable private BlockPos assembledFromPos = null;
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
    // Кэш результата BallisticSolver: пересчитывается только когда позиция ствола
    // или цели сместилась более чем на порог, или истёк принудительный интервал.
    // Отдельные кэши для entity-цели и commander-цели — у них разная природа движения.
    //
    // ВАЖНО: порог инвалидации сравнивается не с абсолютным смещением в блоках²,
    // а со смещением, НОРМИРОВАННЫМ на квадрат дистанции до цели (относительное
    // угловое смещение). Абсолютный порог в блоках приводил к тому, что для
    // близких целей то же самое (или даже меньшее) угловое изменение требуемого
    // yaw/pitch пересекало порог гораздо чаще, чем для далёких — кэш
    // пересчитывался почти каждый тик, wantedYaw/wantedPitch «дёргались»
    // быстрее, чем ствол физически успевал довернуться (ограничен
    // YAW_MAX_DEG_PER_TICK/PITCH_MAX_DEG_PER_TICK), alignedTicks не успевал
    // набрать REQUIRED_ALIGNED_TICKS подряд — orudие «зависало» и стреляло
    // заметно реже именно по близким целям, хотя видимо не должно.
    // Нормировка на distanceToSqr(muzzle, target) делает порог одинаковым
    // в угловых единицах независимо от дистанции.

    /** Относительный порог смещения (смещение²/дистанция² до цели), при котором кэш инвалидируется. */
    private static final double AIM_CACHE_POS_THRESHOLD_SQ  = 0.0004; // ~2% дистанции
    /** Порог изменения относительной скорости цели (блоков/тик)², при котором кэш инвалидируется. */
    private static final double AIM_CACHE_VEL_THRESHOLD_SQ  = 0.001;
    /** Принудительный пересчёт раз в N тиков, даже если входные данные не изменились. */
    private static final int    AIM_CACHE_MAX_AGE            = 5;

    // ── Сглаженная скорость цели (для упреждения) ───────────────────────────
    // target.getDeltaMovement() — «сырая» физическая скорость за последний тик.
    // Для мобов с pathfinding-ИИ она сильно шумит: трение (friction ×0.91 каждый
    // тик), ступенчатое движение по узлам пути, шаги вверх/вниз по рельефу —
    // всё это даёт скачущее от тика к тику значение, часто близкое к нулю даже
    // когда цель устойчиво движется в одном направлении. BallisticSolver.solve()
    // считает точку упреждения как targetPos + targetVel*T — с шумной скоростью
    // T получается почти нулевым смещением, и орудие вместо упреждения просто
    // «тащится» за текущей позицией цели (visually — «догоняет, но не обгоняет»).
    // Экспоненциальное сглаживание (EMA) по фактическому смещению мировой
    // позиции цели между тиками даёт устойчивую оценку «среднего» вектора
    // движения по всем трём осям, на которую можно опираться для упреждения.
    // Двухступенчатое сглаживание вместо одиночной EMA:
    // 1) Короткий кольцевой буфер позиций (POS_HISTORY_TICKS тиков) даёт
    //    вектор среднего смещения за интервал — устойчив к шуму отдельного
    //    тика (трение/шаги пути), но реагирует на изменение направления
    //    быстрее, чем EMA с alpha=0.15 (та требовала ~15+ тиков на переход
    //    к новому направлению, из-за чего при беге игрока зигзагом/по кругу
    //    вектор упреждения почти всегда «смотрел» в устаревшую сторону).
    // 2) Лёгкая EMA поверх этого буферного вектора убирает остаточное
    //    дрожание кадр-к-кадру, не внося долгой инерции.
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
            // Самая старая позиция в буфере — это индекс posHistoryHead при
            // полном буфере, либо индекс 0 пока буфер ещё не заполнен.
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
    // Командер не движется сам по себе, но может быть на корабле Sable — порог чуть мягче.
    // См. пояснение выше: тот же относительный (не абсолютный) порог по дистанции.
    private static final int    CMD_AIM_CACHE_MAX_AGE          = 10; // обновляем реже — цель статична
    private static final double CMD_AIM_CACHE_POS_THRESHOLD_SQ = 0.0009; // ~3% дистанции

    @Nullable private ServerSubLevel controllerSubLevel = null;
    private int subLevelCacheTimer = 0;

    // Радиус сканирования зависит только от тира блока, который не меняется
    // в рантайме после установки BlockEntity, поэтому вычисляется один раз
    // и кэшируется, а не пересчитывается на каждый вызов getScanRadius().
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

    private void tick(Level level, BlockPos pos, BlockState state) {
        if (SableCompat.isAvailable() && level instanceof ServerLevel sl) {
            if (++subLevelCacheTimer >= SUBLEVEL_CACHE_INTERVAL) {
                subLevelCacheTimer = 0;
                controllerSubLevel = SableCompat.getSubLevelForBlock(sl, pos);
            }
        }

        // ── Редстоун-сборка/разборка пушки ──────────────────────────────────
        // Аналог onRedstoneUpdate() у CannonMountBlockEntity/FixedCannonMountBlockEntity,
        // но без BlockState-свойства: просто фронт сигнала на соседях блока.
        boolean assemblyPowered = level.hasNeighborSignal(pos);
        if (assemblyPowered != prevAssemblyPowered) {
            prevAssemblyPowered = assemblyPowered;
            if (assemblyPowered) {
                assembleCannon(level, pos);
            } else {
                disassembleCannon();
            }
        }
        // Контрапшен сам вызывает attach()/onStall() на своём тике (см.
        // PitchOrientedContraptionEntity.tickContraption()), но перенос
        // cannonYaw/cannonPitch в контрапшен — наша обязанность, как это
        // раньше делал CannonMountBlockEntity.tick() → applyRotation().
        if (mountedContraption != null && !mountedContraption.isAlive()) mountedContraption = null;
        prevCannonYaw   = cannonYaw;
        prevCannonPitch = cannonPitch;
        applyRotation();

        if (++transferTickCounter >= TRANSFER_INTERVAL) {
            transferTickCounter = 0;
            if (mountedContraption != null) tryTransferToCannon(level);
        }

        if (!active) return;
        if (mountedContraption == null) return;

        PitchOrientedContraptionEntity mount = mountedContraption;

        // Координаты контроллера считаем один раз за тик: при наличии
        // controllerSubLevel каждый вызов getControllerWorldPos() выполняет
        // матричное преобразование (SableCompat.toWorldPos).
        Vec3 tickWorldCenter = getControllerWorldPos();
        Vec3 tickMuzzlePos = getControllerWorldPos();
        if (mount.getContraption() instanceof AbstractMountedCannonContraption) {
            tickMuzzlePos = computeRealMuzzlePos(mount);
        }

        if (yawDirty)   tickYaw(mount);
        if (pitchDirty) tickPitch(mount);
        tickFire(level, mount);

        // Получаем реальный ServerLevel (работает и для ContraptionLevel)
        ServerLevel sl = resolveServerLevel(level);
        if (sl == null) return;

        // ── Target tracking ──────────────────────────────────────────────────
        if (currentTargetUUID != null) {
            Entity e = sl.getEntity(currentTargetUUID);
            if (e == null && controllerSubLevel != null)
                e = controllerSubLevel.getLevel().getEntity(currentTargetUUID);
            // Если цель на чужом sublevel — ищем её там
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

            // Дешёвые проверки — каждый тик (без raycast)
            boolean hardLost = e == null || !e.isAlive() || !filterData.isAllowed(e)
                    || (main != null && filterData.isNearAlly(e, main));
            // Для sublevel-цели position() — локальные координаты; конвертируем в мировые
            // через findLivingEntitiesInAllSubLevels невозможно дёшево, поэтому
            // при currentTargetOnSubLevel пропускаем outOfRange-проверку (grace обеспечит drop).
            boolean outOfRange = !hardLost && !currentTargetOnSubLevel &&
                    e.distanceToSqr(tickWorldCenter) > (double) r * r;

            if (hardLost) {
                dropEntityTarget(sl);
            } else if (outOfRange) {
                if (++losGraceTicks > LOS_GRACE_TICKS_MAX) dropEntityTarget(sl);
            } else {
                // Дорогой LOS raycast — только раз в LOS_CHECK_INTERVAL тиков.
                // Если цель находится на sublevel-корабле, LOS через блоки не проверяем:
                // стены корабля-цели не являются частью мирового уровня, поэтому
                // raycast всё равно их не «видит» — аналогично тому, как командер
                // на sublevel обнаруживается без LOS-проверки.
                if (currentTargetOnSubLevel) {
                    losGraceTicks = 0; // цель на sublevel — всегда «видима»
                } else if (++losCheckCounter >= LOS_CHECK_INTERVAL) {
                    losCheckCounter = 0;
                    boolean hasLos = controllerSubLevel != null
                            ? LineOfSightUtil.hasLineOfSightToEntityFromSubLevel(controllerSubLevel, tickMuzzlePos, e)
                            : LineOfSightUtil.hasLineOfSightToEntity(main != null ? main : sl, tickMuzzlePos, e);
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
            scanForTarget(level, sl, scanLevel, tickWorldCenter, tickMuzzlePos);
            if (currentTargetUUID != null && !currentTargetUUID.equals(prev)) alignedTicks = 0;
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
                TargetSnapshot snap = computeTargetSnapshot(sl, e, tickMuzzlePos);
                aimAndFireAtEntity(sl, e, mount, snap);
            }
        }

        if (commanderTargetPos != null && currentTargetUUID == null) {
            aimAndFireAtCommander(sl, mount);
        }
    }

    // ── Inlined Yaw logic ─────────────────────────────────────────────────────
    private void tickYaw(PitchOrientedContraptionEntity mount) {
        double currentYaw = wrap360(mount.yaw);
        double desiredYaw = wrap360(targetYaw);
        double diff       = shortestYawDelta(currentYaw, desiredYaw);
        boolean snapped   = Math.abs(diff) <= YAW_DEADBAND_DEG;

        if (snapped) {
            setYaw((float) desiredYaw);
            yawDirty = false;
        } else {
            double step = Math.min(Math.abs(diff), YAW_MAX_DEG_PER_TICK) * Math.signum(diff);
            setYaw((float) wrap360(currentYaw + step));
        }
    }

    private static double wrap360(double deg) {
        deg %= 360.0;
        if (deg < 0.0) deg += 360.0;
        return deg;
    }

    private static double shortestYawDelta(double from, double to) {
        return (to - from + 540.0) % 360.0 - 180.0;
    }

    // ── Inlined Pitch logic ───────────────────────────────────────────────────
    private void tickPitch(PitchOrientedContraptionEntity mount) {
        // См. пояснение к setPitch()/applyRotation(): cannonPitch — логическая
        // (world-space) величина, mount.pitch (raw) = cannonPitch * sgn.
        float sgn               = getContraptionSign();
        float currentWorldPitch = mount.pitch * sgn;   // raw → world
        float diff              = targetPitch - currentWorldPitch;
        boolean snapped         = Math.abs(diff) <= PITCH_DEADBAND_DEG;

        if (snapped) {
            setPitch(targetPitch);   // setPitch ожидает world-space, НЕ raw
            pitchDirty = false;
        } else {
            float step = Math.min(Math.abs(diff), PITCH_MAX_DEG_PER_TICK) * Math.signum(diff);
            setPitch(currentWorldPitch + step);  // setPitch ожидает world-space, НЕ raw
        }
    }

    // ── Inlined Fire logic ────────────────────────────────────────────────────
    private void tickFire(Level level, PitchOrientedContraptionEntity mount) {
        ServerLevel sl = resolveServerLevel(level);
        if (sl == null) return;
        if (!(mount.getContraption() instanceof AbstractMountedCannonContraption cannon)) return;

        if (cancelRequested) {
            cancelRequested = false;
            fireRequested    = false;
            cannon.onRedstoneUpdate(sl, mount, false, 0, this);
            return;
        }
        if (!fireRequested && !broadcastFireRequested) return;
        fireRequested          = false;
        broadcastFireRequested = false;
        cannon.onRedstoneUpdate(sl, mount, true, 15, this);
    }

    // ── Aim setters ──────────────────────────────────────────────────────────
    private void setTargetYaw(float yaw) {
        this.targetYaw = yaw;
        this.yawDirty  = true;
    }

    private void setTargetPitch(float pitch) {
        this.targetPitch = pitch;
        this.pitchDirty  = true;
    }

    private void doRequestFire() {
        this.fireRequested   = true;
        this.cancelRequested = false;
    }

    private void doCancelFire() {
        this.fireRequested   = false;
        this.cancelRequested = true;
    }

    // ── Aim / Fire wrappers (previously delegated to helper BEs) ─────────────
    private void applyAim(float wantedYaw, float wantedPitch) {
        if (allowHorizontal) setTargetYaw(wantedYaw);
        if (allowVertical)   setTargetPitch(wantedPitch);
    }

    private void requestFire(ServerLevel level) {
        doRequestFire();
        fireCooldown = MIN_FIRE_COOLDOWN;
        alignedTicks = 0;
        broadcastFireToFrequencyPeers(level);
    }

    /**
     * Рассылает команду "открыть огонь" всем контроллерам с той же (ненулевой)
     * частотой fireFrequency в радиусе FIRE_FREQUENCY_RADIUS блоков от этого
     * контроллера. Получатели откроют огонь по своей текущей ориентации,
     * даже если сами не навелись ни на одну цель. Дистанция считается по
     * прямой (евклидово расстояние), без учёта препятствий.
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
     * Не требует навёденной цели — просто триггерит выстрел текущей пушки
     * в её текущем положении.
     */
    public void receiveBroadcastFire() {
        this.broadcastFireRequested = true;
    }

    private void dropEntityTarget(ServerLevel level) {
        currentTargetUUID    = null;
        currentTargetOnSubLevel = false;
        confirmTicks         = 0;
        losGraceTicks        = 0;
        doCancelFire();
        alignedTicks         = 0;
        // Инвалидируем кэш баллистики — цель сменилась
        entityAimCache       = null;
        entityAimCacheMuzzle = null;
        entityAimCacheTarget = null;
        entityAimCacheRelVel = null;
        entityAimCacheAge    = 0;
        // Сбрасываем сглаженную скорость упреждения — она относилась к утраченной цели.
        velTrackUUID      = null;
        posHistoryCount   = 0;
        posHistoryHead    = 0;
        smoothedTargetVel = Vec3.ZERO;
        // Сбрасываем оценку угловой скорости наведения — она относилась к утраченной цели.
        hasPrevWantedAim = false;
    }

    // ── Scanning ──────────────────────────────────────────────────────────────
    private void scanForTarget(Level level, ServerLevel mainLevel, Level scanLevel, Vec3 worldCenter, Vec3 muzzle) {
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

        // Дополнительно ищем живые entity во всех sublevel-кораблях.
        // Сущности внутри sublevel'а находятся в его собственном Level и не видны
        // через обычный mainLevel.getEntitiesOfClass — точно та же проблема,
        // что и с командерами на кораблях (решена через findCommandersInAllSubLevels).
        // Для таких целей LOS через блоки не проверяем (см. currentTargetOnSubLevel).
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
            boolean los = (controllerSubLevel != null)
                    ? LineOfSightUtil.hasLineOfSightToEntityFromSubLevel(controllerSubLevel, muzzle, candidate)
                    : LineOfSightUtil.hasLineOfSightToEntity(mainLevel, muzzle, candidate);
            LOGGER.info("[Scan] {} LOS-check {} muzzle={} target={} subLevel={} los={}",
                    worldPosition, candidate.getClass().getSimpleName(), muzzle, candidate.position(),
                    controllerSubLevel != null, los);
            if (los) { chosen = candidate; break; }
        }
        if (chosen == null && !toCheck.isEmpty()) {
            LOGGER.info("[Scan] {} no candidate passed LOS out of {} checked", worldPosition, toCheck.size());
        }

        // Если в главном мире цель не найдена — ищем на sublevel-кораблях.
        // LOS не проверяем: стены чужого корабля не блокируют наводку.
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
                alignedTicks  = 0;
                losGraceTicks = 0;
            }
            commanderTargetPos = null;
            return;
        }

        // Скан не нашёл цель (не в AABB-кандидатах либо не прошла LOS среди
        // проверенных). Прежде чем сбрасывать currentTargetUUID, даём шанс
        // grace-периоду — та же логика, что уже используется для outOfRange/LOS
        // в основном тике (см. losGraceTicks/LOS_GRACE_TICKS_MAX выше). Раньше
        // это ветвление сбрасывало цель сразу, в обход grace, что приводило к
        // более резкой потере цели через скан, чем через обычный per-tick путь.
        if (prevUUID != null) {
            Entity prevEntity = mainLevel.getEntity(prevUUID);
            if (prevEntity == null && controllerSubLevel != null)
                prevEntity = controllerSubLevel.getLevel().getEntity(prevUUID);
            // Ищем в sublevel-кораблях если цель была на одном из них
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
                // Цель ещё валидна, просто временно не попала в скан-результат.
                // Оставляем currentTargetUUID как есть, ничего не сбрасываем.
                return;
            }
        }

        currentTargetUUID = null;
        confirmTicks      = 0;
        losGraceTicks     = 0;
        doCancelFire();
        alignedTicks      = 0;
        hasPrevWantedAim  = false;
        velTrackUUID      = null;
        posHistoryCount   = 0;
        posHistoryHead    = 0;
        smoothedTargetVel = Vec3.ZERO;
        scanForCommanderTargets(scanLevel, mainLevel, worldCenter, muzzle);
    }

    private void scanForCommanderTargets(Level scanLevel, ServerLevel mainLevel, Vec3 worldCenter, Vec3 muzzle) {
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

        // ИСПРАВЛЕНО: раньше ключом seen-мапы был cmd.getBlockPos() — ЛОКАЛЬНЫЕ
        // координаты командера в его собственной системе отсчёта (главный мир
        // ИЛИ SubLevel корабля). Ниже, при выборе ближайшего (Collections.min),
        // этот локальный BlockPos сравнивался как мировая позиция
        // (e.getKey().getCenter().distanceToSqr(worldCenter)) — что верно
        // только для командеров в главном мире. Для командеров, найденных
        // через findCommandersInRadius(scanLevel,...)/(mainLevel,...) НА
        // ДРУГОМ корабле/SubLevel, их "локальный" BlockPos подставлялся как
        // мировой без какой-либо конвертации — то же самое искажение,
        // из-за которого разные структуры "не видели" вражеские командеры
        // друг у друга. Строки, идущие через SableCompat (ниже), уже были
        // не подвержены багу — там сразу использовался BlockPos.containing
        // (entry.worldPos()), реально сконвертированная мировая позиция.
        // Теперь findCommandersInRadius возвращает CommanderHit с готовой
        // мировой позицией (hit.worldPos) для всех трёх источников —
        // используем её как ключ везде одинаково.
        java.util.LinkedHashMap<BlockPos, CommanderBlockEntity> seen = new java.util.LinkedHashMap<>();
        for (CommanderBlockEntity.CommanderHit hit :
                CommanderBlockEntity.findCommandersInRadius(scanLevel, worldPosition, worldCenter, radius, controllerSubLevel))
            seen.put(BlockPos.containing(hit.worldPos), hit.commander);

        if (controllerSubLevel != null) {
            BlockPos worldOriginBlock = BlockPos.containing(worldCenter);
            // Второй проход — явно через главный мир, selfSubLevel=null
            // (mainLevel гарантированно не корабль, координаты там уже мировые).
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

        // Нужен только ближайший командер, поэтому ищем минимум за один проход
        // (O(n)) вместо полной сортировки (O(n log n)). Дистанция берётся прямо
        // из entry.getKey() — без обратного линейного поиска по seen, который
        // раньше превращал это в O(n^2 log n).
        java.util.Map.Entry<BlockPos, CommanderBlockEntity> nearest =
                Collections.min(seen.entrySet(),
                        Comparator.comparingDouble(e -> e.getKey().getCenter().distanceToSqr(worldCenter)));

        BlockPos chosen = nearest.getKey();

        // Инвалидируем кэш баллистики командера если цель сменилась
        if (chosen != null && !chosen.equals(commanderTargetPos)) {
            cmdAimCache       = null;
            cmdAimCacheMuzzle = null;
            cmdAimCacheTarget = null;
            cmdAimCacheAge    = 0;
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
        // Если цель на sublevel-корабле, её position() — локальные координаты.
        // Конвертируем в мировые, переиспользуя worldPos из findLivingEntitiesInAllSubLevels.
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
        // Для sublevel-цели getDeltaMovement() — скорость в локальной системе корабля.
        // Трансформируем в мировую (только вращение, без трансляции — это velocity).
        // Используем сглаженную (EMA) скорость по фактическому смещению мировой
        // позиции цели вместо «сырой» target.getDeltaMovement() — см. пояснение
        // у updateSmoothedTargetVelocity(): сырая скорость слишком шумит для
        // устойчивого упреждения, из-за чего орудие фактически не опережало цель.
        Vec3 targetVel;
        if (currentTargetOnSubLevel && SableCompat.isAvailable()) {
            Vec3 rawLocalVel = target.getDeltaMovement();
            targetVel = rawLocalVel; // fallback, перезаписывается ниже если resolved
            ServerLevel ml = mainLevel(level);
            if (ml != null) {
                for (var _entry : SableCompat.findLivingEntitiesInAllSubLevels(
                        ml, approxMuzzle, getScanRadius() * 2, LivingEntity.class,
                        _e -> _e.getUUID().equals(currentTargetUUID))) {
                    // Скорость корабля в мировых координатах + локальная скорость entity
                    Vec3 shipVel = SableCompat.getShipVelocity(_entry.subLevel());
                    Vec3 wVel    = SableCompat.toWorldVelocity(_entry.subLevel(), rawLocalVel);
                    targetVel = wVel.add(shipVel);
                    break;
                }
            }
            // Для sublevel-целей сглаживание по мировой позиции ненадёжно (позиция
            // корабля сама постоянно меняется независимо от движения моба внутри
            // него), поэтому используем raw-скорость как есть — она уже не шумит
            // так сильно, потому что домножена на скорость корабля.
        } else {
            targetVel = updateSmoothedTargetVelocity(target, targetPos);
        }
        Vec3 relVel = new Vec3(
                targetVel.x - platVel.x,
                targetVel.y - platVel.y,
                targetVel.z - platVel.z);
        return new TargetSnapshot(targetPos, relVel);
    }

    private void aimAndFireAtEntity(ServerLevel level, Entity target, PitchOrientedContraptionEntity c,
                                    TargetSnapshot snap) {
        if (!(c.getContraption() instanceof AbstractMountedCannonContraption)) return;
        ownContraptionUUID = c.getUUID();

        Vec3 muzzle    = computeRealMuzzlePos(c);
        Vec3 targetPos = snap.targetPos();
        Vec3 relVel    = snap.relVel();
        Vec3 muzzleWorldPos = muzzle;

        // ── Ballistic cache ───────────────────────────────────────────────────
        // Пересчёт только если ствол или цель сместились, скорость изменилась,
        // или истёк принудительный интервал обновления.
        // Порог сравнивается с квадратом дистанции до цели (см. пояснение у
        // AIM_CACHE_POS_THRESHOLD_SQ) — иначе близкие цели пересчитывают кэш
        // намного чаще дальних при одинаковом абсолютном смещении в блоках.
        double distSqToTargetE = Math.max(muzzle.distanceToSqr(targetPos), 1.0);
        boolean needRecalc = entityAimCache == null
                || ++entityAimCacheAge >= AIM_CACHE_MAX_AGE
                || muzzle.distanceToSqr(entityAimCacheMuzzle) / distSqToTargetE > AIM_CACHE_POS_THRESHOLD_SQ
                || targetPos.distanceToSqr(entityAimCacheTarget) / distSqToTargetE > AIM_CACHE_POS_THRESHOLD_SQ
                || relVel.subtract(entityAimCacheRelVel).lengthSqr() > AIM_CACHE_VEL_THRESHOLD_SQ;

        float sgn          = getContraptionSign();
        float currentPitch = c.pitch * sgn;

        if (needRecalc) {
            // Use world-space pitch limits: for inverted cannons (sgn=-1) depression
            // and elevation are physically swapped relative to world space.
            // Traverse-Compensated Lead: сдвигаем точку прицеливания вперёд на
            // оценочное время доворота ствола до предыдущей аим-точки — см.
            // estimateTraverseTicks(). Используем relVel (уже сглаженную
            // скорость цели относительно платформы) как экстраполятор:
            // targetPos смещается так, будто цель продолжит двигаться с той
            // же скоростью ещё traverseTicks тиков сверху обычного упреждения
            // по времени полёта, которое считает сам BallisticSolver.
            double traverseTicks = estimateTraverseTicks(c.yaw, currentPitch,
                    prevWantedYaw, prevWantedPitch, hasPrevWantedAim);
            Vec3 traverseAdjustedTarget = traverseTicks > 0.0
                    ? targetPos.add(relVel.scale(traverseTicks))
                    : targetPos;
            entityAimCache       = BallisticSolver.solve(muzzle, traverseAdjustedTarget, relVel,
                    CBCAutoTargetConfig.MUZZLE_SPEED_BLOCKS_PER_TICK.get(),
                    CBCAutoTargetConfig.DEFAULT_GRAVITY.get(),
                    CBCAutoTargetConfig.DEFAULT_DRAG.get(),
                    false, worldMaxDepression(c, sgn), worldMaxElevation(c, sgn));
            entityAimCacheMuzzle = muzzle;
            entityAimCacheTarget = targetPos;
            entityAimCacheRelVel = relVel;
            entityAimCacheAge    = 0;
            LOGGER.debug("[AimCache] entity recalc at {} traverseTicks={}", worldPosition, traverseTicks);
        }
        double[] aim = entityAimCache;
        // ─────────────────────────────────────────────────────────────────────

        float[] local       = ShipAimSolver.toLocalAim(aim[0], aim[1], controllerSubLevel);
        float   wantedYaw   = local[0];
        float   wantedPitch = local[1];

        applyAim(wantedYaw, wantedPitch);

        // Заблокированная ось (allowHorizontal/allowVertical = false) физически
        // не может довернуться до wantedYaw/wantedPitch, поэтому сравнивать
        // "желаемый" угол с фактическим для неё бессмысленно — она никогда не
        // станет "ok" и просто заблокирует стрельбу навсегда. Для заблокированной
        // оси условие готовности считается выполненным автоматически: стреляем
        // с тем углом, который уже есть.
        // Угловая скорость требуемого наведения между тиками — чем быстрее
        // меняется wantedYaw/wantedPitch (манёвренная близкая цель), тем
        // шире допуск, иначе alignedTicks никогда не наберёт REQUIRED_ALIGNED_TICKS
        // подряд и орудие не выстрелит, пока цель не остановится.
        double yawTolExtra = 0, pitchTolExtra = 0;
        if (hasPrevWantedAim) {
            double yawRate   = Math.abs(angleDiff(wantedYaw, prevWantedYaw));
            double pitchRate = Math.abs(wantedPitch - prevWantedPitch);
            yawTolExtra   = Math.min(yawRate   * AIM_RATE_TOLERANCE_GAIN, AIM_RATE_TOLERANCE_MAX);
            pitchTolExtra = Math.min(pitchRate * AIM_RATE_TOLERANCE_GAIN, AIM_RATE_TOLERANCE_MAX);
        }
        prevWantedYaw   = wantedYaw;
        prevWantedPitch = wantedPitch;
        hasPrevWantedAim = true;

        boolean yawOk   = !allowHorizontal
                || Math.abs(angleDiff(wantedYaw, c.yaw)) < BallisticSolver.YAW_TOLERANCE + yawTolExtra;
        boolean pitchOk = !allowVertical
                || Math.abs(wantedPitch - currentPitch) < BallisticSolver.PITCH_TOLERANCE + pitchTolExtra;

        if (fireCooldown > 0) fireCooldown--;
        alignedTicks = (yawOk && pitchOk) ? alignedTicks + 1 : 0;

        LOGGER.debug("[AimGate] entity pos={} wantedYaw={} curYaw={} yawDiff={} yawTol={} yawOk={} " +
                        "wantedPitch={} curPitch={} pitchDiff={} pitchTol={} pitchOk={} alignedTicks={} " +
                        "fireCooldown={} confirmTicks={}",
                worldPosition, wantedYaw, c.yaw, angleDiff(wantedYaw, c.yaw),
                BallisticSolver.YAW_TOLERANCE + yawTolExtra, yawOk,
                wantedPitch, currentPitch, wantedPitch - currentPitch,
                BallisticSolver.PITCH_TOLERANCE + pitchTolExtra, pitchOk,
                alignedTicks, fireCooldown, confirmTicks);

        if (yawOk && pitchOk && alignedTicks >= REQUIRED_ALIGNED_TICKS
                && fireCooldown == 0 && confirmTicks >= 1) {
            Entity check = level.getEntity(currentTargetUUID);
            // Если цель на sublevel — ищем её там
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
            // LOS перед выстрелом: для sublevel-цели не проверяем (стены корабля-цели
            // не блокируют выстрел — аналогично логике commander-цели).
            boolean fireLos;
            if (currentTargetOnSubLevel) {
                fireLos = check != null;
            } else {
                ServerLevel ml = mainLevel(level);
                fireLos = (check != null) && ((controllerSubLevel != null)
                        ? LineOfSightUtil.hasLineOfSightToEntityFromSubLevel(controllerSubLevel, muzzleWorldPos, check)
                        : LineOfSightUtil.hasLineOfSightToEntity(ml, muzzleWorldPos, check));
            }
            if (check == null || !check.isAlive() || !fireLos) {
                currentTargetUUID = null; currentTargetOnSubLevel = false;
                alignedTicks = 0; return;
            }
            requestFire(level);
        }
    }

    private static final Logger LOGGER_AIM_CMD = LoggerFactory.getLogger("cbc_autotarget/AimAtCommander");

    private void aimAndFireAtCommander(ServerLevel level, PitchOrientedContraptionEntity mount) {
        if (commanderTargetPos == null) return;

        Level scanLevel = (controllerSubLevel != null) ? controllerSubLevel.getLevel() : level;

        BlockEntity be = level.getBlockEntity(commanderTargetPos);
        if (!(be instanceof CommanderBlockEntity) && controllerSubLevel != null)
            be = scanLevel.getBlockEntity(commanderTargetPos);
        if (!(be instanceof CommanderBlockEntity) && SableCompat.isAvailable()) {
            // ИСПРАВЛЕНО: commanderTargetPos хранит МИРОВУЮ позицию цели на
            // момент последнего scanForCommanderTargets() (см. фикс с
            // hit.worldPos) — но если цель стоит на ДВИЖУЩЕМСЯ корабле,
            // между сканированием (реже) и этим вызовом (каждый тик
            // стрельбы) она успевает сместиться на несколько блоков.
            // Радиус fallback-поиска в 2 блока был слишком узким —
            // корабль, движущийся хотя бы с небольшой скоростью, выводил
            // цель за пределы этого окна почти сразу после обнаружения,
            // прежде чем Controller успевал навестись/выстрелить. Отсюда
            // "нашёл цель, но тут же снова её терял и не стрелял".
            // Расширяем окно поиска и логируем исход для диагностики.
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

        PitchOrientedContraptionEntity c = mount;
        if (!(c.getContraption() instanceof AbstractMountedCannonContraption)) return;
        ownContraptionUUID = c.getUUID();

        Vec3 muzzle    = computeRealMuzzlePos(c);
        // ИСПРАВЛЕНО: раньше здесь стояло commanderTargetPos.getCenter() —
        // это ЗАКЭШИРОВАННАЯ мировая позиция на момент последнего
        // scanForCommanderTargets()/fallback-резолва, округлённая до целого
        // блока. Комментарий ниже ошибочно предполагал "командер стоит на
        // месте" — но если он находится на корабле Sable, его мировая
        // позиция меняется каждый тик вместе с движением корабля. Берём
        // актуальную позицию прямо у найденного targetCmd (CommanderBlockEntity.
        // getWorldPos(), уже учитывает SubLevel-конвертацию) — так наводка
        // остаётся точной, даже если между сканированием и этим выстрелом
        // корабль-цель успел сместиться.
        Vec3 targetPos = targetCmd.getWorldPos();
        Vec3 platVel   = getPlatformVelocity();
        Vec3 relVel    = Vec3.ZERO.subtract(platVel);

        // ── Ballistic cache (commander) ───────────────────────────────────────
        // Командер стоит на месте — кэш живёт дольше (CMD_AIM_CACHE_MAX_AGE тиков).
        // Инвалидация по порогу позиции нужна если командер на корабле Sable.
        boolean needRecalc = cmdAimCache == null
                || ++cmdAimCacheAge >= CMD_AIM_CACHE_MAX_AGE
                || muzzle.distanceToSqr(cmdAimCacheMuzzle) / Math.max(muzzle.distanceToSqr(targetPos), 1.0) > CMD_AIM_CACHE_POS_THRESHOLD_SQ
                || targetPos.distanceToSqr(cmdAimCacheTarget) / Math.max(muzzle.distanceToSqr(targetPos), 1.0) > CMD_AIM_CACHE_POS_THRESHOLD_SQ;

        if (needRecalc) {
            // Same world-space limit correction for commander targets.
            float sgnC = getContraptionSign();
            cmdAimCache       = BallisticSolver.solve(muzzle, targetPos, relVel,
                    CBCAutoTargetConfig.MUZZLE_SPEED_BLOCKS_PER_TICK.get(),
                    CBCAutoTargetConfig.DEFAULT_GRAVITY.get(),
                    CBCAutoTargetConfig.DEFAULT_DRAG.get(),
                    false, worldMaxDepression(c, sgnC), worldMaxElevation(c, sgnC));
            cmdAimCacheMuzzle = muzzle;
            cmdAimCacheTarget = targetPos;
            cmdAimCacheAge    = 0;
            LOGGER.debug("[AimCache] commander recalc at {}", worldPosition);
        }
        double[] aim = cmdAimCache;
        // ─────────────────────────────────────────────────────────────────────

        float[] local       = ShipAimSolver.toLocalAim(aim[0], aim[1], controllerSubLevel);
        float   wantedYaw   = local[0];
        float   wantedPitch = local[1];

        applyAim(wantedYaw, wantedPitch);

        float   sgn          = getContraptionSign();
        float   currentPitch = c.pitch * sgn;
        // См. пояснение в aimAndFireAtEntity: заблокированная ось всегда
        // считается готовой, иначе она никогда не станет "ok" и заблокирует
        // огонь по командеру навсегда.
        boolean yawOk   = !allowHorizontal
                || Math.abs(angleDiff(wantedYaw, c.yaw)) < BallisticSolver.YAW_TOLERANCE;
        boolean pitchOk = !allowVertical
                || Math.abs(wantedPitch - currentPitch) < BallisticSolver.PITCH_TOLERANCE;

        if (fireCooldown > 0) fireCooldown--;
        alignedTicks = (yawOk && pitchOk) ? alignedTicks + 1 : 0;

        if (yawOk && pitchOk && alignedTicks >= REQUIRED_ALIGNED_TICKS && fireCooldown == 0) {
            requestFire(level);
        }
    }

    // ── Traverse-Compensated Lead (компенсация времени доворота ствола) ────────
    // Физический предел скорости поворота ствола (YAW_MAX_DEG_PER_TICK/
    // PITCH_MAX_DEG_PER_TICK) — жёсткая кинематическая граница, которую
    // никаким сглаживанием скорости цели не обойти: при достаточно быстром
    // угловом движении цели относительно пушки (например, цель бежит по
    // спирали и сближается) требуемая скорость доворота начинает превышать
    // физический лимит ствола, и пушка гарантированно отстаёт от расчётной
    // точки упреждения, пока не собьётся дистанция/угловая скорость.
    //
    // BallisticSolver.solve() уже даёт упреждение по времени ПОЛЁТА снаряда,
    // но целится в точку "как если бы ствол телепортировался туда мгновенно".
    // Добавляем вторую фазу упреждения: перед расчётом баллистики сдвигаем
    // точку прицеливания вперёд по времени ДОВОРОТА ствола от текущего угла
    // до предыдущей расчётной точки — так пушка целится не в то, "где цель
    // сейчас плюс время полёта", а в то, "где цель будет к моменту, когда
    // ствол физически туда довернётся, плюс время полёта". Это позволяет
    // стволу "срезать" траекторию упреждения вместо бесконечной погони за
    // постоянно убегающей целью.
    //
    // Оценка времени доворота: угловое расстояние от текущего yaw/pitch
    // ствола до предыдущей аим-точки, делённое на физический лимит град/тик.
    // Берём максимум по осям (обе оси доворачиваются параллельно, финиш —
    // по более медленной). Ограничиваем сверху TRAVERSE_LEAD_MAX_TICKS,
    // чтобы при потере цели/резкой смене угла не улететь предсказанием в
    // бесконечность.
    private static final int TRAVERSE_LEAD_MAX_TICKS = 40;

    /**
     * Оценивает время доворота ствола (в тиках) от текущего мирового угла
     * наведения до последней расчётной wanted-точки.
     */
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


    /**
     * Знак конвертации между "raw" pitch контрапшена (c.pitch, хранится в
     * PitchOrientedContraptionEntity) и "логическим"/мировым pitch, которым
     * оперирует наш код (targetPitch, BallisticSolver и т.д.).
     *
     * Формула сверена напрямую с декомпилированным исходником CBC
     * (CannonMountBlockEntity.applyRotation() / getPitchOffset(), версия
     * 5.11.6 для MC 1.21.1) и полностью ему соответствует:
     *
     *   Direction dir = mountedContraption.getInitialOrientation();
     *   boolean flag = (dir.getAxisDirection() == POSITIVE) == (dir.getAxis() == Axis.X);
     *   float sgn = flag ? 1.0F : -1.0F;
     *
     * Это НЕ связано с тем, находится ли mount выше или ниже controller —
     * это чисто горизонтальный признак (по какой оси и в какую сторону
     * "смотрит" исходная ориентация контрапшена). Раньше здесь ошибочно
     * стояла привязка к вертикальному положению mount — это было неверно
     * и никак не являлось источником проблемы с зависанием/телепанием
     * пушки. Настоящая причина была в другом месте (см. tickPitch()).
     */
    private float getContraptionSign() {
        Direction d = getContraptionDirection();
        boolean flag = (d.getAxisDirection() == Direction.AxisDirection.POSITIVE)
                == (d.getAxis() == Direction.Axis.X);
        return flag ? 1.0f : -1.0f;
    }

    /**
     * ИСПРАВЛЕНО: раньше depression/elevation свопались местами при sgn=-1,
     * по аналогии с (ошибочным) предположением, что raw↔world конвертация
     * pitch должна затрагивать и пределы. Но в декомпилированном CBC видно
     * (CannonMountBlockEntity.tick()):
     *
     *   this.cannonPitch = Mth.clamp(newPitch % 360.0F, -getMaxDepress(), getMaxElevate());
     *
     * где getMaxDepress()/getMaxElevate() берутся из контрапшена НАПРЯМУЮ,
     * без какого-либо участия sgn. cannonPitch — уже логическая (мировая)
     * величина в той же системе координат, что и наш targetPitch, так что
     * никакого свопа депрессии/элевации по знаку контрапшена не требуется —
     * пределы одинаковы независимо от sgn.
     */
    private static float worldMaxDepression(PitchOrientedContraptionEntity c, float sgn) {
        return c.maximumDepression();
    }

    /** @see #worldMaxDepression */
    private static float worldMaxElevation(PitchOrientedContraptionEntity c, float sgn) {
        return c.maximumElevation();
    }

    private Vec3 getControllerWorldPos() {
        Vec3 local = Vec3.atCenterOf(worldPosition);
        return (controllerSubLevel != null) ? SableCompat.toWorldPos(controllerSubLevel, local) : local;
    }

    private Vec3 computeRealMuzzlePos(PitchOrientedContraptionEntity c) {
        Vec3 base = c.position();
        if (controllerSubLevel != null) base = SableCompat.toWorldPos(controllerSubLevel, base);
        double len = CBCAutoTargetConfig.BARREL_LENGTH.get();
        if (len <= 0.0) return base;
        // c.pitch is raw (CBC internal). For inverted cannons (sgn=-1) the physical
        // barrel direction is opposite to raw pitch, so we must use worldPitch = raw * sgn.
        float sgn = getContraptionSign();
        double yawRad   = Math.toRadians(-c.yaw + 90.0);
        double pitchRad = Math.toRadians(c.pitch * sgn);   // world-space pitch
        double cosP = Math.cos(pitchRad);
        return base.add(cosP * Math.cos(yawRad) * len,
                Math.sin(pitchRad) * len,
                cosP * Math.sin(yawRad) * len);
    }

    private Vec3 getPlatformVelocity() {
        return (controllerSubLevel != null && SableCompat.isAvailable())
                ? SableCompat.getShipVelocity(controllerSubLevel) : Vec3.ZERO;
    }

    /**
     * Возвращает ServerLevel для работы с entity/scanning.
     * Если level является ContraptionLevel (не instanceof ServerLevel),
     * получаем реальный ServerLevel через MinecraftServer.
     * Возвращает null если получить не удалось.
     */
    @Nullable
    private ServerLevel resolveServerLevel(Level level) {
        if (level instanceof ServerLevel sl) return sl;
        if (level.getServer() != null) {
            // Пробуем получить уровень по текущему ключу измерения
            ServerLevel sl = level.getServer().getLevel(level.dimension());
            if (sl != null) return sl;
            // Fallback: overworld
            return level.getServer().getLevel(Level.OVERWORLD);
        }
        return null;
    }

    private ServerLevel mainLevel(Level level) {
        if (level instanceof ServerLevel sl) return sl;
        // ContraptionLevel или другой виртуальный уровень — получаем overworld-ServerLevel
        // через сервер (dimension ключ может не совпадать, берём overworld как fallback)
        if (level.getServer() != null) {
            ServerLevel sl = level.getServer().getLevel(Level.OVERWORLD);
            if (sl != null) return sl;
        }
        if (controllerSubLevel != null)
            return (ServerLevel) level.getServer().getLevel(level.dimension());
        return null;
    }

    // ── ControlPitchContraption / ControlPitchContraption.Block ────────────────
    // Реализация протокола CBC напрямую этим блоком — раньше это делал
    // отдельный CannonMountBlockEntity, теперь Controller сам себе mount.
    // Перенос методов из CannonMountBlockEntity (версия 5.11.3).

    @Override
    public BlockState getControllerState() {
        return getBlockState();
    }

    @Override
    public boolean isAttachedTo(AbstractContraptionEntity entity) {
        return this.mountedContraption == entity;
    }

    @Override
    public void attach(PitchOrientedContraptionEntity contraption) {
        if (!(contraption.getContraption() instanceof AbstractMountedCannonContraption)) return;
        this.mountedContraption = contraption;
        if (level != null && !level.isClientSide) {
            this.running = true;
            setChanged();
        }
    }

    @Override
    public void onStall() {
        // CannonMountBlockEntity здесь вызывает this.sendData() (обновление клиентского
        // стейта самого BlockEntity) — у нас эквивалент это setChanged().
        if (level != null && !level.isClientSide) setChanged();
    }

    @Override
    public void disassemble() {
        disassembleCannon();
    }

    @Override
    public BlockPos getControllerBlockPos() {
        return worldPosition;
    }

    @Override
    public void markForReassembly() {
        // CBC 5.11.6: помечает, что контрапшен нужно пересобрать (например, после
        // hot-reload/выгрузки), не выключая сам Controller. running остаётся true,
        // поэтому tick() (см. assembleCannon вызов) пересоберёт пушку на следующем тике.
        if (mountedContraption != null) {
            mountedContraption.disassemble();
            mountedContraption = null;
        }
        setChanged();
    }

    @Override
    public Vec3 getDismountPositionForContraption(PitchOrientedContraptionEntity poce) {
        // Оригинал (CannonMountBlockEntity) спешивает игрока в противоположную от
        // казённика сторону, используя своё blockstate-свойство VERTICAL_DIRECTION.
        // У нас нет фиксированной оси — казённик может быть на любой из 6 граней,
        // поэтому берём направление, противоположное реальной ориентации ствола.
        Direction back = poce.getInitialOrientation().getOpposite();
        return Vec3.atBottomCenterOf(worldPosition.relative(back));
    }

    /**
     * Самостоятельная сборка пушки — аналог CannonMountBlockEntity.assemble() /
     * FixedCannonMountBlockEntity.assemble(), но без отдельного блока-крепления
     * и без фиксированного направления: ищем казённик (CannonContraptionProviderBlock)
     * на любой из 6 граней Controller'а и собираем контрапшен в ту сторону.
     */
    private void assembleCannon(Level level, BlockPos pos) {
        if (mountedContraption != null) return; // уже собрана

        for (Direction dir : Direction.values()) {
            BlockPos assemblyPos = pos.relative(dir);
            if (level.isOutsideBuildHeight(assemblyPos)) continue;
            if (!(level.getBlockState(assemblyPos).getBlock() instanceof CannonContraptionProviderBlock provBlock)) continue;

            AbstractMountedCannonContraption mountedCannon = provBlock.getCannonContraption();
            if (mountedCannon == null) continue;
            try {
                if (!mountedCannon.assemble(level, assemblyPos)) continue; // не собралась (не хватает блоков и т.п.)
            } catch (AssemblyException e) {
                lastAssemblyException = e;
                LOGGER.debug("[assemble] dir={} failed at {}: {}", dir, assemblyPos, e.getMessage());
                continue; // эта грань не подошла — пробуем следующую
            }

            // Направление ствола берём из уже собранного контрапшена, а не из
            // направления, в котором мы искали казённик — это разные вещи
            // (см. CannonMountBlockEntity.assemble(): facing1 = mountedCannon.initialOrientation()).
            Direction facing1 = mountedCannon.initialOrientation();
            mountedCannon.removeBlocksFromWorld(level, BlockPos.ZERO);
            PitchOrientedContraptionEntity contraptionEntity =
                    PitchOrientedContraptionEntity.create(level, mountedCannon, facing1, this);
            this.mountedContraption = contraptionEntity;
            this.running = true;
            this.lastAssemblyException = null;
            this.assembledFromPos = assemblyPos;
            resetContraptionToOffset();
            level.addFreshEntity(contraptionEntity);
            setChanged();

            AllSoundEvents.CONTRAPTION_ASSEMBLE.playOnServer(level, pos);
            return;
        }
        // Ни на одной из 6 граней не нашлось подходящего казённика — тихо не собираем
        // (как FixedCannonMountBlockEntity делает через AssemblyException при отсутствии блока).
    }

    public void disassembleCannon() {
        if (!running && mountedContraption == null) return;
        if (mountedContraption != null) {
            resetContraptionToOffset();
            mountedContraption.save(new CompoundTag()); // Crude refresh of block data — как в CBC
            mountedContraption.disassemble();
            AllSoundEvents.CONTRAPTION_DISASSEMBLE.playOnServer(level, worldPosition);
        }
        running = false;
        mountedContraption = null;
        assembledFromPos = null;
        setChanged();
    }

    /** Аналог CannonMountBlockEntity.resetContraptionToOffset(). */
    private void resetContraptionToOffset() {
        if (mountedContraption == null) return;
        cannonPitch     = 0;
        cannonYaw       = getContraptionDirection().toYRot();
        prevCannonPitch = cannonPitch;
        prevCannonYaw   = cannonYaw;

        mountedContraption.pitch     = cannonPitch;
        mountedContraption.yaw       = cannonYaw;
        mountedContraption.prevPitch = mountedContraption.pitch;
        mountedContraption.prevYaw   = mountedContraption.yaw;

        mountedContraption.setXRot(cannonPitch);
        mountedContraption.setYRot(cannonYaw);
        mountedContraption.xRotO = mountedContraption.getXRot();
        mountedContraption.yRotO = mountedContraption.getYRot();

        // Контрапшен физически стоит там, где была собрана пушка (казённик
        // рядом с Controller'ом), а не внутри блока самого контроллера.
        BlockPos offsetPos = assembledFromPos != null ? assembledFromPos : worldPosition;
        mountedContraption.setPos(Vec3.atBottomCenterOf(offsetPos));
    }

    /**
     * Аналог CannonMountBlockEntity.applyRotation() — переносит наши
     * cannonYaw/cannonPitch (или, если пушку крутит что-то другое —
     * canBeTurnedByController()==false, — читает угол оттуда) в контрапшен.
     * Вызывается каждый server tick из tick(), т.к. раньше это делал
     * CannonMountBlockEntity.tick(), которого в этой цепочке больше нет.
     */
    private void applyRotation() {
        if (mountedContraption == null) return;
        float sgn = getContraptionSign();

        boolean canTurn = mountedContraption.canBeTurnedByController(this);
        if (!canTurn) {
            float d = -mountedContraption.maximumDepression();
            float e = mountedContraption.maximumElevation();
            cannonPitch = net.minecraft.util.Mth.clamp(mountedContraption.pitch, d, e) * sgn;
            cannonYaw   = mountedContraption.yaw;
        } else {
            // prevPitch/prevYaw двигаем вперёд ПЕРЕД записью нового pitch/yaw —
            // иначе CBCContraptionRotationState (рендер) интерполирует между
            // застывшим значением с момента сборки (resetContraptionToOffset)
            // и текущим, из-за чего ствол визуально не следует за реальным
            // углом наводки, хотя pitch/yaw физически верны каждый тик.
            mountedContraption.prevPitch = mountedContraption.pitch;
            mountedContraption.prevYaw   = mountedContraption.yaw;
            mountedContraption.pitch = cannonPitch * sgn;
            mountedContraption.yaw   = cannonYaw;
        }
    }

    private void setYaw(float yaw)     { this.cannonYaw   = yaw; }
    private void setPitch(float pitch) { this.cannonPitch = pitch; }

    private Direction getContraptionDirection() {
        return mountedContraption == null ? Direction.NORTH : mountedContraption.getInitialOrientation();
    }

    private void tryTransferToCannon(Level level) {
        if (mountedContraption == null) return;
        // Большая пушка (MountedBigCannonContraption) не реализует GetItemStorage и не имеет
        // обычного IItemHandler — зарядка снарядов/картриджей в её Quick-Firing Breech устроена
        // как замена блока в казённике, а не как вставка предмета (см. CannonMountPoint#bigCannonInsert).
        if (mountedContraption.getContraption() instanceof MountedBigCannonContraption bigCannon) {
            if (BigCannonBreechFeeder.feed(bigCannon, mountedContraption, inventory)) setChanged();
            return;
        }

        // Автопушка (MountedAutocannonContraption) отдаёт IItemHandler через саму
        // contraption-сущность (см. CannonMountBlockEntity.getItemHandler) — кормим напрямую.
        IItemHandler h = mountedContraption.getCapability(Capabilities.ItemHandler.ENTITY);
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
        // ContraptionLevel не поддерживает setBlock — пропускаем
        if (level instanceof ServerLevel) {
            level.setBlock(worldPosition, getBlockState().setValue(ControllerBlock.ACTIVE, active), 3);
            // Синхронизируем BE-данные (active и т.д.) с клиентом
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }

        if (!active) {
            doCancelFire();
            broadcastFireRequested = false;
            // Инвалидируем оба кэша баллистики при деактивации
            entityAimCache       = null;
            entityAimCacheMuzzle = null;
            entityAimCacheTarget = null;
            entityAimCacheRelVel = null;
            entityAimCacheAge    = 0;
            cmdAimCache       = null;
            cmdAimCacheMuzzle = null;
            cmdAimCacheTarget = null;
            cmdAimCacheAge    = 0;
            ownerCommanderUUID = null; // Освобождаем привязку к командеру
        }
        currentTargetUUID       = null;
        currentTargetOnSubLevel = false;
        commanderTargetPos = null;
        confirmTicks  = 0;
        losGraceTicks = 0;
        alignedTicks  = 0;
        yawDirty      = false;
        pitchDirty    = false;

        if (newActive) {
            int iv   = CBCAutoTargetConfig.SCAN_INTERVAL_TICKS.get();
            int hash = (worldPosition.getX() * 73856093) ^ (worldPosition.getY() * 19349663)
                    ^ (worldPosition.getZ() * 83492791);
            scanTickCounter = Math.abs(hash % iv);

            // Тот же приём для перекладки патронов: без разброса все турели,
            // загруженные одновременно (например, при спавне корабля), пытаются
            // переложить патроны в один и тот же тик. Соль хэша другая, чтобы
            // фаза transfer не совпадала с фазой scan.
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

    // Сборка теперь только по редстоуну (см. tick()) — при установке блока
    // самостоятельно ничего не собираем.
    public void onPlaced()  { }
    public void onRemoved() { disassembleCannon(); }

    // ── Клиентский реестр для рендерера оверлея ───────────────────────────────
    // Хранит позиции всех загруженных ControllerBlockEntity на клиенте.
    // Используется CannonMountOverlayRenderer вместо недоступного blockEntityList.
    private static final java.util.concurrent.ConcurrentHashMap<BlockPos, Boolean> CLIENT_REGISTRY =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static java.util.Set<BlockPos> getClientRegistry() {
        return CLIENT_REGISTRY.keySet();
    }

    // ── Серверный реестр ───────────────────────────────────────────────────────
    // Хранит все загруженные ControllerBlockEntity, сгруппированные по ключу
    // измерения (dimension). SubLevel у Sable имеет собственный уникальный ключ
    // измерения, поэтому контроллеры внутри SubLevel хранятся под ним отдельно
    // от контроллеров основного мира. Это позволяет CommanderBlockEntity
    // находить контроллеры в любом SubLevel за O(n) без перебора блоков.
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
        // Намеренно НЕ сбрасываем schematicBackup здесь.
        // onLoad() вызывается между двумя loadAdditional при деплое схематики,
        // резерв должен дожить до второго loadAdditional и до writeSafeNbt().
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

                // Если active=true загружено из NBT (Sable hotswap) — переинициализируем
                // controllerSubLevel, scanTickCounter и transferTickCounter без
                // повторного вызова applyFromCommander.
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
        // При отключении оси немедленно останавливаем доигрывание уже начатого
        // поворота — иначе пушка успевает довернуться на несколько градусов
        // (до YAW_MAX_DEG_PER_TICK за тик) прежде чем yawDirty естественно
        // сбросится сам в tickYaw(), даже если applyAim() больше не выставляет
        // новую цель поворота.
        if (!v) yawDirty = false;
        setChanged();
    }

    public void setAllowVertical(boolean v) {
        this.allowVertical = v;
        if (!v) pitchDirty = false;
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
            // Активация: принимаем только если контроллер свободен (нет владельца)
            // или владелец — тот же командер.
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
            LOGGER.info("[applyFromCommander] APPLY activate={} active={} hasMountedContraption={} owner={} newMask={} at {}",
                    activate, active, mountedContraption != null, ownerCommanderUUID, Integer.toBinaryString(filterData.getMask()), worldPosition);
            // Принудительно обновляем SubLevel-кэш перед активацией, так как
            // блок мог быть пересоздан Sable (hotswap) или только что размещён.
            if (SableCompat.isAvailable() && level instanceof ServerLevel sl) {
                controllerSubLevel = SableCompat.getSubLevelForBlock(sl, worldPosition);
                subLevelCacheTimer = 0;
                LOGGER.debug("[applyFromCommander] refreshed controllerSubLevel={} at {}",
                        controllerSubLevel == null ? "null" : "present", worldPosition);
            }
            // Сборка пушки теперь идёт только по редстоуну (см. tick()) —
            // активация от командера просто включает active, без ребиндинга.
            if (!active) setActive(true);
        } else {
            // Деактивация: принимаем только от того командера, который активировал.
            if (ownerCommanderUUID != null && !ownerCommanderUUID.equals(srcCommanderUUID)) {
                LOGGER.info("[applyFromCommander] IGNORED deactivate from {} (owner={}), not our commander at {}",
                        srcCommanderUUID, ownerCommanderUUID, worldPosition);
                return;
            }
            filterData.setMask(cf.getMask());
            filterData.setWhitelistEnabled(cf.isWhitelistEnabled());
            filterData.replaceWhitelist(new ArrayList<>(cf.getWhitelist()));
            this.commanderPos = srcCommanderPos;
            ownerCommanderUUID = null; // Освобождаем контроллер
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
        return tag;
    }

    /**
     * Вызывается из SafeNbtWriterRegistry при deploy схематики Create.
     * Записывает в tag только те данные, которые должны сохраняться в схематике:
     * инвентарь (патроны) и настройки фильтра.
     * Позиционные данные (CannonMountPos, CommanderPos и т.д.) намеренно не пишем —
     * они привязаны к миру и после деплоя должны пересчитываться заново.
     */
    public void writeSafeNbt(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("Inventory", inventory.serializeNBT(registries));
        filterData.saveToNBT(tag);
        // Резерв больше не нужен — SafeNbtWriter вызывается последним при деплое.
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
        filterData.saveToNBT(tag);
    }

    // Резервная копия тега, сохранённая при первом loadAdditional с реальными данными.
    // Используется для восстановления если Create вызовет второй loadAdditional с пустым тегом.
    //
    // Реальный порядок вызовов Create при deploy схематики:
    //   1. loadAdditional(тег из схематики)  — содержит Inventory/FilterMask → сохраняем резерв
    //   2. onLoad()                           — блок помещён в мир
    //   3. loadAdditional(пустой тег)         — Create перезаписывает → восстанавливаем из резерва
    //   4. writeSafeNbt() из SafeNbtWriter    — пишем актуальное состояние в tag
    @Nullable private CompoundTag schematicBackup = null;
    private HolderLookup.Provider schematicBackupRegistries = null;

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider reg) {
        super.loadAdditional(tag, reg);

        // Тег содержит реальные данные если присутствует Inventory или FilterMask.
        boolean hasRealData = tag.contains("Inventory") || tag.contains("FilterMask");

        if (!hasRealData && schematicBackup != null) {
            // Пустой тег пришёл ПОСЛЕ загрузки реальных данных.
            // Create вызвал второй loadAdditional при деплое — восстанавливаем из резерва.
            CompoundTag backup = schematicBackup;
            HolderLookup.Provider backupReg = schematicBackupRegistries;
            if (backup.contains("Inventory")) inventory.deserializeNBT(backupReg, backup.getCompound("Inventory"));
            filterData.loadFromNBT(backup);
            // Позиционные данные не восстанавливаем — они должны пересчитываться заново.
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
        filterData.loadFromNBT(tag);
        subLevelCacheTimer = SUBLEVEL_CACHE_INTERVAL;

        if (hasRealData) {
            schematicBackup = tag.copy();
            schematicBackupRegistries = reg;
        }
        // Обратная совместимость: старый флаг HelpersSpawned игнорируем — блоки больше не спавним
    }

    private static float angleDiff(float target, float current) {
        float d = target - current;
        while (d >  180f) d -= 360f;
        while (d < -180f) d += 360f;
        return d;
    }
}