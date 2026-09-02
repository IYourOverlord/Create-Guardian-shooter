package com.yourname.cbcautotarget.blockentity;

import com.yourname.cbcautotarget.filter.TargetCategory;
import com.yourname.cbcautotarget.filter.TargetFilterData;
import com.yourname.cbcautotarget.CBCAutoTargetConfig;
import com.yourname.cbcautotarget.block.ControllerBlock;
import com.yourname.cbcautotarget.compat.SableCompat;
import com.yourname.cbcautotarget.menu.ControllerMenu;
import com.yourname.cbcautotarget.network.SyncWhitelistPacket;
import com.yourname.cbcautotarget.util.BallisticSolver;
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
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class ControllerBlockEntity extends BlockEntity implements MenuProvider {

    private static final java.util.concurrent.ConcurrentHashMap<Long, BlockPos> MOUNT_OWNER_REGISTRY =
            new java.util.concurrent.ConcurrentHashMap<>();

    public  static final double PITCH_TOLERANCE         = 1.0;
    private static final int    INVENTORY_SIZE           = 27;
    private static final int    TRANSFER_INTERVAL        = 10;
    private static final int    MIN_FIRE_COOLDOWN        = 20;
    private static final int    REQUIRED_ALIGNED_TICKS   = 3;
    private static final int    LOS_GRACE_TICKS_MAX      = 5;
    private static final int    SUBLEVEL_CACHE_INTERVAL  = 40;
    // LOS-проверка раз в N тиков вместо каждого тика — главное исправление лагов с Sable
    private static final int    LOS_CHECK_INTERVAL       = 10;

    // ── Yaw state (was YawBlockEntity) ───────────────────────────────────────
    private static final double YAW_DEADBAND_DEG    = 0.15;
    private static final float  YAW_MAX_DEG_PER_TICK = 9.6f;
    private float   targetYaw   = 0f;
    private boolean yawDirty    = false;

    // ── Pitch state (was PitchBlockEntity) ───────────────────────────────────
    private static final float PITCH_DEADBAND_DEG     = 0.1f;
    private static final float PITCH_MAX_DEG_PER_TICK = 9.6f;
    private float   targetPitch  = 0f;
    private boolean pitchDirty   = false;

    // ── Fire state (was FireBlockEntity) ─────────────────────────────────────
    private boolean fireRequested   = false;
    private boolean cancelRequested = false;

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

    /**
     * Устанавливается, когда контроллер получил команду "открыть огонь" от
     * другого контроллера с той же частотой (а не от собственного наведения).
     * Обрабатывается в tickFire() наравне с fireRequested, но не требует
     * попадания по своей цели — просто триггерит выстрел пушки.
     */
    private boolean broadcastFireRequested = false;

    // ── General state ─────────────────────────────────────────────────────────
    private boolean active         = false;
    private int     fireCooldown   = 0;

    @Nullable private BlockPos cannonMountPos     = null;
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
    private int alignedTicks           = 0;
    private int confirmTicks           = 0;
    private int losGraceTicks          = 0;
    private int losCheckCounter        = 0;

    // ── Ballistic aim cache ───────────────────────────────────────────────────
    // Кэш результата BallisticSolver: пересчитывается только когда позиция ствола
    // или цели сместилась более чем на порог, или истёк принудительный интервал.
    // Отдельные кэши для entity-цели и commander-цели — у них разная природа движения.

    /** Порог смещения ствола или цели (в блоках²), при котором кэш инвалидируется. */
    private static final double AIM_CACHE_POS_THRESHOLD_SQ  = 0.01; // ~0.1 блока
    /** Порог изменения относительной скорости цели (блоков/тик)², при котором кэш инвалидируется. */
    private static final double AIM_CACHE_VEL_THRESHOLD_SQ  = 0.001;
    /** Принудительный пересчёт раз в N тиков, даже если входные данные не изменились. */
    private static final int    AIM_CACHE_MAX_AGE            = 5;

    // Кэш для aimAndFireAtEntity
    @Nullable private double[] entityAimCache       = null;
    @Nullable private Vec3     entityAimCacheMuzzle = null;
    @Nullable private Vec3     entityAimCacheTarget = null;
    @Nullable private Vec3     entityAimCacheRelVel = null;
    private int                entityAimCacheAge    = 0;

    // Кэш для aimAndFireAtCommander
    // Командер не движется сам по себе, но может быть на корабле Sable — порог чуть мягче.
    private static final int    CMD_AIM_CACHE_MAX_AGE          = 10; // обновляем реже — цель статична
    private static final double CMD_AIM_CACHE_POS_THRESHOLD_SQ = 0.25; // ~0.5 блока (для движущегося корабля)
    @Nullable private double[] cmdAimCache       = null;
    @Nullable private Vec3     cmdAimCacheMuzzle = null;
    @Nullable private Vec3     cmdAimCacheTarget = null;
    private int                cmdAimCacheAge    = 0;

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

        if (++transferTickCounter >= TRANSFER_INTERVAL) {
            transferTickCounter = 0;
            if (cannonMountPos != null) tryTransferToCannon(level);
        }

        if (!active) return;

        if (cannonMountPos == null) findAndBindCannon(level, pos);

        // Получаем mount один раз — validateCannon возвращает его напрямую.
        // tickYaw, tickPitch, tickFire, aimAndFire используют этот же экземпляр.
        CannonMountBlockEntity mount = (cannonMountPos != null) ? validateCannon(level) : null;
        if (cannonMountPos != null && mount == null) { LOGGER.debug("[tick] validateCannon FAIL -> deactivate at {}", pos); setActive(false); return; }
        if (mount == null)                           { LOGGER.debug("[tick] cannonMountPos still null -> deactivate at {}", pos); setActive(false); return; }

        // Координаты контроллера и дула считаем один раз за тик: при наличии
        // controllerSubLevel каждый вызов getControllerWorldPos()/getMuzzleWorldPos()
        // выполняет матричное преобразование (SableCompat.toWorldPos), поэтому
        // не пересчитываем их повторно в tracking/scan/fire-LOS.
        Vec3 tickWorldCenter = getControllerWorldPos();
        Vec3 tickMuzzlePos   = getMuzzleWorldPos();

        // ── Inlined Yaw tick ─────────────────────────────────────────────────
        if (yawDirty) tickYaw(mount);

        // ── Inlined Pitch tick ───────────────────────────────────────────────
        if (pitchDirty) tickPitch(mount);

        // ── Inlined Fire tick ────────────────────────────────────────────────
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
            if (e != null && e.isAlive()) aimAndFireAtEntity(sl, e, mount, tickMuzzlePos);
        }

        if (commanderTargetPos != null && currentTargetUUID == null) {
            aimAndFireAtCommander(sl, mount, tickMuzzlePos);
        }
    }

    // ── Inlined Yaw logic ─────────────────────────────────────────────────────
    private void tickYaw(CannonMountBlockEntity mount) {
        if (mount.getContraption() == null) return;

        double currentYaw = wrap360(mount.getContraption().yaw);
        double desiredYaw = wrap360(targetYaw);
        double diff       = shortestYawDelta(currentYaw, desiredYaw);
        boolean snapped   = Math.abs(diff) <= YAW_DEADBAND_DEG;

        if (snapped) {
            mount.setYaw((float) desiredYaw);
            mount.notifyUpdate();
            yawDirty = false;
        } else {
            double step = Math.min(Math.abs(diff), YAW_MAX_DEG_PER_TICK) * Math.signum(diff);
            mount.setYaw((float) wrap360(currentYaw + step));
            mount.notifyUpdate();
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
    private void tickPitch(CannonMountBlockEntity mount) {
        if (mount.getContraption() == null) return;

        // ИСПРАВЛЕНО (корень проблемы с "телепанием"/зависанием при наведении):
        //
        // mount.getContraption().pitch — это "raw" pitch контрапшена. Чтобы
        // получить логический (world-space) pitch, его действительно нужно
        // умножить на sgn: currentWorldPitch = c.pitch * sgn. Это чтение —
        // верно и не менялось.
        //
        // НО mount.setPitch(float) — это НЕ обратная операция для чтения
        // c.pitch. В декомпилированном исходнике CBC (CannonMountBlockEntity,
        // версия 5.11.6/1.21.1) видно, что:
        //
        //   public void setPitch(float pitch) { this.cannonPitch = pitch; }
        //
        // То есть setPitch() просто напрямую записывает значение в cannonPitch
        // (уже логическую, мировую величину, которая клэмпится CBC пределами
        // -maximumDepression()..maximumElevation() БЕЗ участия sgn). Домножение
        // на raw происходит позже и автоматически, внутри applyRotation():
        //
        //   this.mountedContraption.pitch = this.cannonPitch * sgn;
        //
        // Прошлая версия этого метода домножала target на sgn ПЕРЕД вызовом
        // setPitch(), то есть применяла конвертацию дважды для пушек с
        // sgn = -1 (не совпадающих по горизонтальной ориентации контрапшена
        // с "нормальным" случаем). В результате CBC каждый тик получал
        // pitch с инвертированным знаком относительно реально желаемого,
        // тут же сам довинчивал это значение в противоположную сторону —
        // и на следующем тике наш diff снова оказывался "неправильным",
        // из-за чего пушка никогда не попадала в PITCH_DEADBAND_DEG и
        // непрерывно дёргалась, откатываясь к 0 (точке, где знак роли не
        // играет и обе стороны временно совпадают).
        //
        // Фикс: setPitch() вызывается с логическим (world-space) значением
        // напрямую, без повторного домножения на sgn.
        float sgn               = getContraptionSign(mount);
        float currentWorldPitch = mount.getContraption().pitch * sgn;   // raw → world (верно, не трогаем)
        float diff              = targetPitch - currentWorldPitch;
        boolean snapped         = Math.abs(diff) <= PITCH_DEADBAND_DEG;

        if (snapped) {
            mount.setPitch(targetPitch);   // setPitch ожидает world-space, НЕ raw
            mount.notifyUpdate();
            pitchDirty = false;
        } else {
            float step = Math.min(Math.abs(diff), PITCH_MAX_DEG_PER_TICK) * Math.signum(diff);
            mount.setPitch(currentWorldPitch + step);  // setPitch ожидает world-space, НЕ raw
            mount.notifyUpdate();
        }
    }

    // ── Inlined Fire logic ────────────────────────────────────────────────────
    private void tickFire(Level level, CannonMountBlockEntity mount) {
        ServerLevel sl = resolveServerLevel(level);
        if (sl == null) return;
        if (cannonMountPos == null) return;
        PitchOrientedContraptionEntity contraption = mount.getContraption();
        if (contraption == null) return;
        if (!(contraption.getContraption() instanceof AbstractMountedCannonContraption cannon)) return;

        if (cancelRequested) {
            cancelRequested = false;
            fireRequested   = false;
            cannon.onRedstoneUpdate(sl, contraption, false, 0, (ControlPitchContraption) mount);
            return;
        }
        if (!fireRequested && !broadcastFireRequested) return;
        fireRequested          = false;
        broadcastFireRequested = false;
        cannon.onRedstoneUpdate(sl, contraption, true, 15, (ControlPitchContraption) mount);
    }

    // ── Aim setters (replaced lookups into helper BEs) ────────────────────────
    private void setTargetYaw(float yaw) {
        this.targetYaw  = yaw;
        this.yawDirty   = true;
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
    private void applyAim(ServerLevel level, float wantedYaw, float wantedPitch) {
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
        alignedTicks         = 0;
        confirmTicks         = 0;
        losGraceTicks        = 0;
        doCancelFire();
        // Инвалидируем кэш баллистики — цель сменилась
        entityAimCache       = null;
        entityAimCacheMuzzle = null;
        entityAimCacheTarget = null;
        entityAimCacheRelVel = null;
        entityAimCacheAge    = 0;
    }

    // ── Scanning ──────────────────────────────────────────────────────────────
    private void scanForTarget(Level level, ServerLevel mainLevel, Level scanLevel, Vec3 worldCenter, Vec3 muzzle) {
        int  radius      = getScanRadius();
        UUID prevUUID    = currentTargetUUID;

        AABB worldBox = new AABB(
                worldCenter.x - radius, worldCenter.y - radius, worldCenter.z - radius,
                worldCenter.x + radius, worldCenter.y + radius, worldCenter.z + radius);

        List<Entity> candidates = new ArrayList<>(
                mainLevel.getEntitiesOfClass(LivingEntity.class, worldBox,
                        e -> e.isAlive() && filterData.isAllowed(e)
                                && !filterData.isNearAlly(e, mainLevel)));

        // Дополнительно ищем живые entity во всех sublevel-кораблях.
        // Сущности внутри sublevel'а находятся в его собственном Level и не видны
        // через обычный mainLevel.getEntitiesOfClass — точно та же проблема,
        // что и с командерами на кораблях (решена через findCommandersInAllSubLevels).
        // Для таких целей LOS через блоки не проверяем (см. currentTargetOnSubLevel).
        java.util.List<SableCompat.SubLevelEntityEntry<LivingEntity>> subLevelCandidates = new java.util.ArrayList<>();
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
            if (los) { chosen = candidate; break; }
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
        alignedTicks      = 0;
        losGraceTicks     = 0;
        doCancelFire();
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
    private void aimAndFireAtEntity(ServerLevel level, Entity target, CannonMountBlockEntity mount, Vec3 muzzleWorldPos) {
        PitchOrientedContraptionEntity c = mount.getContraption();
        if (c == null || !(c.getContraption() instanceof AbstractMountedCannonContraption)) return;
        ownContraptionUUID = c.getUUID();

        Vec3 muzzle    = computeRealMuzzlePos(c);
        // Если цель на sublevel-корабле, её position() — локальные координаты.
        // Конвертируем в мировые, переиспользуя worldPos из findLivingEntitiesInAllSubLevels.
        Vec3 rawTarget = target.position();
        if (currentTargetOnSubLevel && SableCompat.isAvailable()) {
            ServerLevel ml = mainLevel(level);
            if (ml != null) {
                for (var _entry : SableCompat.findLivingEntitiesInAllSubLevels(
                        ml, muzzle, getScanRadius() * 2, LivingEntity.class,
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
        Vec3 targetVel = target.getDeltaMovement();
        if (currentTargetOnSubLevel && SableCompat.isAvailable()) {
            ServerLevel ml = mainLevel(level);
            if (ml != null) {
                for (var _entry : SableCompat.findLivingEntitiesInAllSubLevels(
                        ml, muzzle, getScanRadius() * 2, LivingEntity.class,
                        _e -> _e.getUUID().equals(currentTargetUUID))) {
                    // Скорость корабля в мировых координатах + локальная скорость entity
                    Vec3 shipVel = SableCompat.getShipVelocity(_entry.subLevel());
                    Vec3 wVel    = SableCompat.toWorldVelocity(_entry.subLevel(), targetVel);
                    targetVel = wVel.add(shipVel);
                    break;
                }
            }
        }
        Vec3 relVel  = new Vec3(
                targetVel.x - platVel.x,
                targetVel.y - platVel.y,
                targetVel.z - platVel.z);

        // ── Ballistic cache ───────────────────────────────────────────────────
        // Пересчёт только если ствол или цель сместились, скорость изменилась,
        // или истёк принудительный интервал обновления.
        boolean needRecalc = entityAimCache == null
                || ++entityAimCacheAge >= AIM_CACHE_MAX_AGE
                || muzzle.distanceToSqr(entityAimCacheMuzzle) > AIM_CACHE_POS_THRESHOLD_SQ
                || targetPos.distanceToSqr(entityAimCacheTarget) > AIM_CACHE_POS_THRESHOLD_SQ
                || relVel.subtract(entityAimCacheRelVel).lengthSqr() > AIM_CACHE_VEL_THRESHOLD_SQ;

        if (needRecalc) {
            // Use world-space pitch limits: for inverted cannons (sgn=-1) depression
            // and elevation are physically swapped relative to world space.
            float sgnE = getContraptionSign(mount);
            entityAimCache       = BallisticSolver.solve(muzzle, targetPos, relVel,
                    CBCAutoTargetConfig.MUZZLE_SPEED_BLOCKS_PER_TICK.get(),
                    CBCAutoTargetConfig.DEFAULT_GRAVITY.get(),
                    CBCAutoTargetConfig.DEFAULT_DRAG.get(),
                    false, worldMaxDepression(c, sgnE), worldMaxElevation(c, sgnE));
            entityAimCacheMuzzle = muzzle;
            entityAimCacheTarget = targetPos;
            entityAimCacheRelVel = relVel;
            entityAimCacheAge    = 0;
            LOGGER.debug("[AimCache] entity recalc at {}", worldPosition);
        }
        double[] aim = entityAimCache;
        // ─────────────────────────────────────────────────────────────────────

        float[] local       = ShipAimSolver.toLocalAim(aim[0], aim[1], controllerSubLevel);
        float   wantedYaw   = local[0];
        float   wantedPitch = local[1];

        applyAim(level, wantedYaw, wantedPitch);

        float   sgn          = getContraptionSign(mount);
        float   currentPitch = c.pitch * sgn;
        // Заблокированная ось (allowHorizontal/allowVertical = false) физически
        // не может довернуться до wantedYaw/wantedPitch, поэтому сравнивать
        // "желаемый" угол с фактическим для неё бессмысленно — она никогда не
        // станет "ok" и просто заблокирует стрельбу навсегда. Для заблокированной
        // оси условие готовности считается выполненным автоматически: стреляем
        // с тем углом, который уже есть.
        boolean yawOk   = !allowHorizontal
                || Math.abs(angleDiff(wantedYaw, c.yaw)) < BallisticSolver.YAW_TOLERANCE;
        boolean pitchOk = !allowVertical
                || Math.abs(wantedPitch - currentPitch) < BallisticSolver.PITCH_TOLERANCE;

        if (fireCooldown > 0) fireCooldown--;
        alignedTicks = (yawOk && pitchOk) ? alignedTicks + 1 : 0;

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
                currentTargetUUID = null; currentTargetOnSubLevel = false; alignedTicks = 0; return;
            }
            requestFire(level);
        }
    }

    private static final Logger LOGGER_AIM_CMD = LoggerFactory.getLogger("cbc_autotarget/AimAtCommander");

    private void aimAndFireAtCommander(ServerLevel level, CannonMountBlockEntity mount, Vec3 muzzleWorldPos) {
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

        PitchOrientedContraptionEntity c = mount.getContraption();
        if (c == null || !(c.getContraption() instanceof AbstractMountedCannonContraption)) return;
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
                || muzzle.distanceToSqr(cmdAimCacheMuzzle) > CMD_AIM_CACHE_POS_THRESHOLD_SQ
                || targetPos.distanceToSqr(cmdAimCacheTarget) > CMD_AIM_CACHE_POS_THRESHOLD_SQ;

        if (needRecalc) {
            // Same world-space limit correction for commander targets.
            float sgnC = getContraptionSign(mount);
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

        applyAim(level, wantedYaw, wantedPitch);

        float   sgn          = getContraptionSign(mount);
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

    // ── Cannon helpers ────────────────────────────────────────────────────────
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
    private static float getContraptionSign(CannonMountBlockEntity mount) {
        Direction d = mount.getContraptionDirection();
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

    /**
     * Returns the bound CannonMountBlockEntity using the current level, or null if unavailable.
     * Used to retrieve sgn for muzzle position calculations without a level parameter.
     */
    @Nullable
    private CannonMountBlockEntity findMountBlockEntity() {
        if (cannonMountPos == null || level == null) return null;
        if (level.getBlockEntity(cannonMountPos) instanceof CannonMountBlockEntity m) return m;
        if (controllerSubLevel != null) {
            var acc = controllerSubLevel.getPlot().getEmbeddedLevelAccessor();
            if (acc.getBlockEntity(cannonMountPos) instanceof CannonMountBlockEntity m) return m;
        }
        return null;
    }

    private Vec3 getMuzzleWorldPos() {
        if (cannonMountPos == null) return getControllerWorldPos();
        // For inverted cannons (sgn=-1) the muzzle faces downward, so the approximate
        // scan origin should be below the mount, not above it.
        CannonMountBlockEntity mount = findMountBlockEntity();
        float sgn = (mount != null) ? getContraptionSign(mount) : 1.0f;
        Vec3 local = Vec3.atCenterOf(cannonMountPos).add(0, sgn * 1.0, 0);
        return (controllerSubLevel != null) ? SableCompat.toWorldPos(controllerSubLevel, local) : local;
    }

    private Vec3 computeRealMuzzlePos(PitchOrientedContraptionEntity c) {
        Vec3 base = c.position();
        if (controllerSubLevel != null) base = SableCompat.toWorldPos(controllerSubLevel, base);
        double len = CBCAutoTargetConfig.BARREL_LENGTH.get();
        if (len <= 0.0) return base;
        // c.pitch is raw (CBC internal). For inverted cannons (sgn=-1) the physical
        // barrel direction is opposite to raw pitch, so we must use worldPitch = raw * sgn.
        CannonMountBlockEntity mount = findMountBlockEntity();
        float sgn = (mount != null) ? getContraptionSign(mount) : 1.0f;
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

    private void findAndBindCannon(Level level, BlockPos pos) {
        // BUG FIX: The original code returned immediately after finding the first
        // CannonMount — even if that mount was already claimed by another controller.
        // In stacked cannon setups (e.g. [Controller]—[Mount A (inverted)]—[Mount B (normal)]—[Controller])
        // the upper controller found Mount B below first, tryClaimMount failed (B owned by
        // lower controller), and the method returned WITHOUT ever checking Mount A above.
        // Result: the upper controller stayed unbound and the inverted cannon was never aimed.
        //
        // Fix: collect ALL adjacent free mounts first, then bind to the best one.
        // Priority: prefer the mount that already has an active contraption loaded,
        // then prefer above() over below() so that a controller sitting between two
        // stacked mounts naturally binds upward to the inverted cannon above it.

        BlockPos bestCandidate = null;
        boolean  bestHasContraption = false;

        for (BlockPos candidate : new BlockPos[]{ pos.above(), pos.below() }) {
            BlockEntity be = level.getBlockEntity(candidate);
            LOGGER.debug("[findAndBind] checking {} -> {}", candidate,
                    be == null ? "null" : be.getClass().getSimpleName());
            if (!(be instanceof CannonMountBlockEntity mount)) continue;

            if (!tryClaimMount(candidate, pos)) {
                LOGGER.debug("[findAndBind] mount {} claimed by another controller", candidate);
                continue;   // skip claimed mounts, keep searching
            }

            boolean hasContraption = mount.getContraption() != null;
            if (bestCandidate == null || (!bestHasContraption && hasContraption)) {
                // Release any previously tentatively-claimed candidate before switching
                if (bestCandidate != null) releaseMount(bestCandidate);
                bestCandidate       = candidate;
                bestHasContraption  = hasContraption;
            } else {
                // This candidate is worse – release the claim we just made
                releaseMount(candidate);
            }
        }

        if (bestCandidate != null) {
            cannonMountPos = bestCandidate;
            setChanged();
            LOGGER.debug("[findAndBind] bound cannon at {}", bestCandidate);
        } else {
            LOGGER.debug("[findAndBind] no free CannonMount found above/below {}", pos);
        }
    }

    private boolean tryClaimMount(BlockPos mount, BlockPos ctrl) {
        BlockPos ex = MOUNT_OWNER_REGISTRY.putIfAbsent(mount.asLong(), ctrl);
        return ex == null || ex.equals(ctrl);
    }

    private void releaseMount(BlockPos mount) {
        if (mount != null) MOUNT_OWNER_REGISTRY.remove(mount.asLong(), worldPosition);
    }

    /**
     * Валидирует пушку и возвращает mount если найден, null если нет.
     * Заменяет старую boolean-версию — позволяет переиспользовать mount в tick()
     * без повторного getBlockEntity.
     */
    @Nullable
    private CannonMountBlockEntity validateCannon(Level level) {
        if (cannonMountPos == null) { LOGGER.debug("[validate] cannonMountPos=null"); return null; }
        if (level.getBlockEntity(cannonMountPos) instanceof CannonMountBlockEntity m) {
            PitchOrientedContraptionEntity c = m.getContraption();
            if (c != null) ownContraptionUUID = c.getUUID();
            LOGGER.debug("[validate] OK via level at {}", cannonMountPos);
            return m;
        }
        // Если SubLevel-кэш устарел — обновляем перед поиском.
        // Это критично при первой валидации после активации через Commander,
        // когда subLevelCacheTimer ещё не успел обнулиться.
        if (controllerSubLevel == null && SableCompat.isAvailable() && level instanceof ServerLevel sl) {
            controllerSubLevel = SableCompat.getSubLevelForBlock(sl, worldPosition);
            subLevelCacheTimer = 0;
            LOGGER.debug("[validate] lazy-refreshed controllerSubLevel={} at {}",
                    controllerSubLevel == null ? "null" : "present", worldPosition);
        }
        if (controllerSubLevel != null) {
            var acc = controllerSubLevel.getPlot().getEmbeddedLevelAccessor();
            if (acc.getBlockEntity(cannonMountPos) instanceof CannonMountBlockEntity m) {
                PitchOrientedContraptionEntity c = m.getContraption();
                if (c != null) ownContraptionUUID = c.getUUID();
                LOGGER.debug("[validate] OK via SubLevel accessor at {}", cannonMountPos);
                return m;
            }
            LOGGER.debug("[validate] FAIL: not found via level or SubLevel accessor at {}", cannonMountPos);
            return null;
        }
        LOGGER.debug("[validate] FAIL: not found via level, no SubLevel at {}", cannonMountPos);
        return null;
    }

    @Nullable
    public CannonMountBlockEntity getMount(Level level) {
        if (cannonMountPos == null) return null;
        if (level.getBlockEntity(cannonMountPos) instanceof CannonMountBlockEntity m) return m;
        if (controllerSubLevel != null) {
            var acc = controllerSubLevel.getPlot().getEmbeddedLevelAccessor();
            if (acc.getBlockEntity(cannonMountPos) instanceof CannonMountBlockEntity m) return m;
        }
        return null;
    }

    private void tryTransferToCannon(Level level) {
        if (cannonMountPos == null) return;
        for (Direction dir : Direction.values()) {
            IItemHandler h = level.getCapability(Capabilities.ItemHandler.BLOCK, cannonMountPos, dir);
            if (h == null) continue;
            for (int ms = 0; ms < inventory.getSlots(); ms++) {
                ItemStack stack = inventory.getStackInSlot(ms);
                if (stack.isEmpty()) continue;
                for (int cs = 0; cs < h.getSlots(); cs++) {
                    ItemStack rem = h.insertItem(cs, stack.copy(), false);
                    if (rem.getCount() < stack.getCount()) {
                        inventory.setStackInSlot(ms, rem);
                        setChanged();
                        break;
                    }
                }
            }
        }
    }

    // ── Activation ────────────────────────────────────────────────────────────
    public boolean isActive() { return active; }

    public void setActive(boolean newActive) {
        if (active == newActive) return;
        LOGGER.debug("[setActive] {} -> {} at {} (level={})", active, newActive, worldPosition,
                level == null ? "null" : level.getClass().getSimpleName());
        active = newActive;
        if (level == null || level.isClientSide) return;
        // ContraptionLevel не поддерживает setBlock — пропускаем
        if (level instanceof ServerLevel) {
            level.setBlock(worldPosition, getBlockState().setValue(ControllerBlock.ACTIVE, active), 3);
            // Синхронизируем BE-данные (cannonMountPos, active) с клиентом
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }

        if (!active) {
            releaseMount(cannonMountPos);
            doCancelFire();
            broadcastFireRequested = false;
            ownerCommanderUUID = null; // Освобождаем привязку к командеру
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
        }
        currentTargetUUID       = null;
        currentTargetOnSubLevel = false;
        commanderTargetPos = null;
        alignedTicks  = 0;
        confirmTicks  = 0;
        losGraceTicks = 0;
        yawDirty   = false;
        pitchDirty = false;

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

    public void onPlaced()  { if (level != null) findAndBindCannon(level, worldPosition); }
    public void onRemoved() { releaseMount(cannonMountPos); }

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
    @Nullable public BlockPos getCannonMountPos() { return cannonMountPos; }
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
                LOGGER.debug("[applyFromCommander] IGNORED activate from {} (owner={}), already owned at {}",
                        srcCommanderUUID, ownerCommanderUUID, worldPosition);
                return;
            }
            ownerCommanderUUID = srcCommanderUUID;
            filterData.setMask(cf.getMask());
            filterData.setWhitelistEnabled(cf.isWhitelistEnabled());
            filterData.replaceWhitelist(new ArrayList<>(cf.getWhitelist()));
            this.commanderPos = srcCommanderPos;
            LOGGER.debug("[applyFromCommander] activate={} active={} cannonMountPos={} owner={} at {}",
                    activate, active, cannonMountPos, ownerCommanderUUID, worldPosition);
            // Принудительно обновляем SubLevel-кэш перед активацией, так как
            // блок мог быть пересоздан Sable (hotswap) или только что размещён.
            if (SableCompat.isAvailable() && level instanceof ServerLevel sl) {
                controllerSubLevel = SableCompat.getSubLevelForBlock(sl, worldPosition);
                subLevelCacheTimer = 0;
                LOGGER.debug("[applyFromCommander] refreshed controllerSubLevel={} at {}",
                        controllerSubLevel == null ? "null" : "present", worldPosition);
            }
            // Если cannonMountPos невалиден в текущем SubLevel — пересканируем.
            if (validateCannon(level) == null) {
                LOGGER.debug("[applyFromCommander] cannonMountPos invalid, rebinding cannon at {}", worldPosition);
                cannonMountPos = null;
                if (level != null) findAndBindCannon(level, worldPosition);
            }
            if (!active) setActive(true);
        } else {
            // Деактивация: принимаем только от того командера, который активировал.
            if (ownerCommanderUUID != null && !ownerCommanderUUID.equals(srcCommanderUUID)) {
                LOGGER.debug("[applyFromCommander] IGNORED deactivate from {} (owner={}), not our commander at {}",
                        srcCommanderUUID, ownerCommanderUUID, worldPosition);
                return;
            }
            filterData.setMask(cf.getMask());
            filterData.setWhitelistEnabled(cf.isWhitelistEnabled());
            filterData.replaceWhitelist(new ArrayList<>(cf.getWhitelist()));
            this.commanderPos = srcCommanderPos;
            ownerCommanderUUID = null; // Освобождаем контроллер
            LOGGER.debug("[applyFromCommander] deactivate accepted from {} at {}",
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
        if (cannonMountPos != null) tag.putLong("CannonMountPos", cannonMountPos.asLong());
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
        if (cannonMountPos      != null) tag.putLong("CannonMountPos",     cannonMountPos.asLong());
        if (currentTargetUUID   != null) tag.putUUID("CurrentTargetUUID",  currentTargetUUID);
        if (commanderPos        != null) tag.putLong("CommanderPos",        commanderPos.asLong());
        if (commanderTargetPos  != null) tag.putLong("CommanderTargetPos",  commanderTargetPos.asLong());
        if (ownerCommanderUUID  != null) tag.putUUID("OwnerCommanderUUID",  ownerCommanderUUID);
        tag.putInt("FireCooldown", fireCooldown);
        tag.putFloat("TargetYaw",   targetYaw);
        tag.putFloat("TargetPitch", targetPitch);
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
        cannonMountPos     = tag.contains("CannonMountPos")    ? BlockPos.of(tag.getLong("CannonMountPos"))    : null;
        currentTargetUUID  = tag.hasUUID("CurrentTargetUUID")  ? tag.getUUID("CurrentTargetUUID")             : null;
        commanderPos       = tag.contains("CommanderPos")      ? BlockPos.of(tag.getLong("CommanderPos"))      : null;
        commanderTargetPos = tag.contains("CommanderTargetPos")? BlockPos.of(tag.getLong("CommanderTargetPos")): null;
        ownerCommanderUUID = tag.hasUUID("OwnerCommanderUUID") ? tag.getUUID("OwnerCommanderUUID")            : null;
        fireCooldown       = tag.getInt("FireCooldown");
        targetYaw          = tag.getFloat("TargetYaw");
        targetPitch        = tag.getFloat("TargetPitch");
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