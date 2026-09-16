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
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
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
import org.joml.Quaterniond;
import org.joml.Vector3d;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import javax.annotation.Nullable;

public class MachineSoulBlockEntity extends BlockEntity implements MenuProvider, BlockEntitySubLevelActor {

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
    public enum Tab { VISION, MOVEMENT, ACTION, TARGET, NPC }

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
     * Гироскопическая стабилизация крена (roll) и тангажа (pitch) для
     * физических конструкций Sable — см. {@link #sable$physicsTick}.
     * Держит корабль горизонтально (roll=0, pitch=0), yaw не ограничивается.
     * Включена по умолчанию, чтобы поведение существующих блоков не
     * изменилось. Управляется отдельной кнопкой на вкладке NPC.
     */
    private boolean gyroStabilizationActive = true;

    /**
     * Флаг блокировки редактирования блока («The NPC»).
     * Пока true — все пакеты, изменяющие состояние этого конкретного блока,
     * отклоняются для игроков не в GameType.CREATIVE.
     * Игроки в Creative имеют полный доступ, включая переключение флага обратно.
     * По умолчанию false (не заблокирован).
     */
    private boolean creativeLocked = false;

    // Рантайм-состояние PID стабилизатора (не сохраняется в NBT — накопитель
    // и резервная ось безопасно сбрасываются при перезагрузке чанка/сервера).
    private double gyroIntegralTilt = 0.0;
    // Троттлинг диагностического лога — не спамит на каждый физ-субтик.
    private long gyroLastLogMs = 0L;

    // ── Emergency lockdown (аварийная блокировка) ───────────────────────────
    // Предыдущие значения angVelTilt/yawRate — нужны, чтобы обнаружить
    // АНОМАЛЬНЫЙ СКАЧОК за один тик (внешняя гироскопическая прецессия,
    // столкновение, взаимодействие с другими кораблями и т.п.), а не просто
    // превышение абсолютного порога, который срабатывает уже постфактум.
    private double gyroPrevAngVelTilt = 0.0;
    private double gyroPrevYawRate = 0.0;
    private boolean gyroPrevValid = false;
    // Счётчик оставшихся тиков аварийной блокировки. Пока > 0, стабилизатор
    // игнорирует обычный PID и жёстко гасит ВСЮ angVel (включая yaw) прямым
    // импульсом — это единственный режим, где yaw трогается намеренно,
    // потому что при таком скачке нельзя доверять, что оставшееся вращение
    // "просто поворот" — оно уже привело к неконтролируемой раскрутке.
    private int gyroLockdownTicksLeft = 0;

    // ── Резонансный срыв (LOCKDOWN срабатывает повторно без стабильного окна) ──
    // Проблема из логов: при быстром вращении по yaw гироскопическая прецессия
    // (yaw→tilt через недиагональные члены тензора инерции) постоянно возвращает
    // |angVelTilt| к скачку сразу после выхода из LOCKDOWN — PID/breakaway снова
    // толкает конструкцию, снова срабатывает LOCKDOWN, и так десятками раз подряд
    // (в логах — почти 2 минуты подряд идущих LOCKDOWN/UNSTICK на pos y=990).
    // Это НЕ единичный внешний удар (для него LOCKDOWN и создан), а устойчивый
    // резонанс, который обычная блокировка на GYRO_LOCKDOWN_TICKS не лечит —
    // требуется отдельный аварийный выключатель на случай, когда сам LOCKDOWN
    // повторяется без периода спокойной работы между срабатываниями.
    private int gyroConsecutiveLockdowns = 0;
    private int gyroLockdownFreeStreakTicks = 0;
    private int gyroEmergencyOffTicksLeft = 0;
    private static final int GYRO_LOCKDOWN_STABLE_WINDOW_TICKS = 40; // ~2с без нового LOCKDOWN — предыдущий срыв считается погашенным, счётчик подряд сбрасывается
    private static final int GYRO_CONSECUTIVE_LOCKDOWNS_THRESHOLD = 4; // столько LOCKDOWN подряд без стабильного окна — явный резонанс, а не единичный удар
    private static final int GYRO_EMERGENCY_OFF_TICKS = 100; // ~5с полного отключения стабилизатора — даёт конструкции долежать/упасть в любом положении и погасить резонанс естественным демпфированием мира

    // ── Состояние breakaway (продавливание застревания) ─────────────────────
    private double gyroPrevTiltError = 0.0;
    private boolean gyroPrevTiltErrorValid = false;
    // ── Фильтр дребезга подвески (low-pass на входе PID) ─────────────────────
    // Мягкая подвеска колёс (Sable suspension joints) даёт конструкции
    // постоянный высокочастотный микро-крен на месте (tilt колеблется в
    // районе 0.01-0.05 рад тик от тика туда-обратно, само по себе безобидно).
    // PID реагирует на КАЖДЫЙ такой всплеск как на реальное отклонение и
    // выдаёт restoring-импульс — этот импульс добавляет энергию в систему
    // пружина+корпус, подвеска отвечает бОльшим колебанием, PID снова толкает
    // сильнее — положительная обратная связь по резонансной частоте
    // подвески, визуально выглядящая как нарастающая раскачка вплоть до
    // переворота. gyroFilteredTiltAxis/gyroFilteredTiltSin/gyroFilteredTiltCos
    // хранят экспоненциально сглаженный (EMA) единичный вектор currentUp —
    // фильтруется САМ вектор ориентации (а не скалярный угол), чтобы
    // избежать сложения углов вокруг непараллельных осей на разных тиках.
    // Это классический частотный разделитель: постоянная времени фильтра
    // пропускает медленное реальное опрокидывание почти без задержки, но
    // усредняет и гасит быстрые знакопеременные колебания дребезга подвески,
    // не требуя знания конкретной частоты/жёсткости пружин — адаптивность
    // достигается самой природой EMA (чем быстрее и чем более знакопеременны
    // отклонения, тем сильнее они гасятся усреднением). Используется ТОЛЬКО
    // для PID/deadband — сырой currentUp/tiltError по-прежнему идёт в
    // LOCKDOWN/jumpTilt/nearVertical, чтобы не терять чувствительность к
    // реальным резким ударам/столкновениям.
    private final Vector3d gyroFilteredUp = new Vector3d(0.0, 1.0, 0.0);
    private boolean gyroFilteredUpValid = false;
    // Постоянная времени сглаживания в секундах: за это время фильтр
    // "нагоняет" ~63% (1-1/e) реального отклонения. Подобрана так, чтобы
    // перекрывать типичный период колебаний мягкой подвески (несколько
    // тиков туда-обратно), но не создавать заметной задержки реакции на
    // настоящий устойчивый крен (пилот наклонил корабль намеренно, реальное
    // опрокидывание) — такой крен нарастает МЕДЛЕННО относительно
    // постоянной времени и фильтр его пропускает почти без искажения.
    private static final double GYRO_TILT_FILTER_TIME_CONSTANT = 0.25; // ~5 тиков при 20 тик/сек
    // Deadband с гистерезисом на ОТФИЛЬТРОВАННЫЙ tiltError: ниже входного
    // порога PID считает конструкцию выровненной и НЕ выдаёт restoring-часть
    // вообще (интеграл тоже не копится) — устраняет остаточный тычок даже от
    // сглаженного, но ненулевого шума подвески. Выходной порог гистерезиса
    // ниже входного (classic Schmitt trigger), чтобы deadband не "мигал"
    // включённым/выключенным на каждый тик при tiltError, колеблющемся
    // ровно возле границы — иначе сам deadband стал бы источником дребезга.
    private static final double GYRO_TILT_DEADBAND_ENTER = 0.035; // рад (~2°) — выше этого PID точно активен
    private static final double GYRO_TILT_DEADBAND_EXIT = 0.02;   // рад (~1.15°) — ниже этого PID точно молчит
    private boolean gyroDeadbandActive = false;
    private int gyroStuckTicks = 0;
    private int gyroUnstickCooldownLeft = 0;
    // Slew-rate ограничение восстанавливающего (П+И) импульса: предыдущее
    // применённое значение tiltRestoring нужно, чтобы за один тик оно не
    // могло измениться больше чем на GYRO_RESTORING_SLEW_PER_TICK. Без этого
    // при апериодическом (критически демпфированном) режиме restoring-часть
    // всё равно способна резко "дёрнуть" систему рывком на переходе через
    // deadband/nearVertical или при скачке filteredTiltError, сама создавая
    // крен, который потом приходится гасить демпфером — что и проявлялось
    // как раскачка. Сбрасывается в 0, когда restoring не выдаётся вообще.
    private double gyroPrevRestoring = 0.0;

    // ── Кэш эффективной инерции вдоль оси наклона (оптимизация) ─────────────
    // n·invI·n дорого считать каждый физ-субтик (matrix transform + dot).
    // tiltAxis обычно меняется плавно между соседними тиками при штатной
    // работе стабилизатора, поэтому переиспользуем последнее значение s,
    // пока ось не отклонилась заметно и не истёк лимит тиков кэша — экономит
    // одно matrix-умножение на большинстве тиков без потери точности сверх
    // допустимой (масса/распределение корабля тоже меняются медленно
    // относительно частоты физтика).
    private Vector3d gyroCachedAxis = null;
    private double gyroCachedS = Double.NaN;
    private int gyroCacheTicksLeft = 0;
    private static final double GYRO_CACHE_AXIS_COS_THRESHOLD = 0.999; // ~2.5° отклонения — пересчитать
    private static final int GYRO_CACHE_MAX_TICKS = 10; // принудительный пересчёт не реже раза в 10 тиков


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

    // ── Гироскопическая стабилизация (Sable) ─────────────────────────────────

    public boolean isGyroStabilizationActive() { return gyroStabilizationActive; }

    public void setGyroStabilizationActive(boolean active) {
        this.gyroStabilizationActive = active;
        setChanged();
        LOGGER.info("[MachineSoul] setGyroStabilizationActive pos={} -> {}", worldPosition, active);
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public boolean isCreativeLocked() { return creativeLocked; }

    public void setCreativeLocked(boolean locked) {
        this.creativeLocked = locked;
        setChanged();
        LOGGER.info("[MachineSoul] setCreativeLocked pos={} -> {}", worldPosition, locked);
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

    // ── Gyroscope (единая связная стабилизация наклона, yaw исключён) ─────────
    // Жёстко зашитые коэффициенты критического демпфирования для ЕДИНОЙ оси
    // коррекции наклона (tiltAxis = cross(currentUp, worldUp)). В отличие от
    // прежней версии с двумя независимыми PID (roll вокруг forward, pitch
    // вокруг shipRight), здесь ошибка наклона — один связный вектор: корабль
    // либо наклонён (в любом сочетании крена/тангажа), либо нет. Это устраняет
    // "борьбу" двух контуров через недиагональные члены тензора инерции,
    // которая и была источником нестабильности/раскачки в старой реализации.
    // Снижено с 6.0/3.5 до 2.3/4.2 (Вариант 1: критическое демпфирование) —
    // при KP=6.0 и недостаточно доминирующем демпфере система была
    // колебательной (недодемпфированной): восстанавливающий момент разгонял
    // angVelTilt быстрее, чем демпфер успевал её погасить ДО прохождения
    // вертикали, что вызывало перелёт (overshoot) на противоположный борт и
    // циклическую раскачку. Отношение KD/KP теперь ~1.8 (было ~0.58) —
    // система стремится к апериодическому режиму без перелёта через 0.
    private static final double GYRO_KP = 1.4;   // П-составляющая (по углу наклона, атан2)
    private static final double GYRO_KI = 0.8;   // И-составляющая (компенсация постоянного возмущающего момента)
    private static final double GYRO_KD = 4.2;   // Д-составляющая (гашение угловой скорости по оси коррекции наклона)
    // Anti-windup: жёсткий потолок накопителя интеграла (общий для roll/pitch).
    // Поднят с 0.5 до 4.0: при статическом контакте корабля с поверхностью
    // (например, корабль осел и упирается носом/углом в землю) реактивный
    // момент опоры полностью гасит любой недостаточно большой восстанавливающий
    // импульс — гироскоп застревал в устойчивом ложном равновесии (см. логи:
    // pitch зависал на -0.4635 десятки секунд). Интеграл должен иметь запас,
    // чтобы "продавить" такое статическое сопротивление, как integral
    // windup/breakaway в классических ПИД-регуляторах приводов.
    private static final double GYRO_MAX_INTEGRAL = 4.0;
    // Физический предохранитель на ВОССТАНАВЛИВАЮЩУЮ (П+И) часть импульса —
    // ограничивает только "толкающую к цели" составляющую, чтобы стабилизатор
    // не мог разово вкачать в корабль нефизично большой момент. Демпфер (Д)
    // в этот потолок НЕ упирается (см. ниже) — иначе система может разогнать
    // angVel выше того, что демпфер способен погасить за тик, что и вызывало
    // срыв в раскрутку на больших углах/скоростях.
    // Поднят с 0.5, снижен обратно до 1.5 при добавлении rate-limit'а на
    // dOmega/тик и yaw-детюнинга (GYRO_MAX_DELTA_OMEGA_PER_TICK, yawGain) —
    // restoring-часть больше не единственная защита от разгона, поэтому она
    // может быть умеренной и не должна сама провоцировать резонанс с
    // гироскопической прецессией на высоких |yawRate|.
    private static final double GYRO_MAX_RESTORING_DELTA_OMEGA = 1.5;
    // Жёсткий предохранитель по самой угловой скорости вдоль оси коррекции:
    // если |angVel| превышает этот порог, демпфер обязан погасить её
    // ПОЛНОСТЬЮ за один тик (не клипуется потолком выше), чтобы разгон
    // никогда не мог обогнать способность стабилизатора его гасить.
    // Снижен с 5.0 до 2.0: в логах разгон происходил ЗА ОДИН тик (0.04→3.68
    // рад/с), поэтому чем раньше срабатывает HARD BRAKE, тем меньше энергии
    // успевает накопиться до жёсткого гашения.
    private static final double GYRO_MAX_ANGVEL_BEFORE_HARD_BRAKE = 2.0;
    private static final double GYRO_EPS = 1e-6;
    // Rate-limit на САМ impulseScalar (dOmegaTilt/s), а не только на итоговую
    // |angVel| после применения. Ловит взрывной разгон дифференциальной
    // прецессии: при быстром вращении по yaw в присутствии малого наклона
    // возникает гироскопический момент через недиагональные члены тензора
    // инерции (ω×Iω), который стабилизатор не видит заранее — он проявляется
    // как внезапный скачок |angVelTilt| ЗА ОДИН ТИК (в логах: с -0.04 до -3.68
    // рад/с), т.е. до того как currentMag успевает превысить
    // GYRO_MAX_ANGVEL_BEFORE_HARD_BRAKE и включить HARD BRAKE. Ограничиваем
    // прирост |angVelTilt|, который стабилизатор готов внести за один тик,
    // независимо от того, что насчитал PID/демпфер по формуле dOmega/s.
    // Снижено с 0.5 до 0.12 (Вариант 1), а затем сделано ПРОПОРЦИОНАЛЬНЫМ
    // |tiltError| вместо фиксированного значения (см. computeMaxDeltaOmegaForTilt
    // ниже) — фиксированный потолок 0.12 одновременно:
    //  (а) был слишком резким для малых углов (доли градуса крена уже давали
    //      скачок скорости 0.12 рад/с — воспринимается как "рывок"), и
    //  (б) был слишком мал для больших углов/переворота (tilt≈2.05 рад
    //      застревал НАВСЕГДА: dOmegaTilt=0.12 упирался в потолок и не мог
    //      продавить сопротивление опоры/демпфера — см. логи, где корабль
    //      вверх ногами держал tilt=2.05..2.07 сотни тиков подряд).
    // GYRO_MIN_DELTA_OMEGA_PER_TICK — минимальный потолок при малом крене
    // (плавное, почти незаметное парирование), GYRO_MAX_DELTA_OMEGA_PER_TICK
    // — потолок при полном перевороте (tiltError → π), между ними — плавная
    // линейная интерполяция по |tiltError|/π.
    private static final double GYRO_MIN_DELTA_OMEGA_PER_TICK = 0.035;
    private static final double GYRO_MAX_DELTA_OMEGA_PER_TICK = 0.35;
    // Отдельный, более узкий slew-rate ТОЛЬКО на restoring-часть (П+И) —
    // ограничивает не абсолютную величину, а её ПРИРАЩЕНИЕ между соседними
    // тиками (см. gyroPrevRestoring), чтобы стабилизатор сам не мог вносить
    // рывок при резком изменении filteredTiltError/deadband-состояния.
    // Демпфер (Д) под этот лимит не подпадает — он обязан реагировать на
    // угловую скорость немедленно (см. комментарий у tiltDamping).
    private static final double GYRO_RESTORING_SLEW_PER_TICK = 0.06;

    /**
     * Адаптивный потолок |dOmegaTilt| за тик, пропорциональный величине
     * ошибки наклона: малый крен парируется медленно и плавно, полный
     * переворот (tiltError → π) — быстрее, чтобы не застревать навечно
     * (см. комментарий у GYRO_MIN/MAX_DELTA_OMEGA_PER_TICK). Линейная
     * интерполяция без разрыва производной не нужна — резкий рывок здесь
     * не критичен, так как это ПОТОЛОК скорости изменения, а не сама
     * скорость: skew ограничивается отдельно GYRO_RESTORING_SLEW_PER_TICK.
     */
    private static double computeMaxDeltaOmegaForTilt(double absTiltErrorRad) {
        double t = Math.min(1.0, absTiltErrorRad / Math.PI);
        return GYRO_MIN_DELTA_OMEGA_PER_TICK + (GYRO_MAX_DELTA_OMEGA_PER_TICK - GYRO_MIN_DELTA_OMEGA_PER_TICK) * t;
    }
    // ── Аварийная блокировка (emergency lockdown) ───────────────────────────
    // Порог скачка |angVelTilt| или |yawRate| ЗА ОДИН ТИК относительно
    // предыдущего значения — независимо от абсолютной величины. Ловит именно
    // РЕЗКОЕ приращение (внешняя прецессия/столкновение), которое обычный
    // HARD BRAKE по абсолютному |angVel| видит только постфактум, когда
    // скорость уже большая. В логах скачок был ~3.6 рад/с за тик.
    private static final double GYRO_LOCKDOWN_JUMP_THRESHOLD = 1.0;
    // Во сколько раз расширяется GYRO_LOCKDOWN_JUMP_THRESHOLD при tiltError,
    // приближающемся к π (полный переворот) — см. комментарий у места
    // использования в блоке обнаружения аномального скачка.
    private static final double GYRO_LOCKDOWN_JUMP_THRESHOLD_MULTIPLIER = 4.0;
    // Число тиков полного гашения после срабатывания — даёт системе время
    // "остыть" вместо немедленного возврата к PID, который может снова
    // резонировать с тем же возмущением на следующем тике.
    private static final int GYRO_LOCKDOWN_TICKS = 20;
    // Гироскопическая прецессия (feed-forward компенсация): при вращении
    // корабля по курсу (yaw) вокруг ЕЩЁ не выровненной вертикали угловой
    // момент по yaw через недиагональные члены тензора инерции перетекает в
    // ось наклона пропорционально yawRate·tiltSin·(разница главных моментов
    // инерции). Точный аналитический учёт требует полного тензора инерции
    // (недоступен в API — только getInverseInertiaTensor), поэтому вместо
    // расчёта эффекта заранее ослабляем restoring-часть и демпфер, когда
    // |yawRate| велика — система физически неустойчива к резонансу между
    // yaw-вращением и tilt-коррекцией именно в этом режиме (см. лог: скачок
    // yawRate до -1.33 рад/с совпал с разгоном angVelTilt). Плавный порог
    // без разрыва: множитель убывает от 1.0 (yaw медленный) до
    // GYRO_MIN_GAIN_AT_HIGH_YAW (yaw быстрый).
    private static final double GYRO_YAW_DESTAB_THRESHOLD = 1.0; // рад/с, откуда начинаем снижать усиление
    private static final double GYRO_MIN_GAIN_AT_HIGH_YAW = 0.15;
    // ── Breakaway (продавливание застревания) ───────────────────────────────
    // Проблема из логов: после резкого сброса перевеса корабль завис на
    // tilt≈0.78 рад (~45°) на 23+ секунды, dOmegaTilt≈0.22 держался
    // постоянным — restoring-потолок (1.5) слишком мал, чтобы "продавить"
    // реактивное сопротивление статического контакта/опоры, а понижать сам
    // GYRO_MAX_RESTORING_DELTA_OMEGA обратно до 4.0 нельзя — это снова
    // взводит риск резонансного разгона (см. предыдущий фикс). Вместо этого
    // отслеживаем ЗАСТРЕВАНИЕ: если |tiltError| не уменьшается заметно на
    // протяжении GYRO_STUCK_TICKS_THRESHOLD тиков подряд, restoring-потолок
    // временно и плавно повышается пропорционально длительности застревания
    // (в отличие от интеграла — это НЕ накапливается бесконечно и полностью
    // сбрасывается, как только tiltError снова начинает уменьшаться, поэтому
    // не создаёт отложенного взрыва при внезапном исчезновении препятствия).
    private static final int GYRO_STUCK_TICKS_THRESHOLD = 15; // ~0.75с при 20 тиков/сек — застревание считается подтверждённым
    // Поднят с 0.02 до 0.26 рад (~15°): небольшая кочка/лёгкий крен держит
    // tiltError в районе 0.05-0.10 рад — при старом пороге 0.02 это уже
    // считалось "почти выровненным, но всё равно застреванием", запуская
    // весь breakaway/UNSTICK каскад (включая угловой толчок) на конструкции,
    // которая на самом деле стоит почти ровно. Именно так лёгкое касание
    // кочки перерастало в "торнадо" — см. логи: tilt=0.08 стабильно держится
    // 60 тиков (это НОРМАЛЬНО для мелкого шума подвески от кочки, а не
    // признак контакта с опорой, который стоит "продавливать" усиленным
    // импульсом), но старый порог всё равно запускал UNSTICK. При крене
    // меньше ~15° никакого продавливания вообще не требуется — обычный
    // PID+демпфер сам справляется, breakaway/UNSTICK нужны только для
    // случаев реального застревания на большом угле (лежит на боку/вверх
    // ногами).
    private static final double GYRO_STUCK_MIN_TILT_ERROR = 0.26;
    private static final double GYRO_STUCK_ERROR_PROGRESS_EPS = 0.005; // рад — порог "заметного" уменьшения ошибки за тик
    // Снижено с 4.0 до 2.0 (Вариант 1) — учетверение restoring-потолка при
    // ложном срабатывании breakaway (например, в затяжном динамическом
    // вираже, ошибочно принятом за статическое застревание) вызывало
    // мгновенный резкий переворот при выходе конструкции из поворота.
    // GYRO_STUCK_YAW_SUPPRESS_THRESHOLD уже отсекает часть таких случаев, но
    // сам множитель также сделан менее агрессивным как вторая линия защиты.
    private static final double GYRO_STUCK_MAX_RESTORING_MULTIPLIER = 2.0; // во сколько раз может вырасти потолок при полном застревании
    private static final int GYRO_STUCK_RAMP_TICKS = 40; // за сколько тиков множитель нарастает от 1.0 до максимума
    // Порог |yawRate|, начиная с которого "застревание" не считаем поводом
    // расширять restoring-потолок. Проблема из логов: при быстром вращении по
    // курсу гироскопическая прецессия (yaw→tilt) сама постоянно "подпитывает"
    // ошибку наклона — tiltError не убывает НЕ из-за статического контакта с
    // опорой (для которого расширение потолка и задумано), а из-за реального
    // физического источника момента, который расширенный потолок только
    // усиливает, провоцируя резонанс и последующий LOCKDOWN. Тот же порог, что
    // и у yaw-детюнинга restoring-части (GYRO_YAW_DESTAB_THRESHOLD) — при таком
    // |yawRate| PID и так снижает усиление, расширять потолок вдобавок нельзя.
    private static final double GYRO_STUCK_YAW_SUPPRESS_THRESHOLD = GYRO_YAW_DESTAB_THRESHOLD;
    // ── Unstick (отрыв от поверхности) ───────────────────────────────────────
    // Проблема, которую НЕ решает restoring-мультипликатор: если корабль
    // упал на бок и лежит на земле, реактивная сила НОРМАЛЬНОЙ РЕАКЦИИ ОПОРЫ
    // (контактное трение) гасит угловой импульс независимо от его величины —
    // это не "недостаточно сильный толчок", а другой физический канал
    // воздействия (контакт), который чисто угловой impulseScalar/s в принципе
    // не может пересилить. В логах: 31 секунда (stuckTicks=1214) на
    // tilt=0.45 с restoringMult=4.0 без малейшего прогресса. Решение —
    // дополнительный ЛИНЕЙНЫЙ импульс вдоль worldUp ("подпрыгнуть"), который
    // на короткое время отрывает корабль от поверхности, снимая контактную
    // реакцию и позволяя угловому восстановлению наконец подействовать.
    private static final int GYRO_UNSTICK_TICKS_THRESHOLD = 60; // ~3с непрерывного застревания при 20 тик/сек — явно контакт с опорой, не временное сопротивление
    private static final int GYRO_UNSTICK_COOLDOWN_TICKS = 60; // не повторять чаще раза в ~3с — даёт время физике отреагировать на предыдущий толчок
    // Импульс подбирается пропорционально массе через ту же getInverseInertiaTensor
    // логику, что и остальной стабилизатор — используем реальную массу корабля
    // (massData.getMass()), чтобы толчок был одинаково эффективен для лёгких
    // и тяжёлых конструкций, а не фиксированной величиной "на глаз".
    private static final double GYRO_UNSTICK_LINEAR_VELOCITY_KICK = 1.5; // м/с, желаемая вертикальная скорость сразу после толчка
    // Целевой dOmegaTilt (рад/с), сообщаемый конструкции ВДОЛЬ tiltAxis (то
    // есть в направлении УМЕНЬШЕНИЯ tiltError — то же соглашение о знаке,
    // что и у tiltRestoring/tiltDamping) синхронно с вертикальным линейным
    // толчком отрыва. Обычный PID-потолок (GYRO_MAX_DELTA_OMEGA_PER_TICK)
    // сюда намеренно не применяется: тот рассчитан на постоянное действие
    // тик за тиком без риска рывка, а здесь — разовый импульс именно в
    // момент временного снятия контакта с опорой, когда обычная угловая
    // скорость коррекции физически не успевает провернуть конструкцию за
    // краткое окно невесомости (см. лог: 660 тиков подряд без прогресса по
    // tilt, десяток чисто вертикальных UNSTICK-толчков без эффекта).
    private static final double GYRO_UNSTICK_ANGULAR_KICK = 0.8;

    /**
     * Вызывается Sable каждый физический тик пока блок находится на sublevel.
     * Реализует стабилизатор крена (roll) и тангажа (pitch) с учётом реального
     * тензора инерции корабля — PID выдаёт целевое изменение угловой скорости
     * (dOmega) по каждой оси коррекции, а фактический импульс получается
     * делением dOmega на эффективную обратную инерцию корабля вдоль этой оси
     * (n·invI·n), посчитанную в локальных координатах sublevel'а через
     * MassData.getInverseInertiaTensor(). Это даёт одинаковое время сходимости
     * для любого корабля без единого настраиваемого параметра.
     * Yaw (курс вокруг мировой вертикали) намеренно не ограничивается —
     * стабилизатор только выравнивает корабль горизонтально.
     * Наклон описывается единой осью коррекции tiltAxis = cross(currentUp,
     * worldUp) вместо двух раздельных каналов roll/pitch — устраняет борьбу
     * контуров через недиагональный тензор инерции. Yaw явно вычитается из
     * angVel проекцией на worldUp до расчёта ошибки/демпфера.
     */
    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double timeStep) {
        if (!gyroStabilizationActive) return;      // стабилизация выключена кнопкой на вкладке NPC
        if (!handle.isValid()) return;

        // ── Аварийный выключатель при резонансном срыве ──────────────────────
        // Пока активен — стабилизатор полностью бездействует (даже LOCKDOWN не
        // применяет импульсы): конструкция долёживает/падает в любом положении
        // под естественной физикой Sable, резонанс гаснет сам без "подпитки"
        // со стороны PID. По истечении окна все счётчики срыва обнуляются и
        // стабилизация возобновляется с чистого состояния.
        if (gyroEmergencyOffTicksLeft > 0) {
            gyroEmergencyOffTicksLeft--;
            if (gyroEmergencyOffTicksLeft == 0) {
                gyroConsecutiveLockdowns = 0;
                gyroLockdownFreeStreakTicks = 0;
                gyroLockdownTicksLeft = 0;
                gyroIntegralTilt = 0.0;
                gyroPrevRestoring = 0.0;
                gyroStuckTicks = 0;
                gyroUnstickCooldownLeft = 0;
                gyroPrevValid = false;
                gyroPrevTiltErrorValid = false;
                gyroCacheTicksLeft = 0;
                gyroFilteredUpValid = false; // фильтр переинициализируется текущей ориентацией на след. тике, без "подтягивания" через долгую паузу
                gyroDeadbandActive = false;
                LOGGER.info("[MachineSoul][gyro] pos={} аварийное отключение снято, стабилизация возобновлена", worldPosition);
            }
            return;
        }

        BlockState state = getBlockState();
        Direction facing = state.hasProperty(MachineSoulBlock.FACING)
                ? state.getValue(MachineSoulBlock.FACING)
                : Direction.SOUTH;

        Quaterniond orientation = new Quaterniond(subLevel.logicalPose().orientation());

        // facing.getStepX/Y/Z — компоненты направления без зависимости от Vec3i/Vector3i.
        // localUp вычисляется как локальная ось, перпендикулярная facing (Y мировой
        // системы блока в его собственных координатах) — жёстко привязана к
        // ориентации блока в конструкции, а не к facing напрямую, поэтому корректно
        // отслеживает произвольный крен/тангаж независимо от того, куда "смотрит" блок.
        Vector3d localForward = new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ()).normalize();
        Vector3d localUpRef = Math.abs(localForward.y) > 0.999
                ? new Vector3d(0.0, 0.0, 1.0)
                : new Vector3d(0.0, 1.0, 0.0);
        Vector3d localUp = new Vector3d(localUpRef)
                .sub(new Vector3d(localForward).mul(localUpRef.dot(localForward)))
                .normalize();

        Vector3d worldUp = new Vector3d(0.0, 1.0, 0.0);
        // currentUp — текущая ориентация "верха" корабля в мировых координатах,
        // единственный вектор, необходимый для описания наклона (roll+pitch слиты
        // в одну величину). Полностью аналогично cross(currentUp, worldUp) у
        // референсной реализации.
        Vector3d currentUp = orientation.transform(new Vector3d(localUp)).normalize();

        // ── Единая ось и угол наклона ────────────────────────────────────────
        // tiltAxis = cross(currentUp, worldUp): лежит строго в горизонтальной
        // плоскости, направление которой автоматически задаёт, "куда" клонится
        // корабль — крен и тангаж это просто разные направления ОДНОГО и того
        // же вектора ошибки, а не два раздельных PID, которые могут тянуть
        // систему в разные стороны через недиагональные члены тензора инерции.
        Vector3d tiltAxis = new Vector3d(currentUp).cross(worldUp);
        double tiltSin = tiltAxis.length();
        double tiltCos = currentUp.dot(worldUp);
        double tiltError = Math.atan2(tiltSin, tiltCos); // угол наклона от вертикали, радианы, [0, π]

        boolean nearVertical = tiltSin < GYRO_EPS; // currentUp почти совпадает/противоположен worldUp — ось не определена
        if (!nearVertical) {
            tiltAxis.mul(1.0 / tiltSin); // нормализуем: |cross|=sin(угол), безопасно делить (tiltSin >= GYRO_EPS)
        }

        // ── EMA-фильтрация currentUp для PID (гашение дребезга подвески) ────
        // alpha = timeStep / (timeStep + tau): стандартная дискретная EMA с
        // постоянной времени tau, корректно адаптируется к переменному
        // timeStep (суб-тики Sable) без пересчёта вручную. На первом валидном
        // тике фильтр инициализируется текущим значением — без "разгона" с
        // произвольной точки, которое иначе выглядело бы как ложный скачок.
        if (!gyroFilteredUpValid) {
            gyroFilteredUp.set(currentUp);
            gyroFilteredUpValid = true;
        } else {
            double alpha = timeStep / (timeStep + GYRO_TILT_FILTER_TIME_CONSTANT);
            gyroFilteredUp.lerp(currentUp, alpha);
            double filteredLen = gyroFilteredUp.length();
            if (filteredLen > GYRO_EPS) {
                gyroFilteredUp.mul(1.0 / filteredLen); // ре-нормализация: lerp двух единичных векторов даёт |v|<1
            } else {
                gyroFilteredUp.set(currentUp);
            }
        }
        Vector3d filteredTiltAxis = new Vector3d(gyroFilteredUp).cross(worldUp);
        double filteredTiltSin = filteredTiltAxis.length();
        double filteredTiltCos = gyroFilteredUp.dot(worldUp);
        double filteredTiltError = Math.atan2(filteredTiltSin, filteredTiltCos);
        boolean filteredNearVertical = filteredTiltSin < GYRO_EPS;
        if (!filteredNearVertical) {
            filteredTiltAxis.mul(1.0 / filteredTiltSin);
        }

        // ── Deadband с гистерезисом на отфильтрованный tiltError ────────────
        // Ниже GYRO_TILT_DEADBAND_EXIT деактивируем PID полностью (restoring
        // не выдаётся, интеграл не копится) — конструкция считается
        // выровненной. Выше GYRO_TILT_DEADBAND_ENTER — точно активируем.
        // Между ними сохраняется предыдущее состояние (гистерезис Шмитта),
        // что не даёт deadband самому мигать на границе.
        if (gyroDeadbandActive) {
            if (filteredTiltError < GYRO_TILT_DEADBAND_EXIT) {
                gyroDeadbandActive = false;
            }
        } else {
            if (filteredTiltError > GYRO_TILT_DEADBAND_ENTER) {
                gyroDeadbandActive = true;
            }
        }

        Vector3d angVel = handle.getAngularVelocity(new Vector3d());
        // Проекция angVel на worldUp — это компонента yaw (курс). Явно вычитаем
        // её из angVel ДО вычисления angVelTilt, чтобы демпфер и предохранители
        // ни при каких обстоятельствах не гасили и не воспринимали свободное
        // вращение по курсу как "накренивание" — конструкция должна свободно
        // поворачиваться вправо/влево без сопротивления стабилизатора.
        double yawRate = angVel.dot(worldUp);
        Vector3d angVelNoYaw = new Vector3d(angVel).sub(new Vector3d(worldUp).mul(yawRate));
        double angVelTilt = nearVertical ? 0.0 : angVelNoYaw.dot(tiltAxis);

        // ── Обнаружение аномального скачка → аварийная блокировка ──────────
        // Сравниваем ТЕКУЩИЕ angVelTilt/yawRate с их значением на ПРОШЛОМ
        // физтике этого же блока. Резкое приращение независимо от абсолютной
        // величины — верный признак внешнего возмущения (гироскопическая
        // прецессия, столкновение, стыковка с другим кораблём), которое
        // обычный PID не спроектирован гасить корректно за один шаг.
        if (gyroPrevValid) {
            double jumpTilt = Math.abs(angVelTilt - gyroPrevAngVelTilt);
            // jumpYaw больше НЕ участвует в условии триггера: резкий разворот
            // по курсу (пилот дёрнул штурвал) — штатное управляемое действие,
            // а не признак резонанса/удара. Он уже корректно демпфируется
            // отдельно через yawGain/GYRO_YAW_DESTAB_THRESHOLD в PID-ветке ниже.
            // Реальная гироскопическая аномалия проявляется именно в jumpTilt
            // (см. разбор логов: tilt≈0 + yawRate≈0.99 → на след. тике
            // tilt скачет до 0.6-2.0 из-за прецессии, а не из-за самого yaw).
            double jumpYaw = Math.abs(yawRate - gyroPrevYawRate);
            // Порог скачка масштабируется вверх при большом |tiltError|
            // (до GYRO_LOCKDOWN_JUMP_THRESHOLD_MULTIPLIER раз при tilt≈π):
            // именно в диапазоне "перевёрнут" (tilt>~1.5 рад) сам законный,
            // желаемый рывок демпфера/restoring для самовыравнивания создаёт
            // резкое приращение angVelTilt, неотличимое по величине от
            // внешнего удара — фиксированный порог поэтому постоянно ложно
            // срабатывал LOCKDOWN'ом именно в момент, когда стабилизатор
            // наконец начинал реально продавливать переворот (см. логи:
            // tilt=1.73→2.08→LOCKDOWN×4→аварийное отключение, корабль
            // застревал вверх ногами на десятки секунд).
            double jumpThreshold = GYRO_LOCKDOWN_JUMP_THRESHOLD
                    * (1.0 + (GYRO_LOCKDOWN_JUMP_THRESHOLD_MULTIPLIER - 1.0) * Math.min(1.0, Math.abs(tiltError) / Math.PI));
            if (jumpTilt > jumpThreshold) {
                gyroLockdownTicksLeft = GYRO_LOCKDOWN_TICKS;
                // ── Счётчик подряд идущих LOCKDOWN (детектор резонансного срыва) ──
                // Если с прошлого LOCKDOWN не прошло GYRO_LOCKDOWN_STABLE_WINDOW_TICKS
                // спокойной работы — это не новый независимый удар, а продолжение
                // того же незатухающего резонанса (см. лог: LOCKDOWN → PID → UNSTICK
                // → снова LOCKDOWN спустя доли секунды, десятки раз подряд).
                if (gyroLockdownFreeStreakTicks < GYRO_LOCKDOWN_STABLE_WINDOW_TICKS) {
                    gyroConsecutiveLockdowns++;
                } else {
                    gyroConsecutiveLockdowns = 1;
                }
                gyroLockdownFreeStreakTicks = 0;
                final double jumpTiltLog = jumpTilt, jumpYawLog = jumpYaw;
                gyroDebugLog(() -> String.format(java.util.Locale.ROOT,
                        "LOCKDOWN TRIGGERED: jumpTilt=%.3f jumpYaw=%.3f > порог=%.3f, блокировка на %d тиков, подряд=%d",
                        jumpTiltLog, jumpYawLog, GYRO_LOCKDOWN_JUMP_THRESHOLD, GYRO_LOCKDOWN_TICKS, gyroConsecutiveLockdowns));

                if (gyroConsecutiveLockdowns >= GYRO_CONSECUTIVE_LOCKDOWNS_THRESHOLD) {
                    gyroEmergencyOffTicksLeft = GYRO_EMERGENCY_OFF_TICKS;
                    LOGGER.info("[MachineSoul][gyro] pos={} РЕЗОНАНСНЫЙ СРЫВ: {} LOCKDOWN подряд без стабильного окна, "
                            + "стабилизатор аварийно отключён на {} тиков", worldPosition, gyroConsecutiveLockdowns, GYRO_EMERGENCY_OFF_TICKS);
                }
            }
        }
        gyroPrevAngVelTilt = angVelTilt;
        gyroPrevYawRate = yawRate;
        gyroPrevValid = true;
        // Считаем тики без нового LOCKDOWN — растёт на каждом тике этой ветки
        // (т.е. пока не сработал new LOCKDOWN выше и не активен старый ниже
        // не проверяется здесь намеренно: инкремент должен идти независимо от
        // того, идёт ли ещё старая блокировка, иначе "стабильное окно" никогда
        // не наберётся во время затяжной серии GYRO_LOCKDOWN_TICKS-блокировок).
        if (gyroLockdownFreeStreakTicks < Integer.MAX_VALUE) {
            gyroLockdownFreeStreakTicks++;
        }

        if (gyroLockdownTicksLeft > 0) {
            gyroLockdownTicksLeft--;
            // Полное аварийное гашение: в отличие от штатного HARD BRAKE
            // (который трогает только tiltAxis), здесь гасится ВСЯ angVel
            // целиком, включая yaw — при зафиксированном скачке такой силы
            // нельзя полагаться на то, что вращение по курсу всё ещё "просто
            // управляемый поворот", оно уже часть неконтролируемой раскрутки.
            // gyroIntegralTilt сбрасывается, чтобы после выхода из блокировки
            // PID не рванул сразу на накопленную за время блокировки ошибку.
            gyroIntegralTilt = 0.0;
            gyroCacheTicksLeft = 0; // форсируем пересчёт s после блокировки — ориентация могла сильно измениться
            gyroStuckTicks = 0; // после блокировки застревание не актуально — ситуация изменилась
            gyroPrevTiltErrorValid = false;
            gyroFilteredUpValid = false; // переинициализация фильтра текущей ориентацией на след. тике
            gyroDeadbandActive = false;
            if (angVel.lengthSquared() > GYRO_EPS * GYRO_EPS) {
                MassData massDataLockdown = subLevel.getMassTracker();
                if (massDataLockdown != null && !massDataLockdown.isInvalid()) {
                    Vector3d angVelLocal = orientation.transformInverse(new Vector3d(angVel));
                    Vector3d invIAngVel = new Vector3d();
                    massDataLockdown.getInverseInertiaTensor().transform(angVelLocal, invIAngVel);
                    double sFull = angVelLocal.dot(invIAngVel);
                    if (Double.isFinite(sFull) && sFull > 1e-9) {
                        double impulseScalar = -1.0 / sFull;
                        Vector3d lockdownImpulse = new Vector3d(angVel).mul(impulseScalar);
                        if (Double.isFinite(lockdownImpulse.x) && Double.isFinite(lockdownImpulse.y)
                                && Double.isFinite(lockdownImpulse.z)) {
                            handle.applyAngularImpulse(lockdownImpulse);
                        }
                    }
                }
            }
            final int ticksLeftLog = gyroLockdownTicksLeft;
            gyroDebugLog(() -> "LOCKDOWN active, ticksLeft=" + ticksLeftLog);
            return; // обычный PID не выполняется, пока блокировка активна
        }

        // ── Breakaway: обнаружение застревания и адаптивный потолок restoring ──
        // "Застревание" = |tiltError| не уменьшается заметно тик от тика,
        // несмотря на то что стабилизатор активно толкает (типичный признак
        // статического контакта/реактивной опоры, продавить которую нужно
        // бОльшим восстанавливающим импульсом, а не большей угловой скоростью
        // демпфера — поэтому не трогаем GYRO_MAX_DELTA_OMEGA_PER_TICK/KD).
        double absTiltError = Math.abs(tiltError);
        if (gyroPrevTiltErrorValid && absTiltError > GYRO_STUCK_MIN_TILT_ERROR) { // ниже этого порога застревание не считается — почти выровнено, дальнейшая "борьба" не нужна
            double progress = gyroPrevTiltError - absTiltError; // >0 если ошибка уменьшается
            if (progress < GYRO_STUCK_ERROR_PROGRESS_EPS) {
                gyroStuckTicks++;
            } else {
                gyroStuckTicks = 0;
            }
        } else {
            gyroStuckTicks = 0;
        }
        gyroPrevTiltError = absTiltError;
        gyroPrevTiltErrorValid = true;

        double restoringMultiplier = 1.0;
        if (gyroStuckTicks > GYRO_STUCK_TICKS_THRESHOLD && Math.abs(yawRate) <= GYRO_STUCK_YAW_SUPPRESS_THRESHOLD) {
            // Плавный линейный разгон множителя от 1.0 до
            // GYRO_STUCK_MAX_RESTORING_MULTIPLIER за GYRO_STUCK_RAMP_TICKS
            // тиков после подтверждения застревания — не скачок, чтобы не
            // создать собственный резкий импульс, который заново потревожит
            // соседей по конструкции. Не расширяем потолок при быстром yaw —
            // см. GYRO_STUCK_YAW_SUPPRESS_THRESHOLD: там "застревание" вызвано
            // прецессией, а не статическим контактом, и усиление импульса
            // только раскачивает резонанс.
            int ticksIntoStuck = gyroStuckTicks - GYRO_STUCK_TICKS_THRESHOLD;
            double rampT = Math.min(1.0, ticksIntoStuck / (double) GYRO_STUCK_RAMP_TICKS);
            restoringMultiplier = 1.0 + (GYRO_STUCK_MAX_RESTORING_MULTIPLIER - 1.0) * rampT;
        }

        // ── Unstick: отрыв от поверхности при затяжном застревании ──────────
        // Расширенный restoring-потолок продавливает ВРЕМЕННОЕ сопротивление
        // (например, сброс груза, о котором опора ещё "помнит" через упругость
        // контакта), но бессилен против УСТОЙЧИВОГО контакта с землёй — сила
        // реакции опоры действует по другому физическому каналу и гасит
        // угловой импульс независимо от его величины. Если застревание длится
        // намного дольше нормального продавливания (GYRO_UNSTICK_TICKS_THRESHOLD
        // тиков), считаем это контактом с опорой и даём короткий вертикальный
        // толчок, чтобы физически оторвать корабль от поверхности.
        // ИСПРАВЛЕНО: было `gyroStuckTicks % GYRO_UNSTICK_TICKS_THRESHOLD == 0` —
        // точное совпадение по модулю ненадёжно, так как физтик может идти
        // неравномерно (суб-тики Sable, троттлинг сервера), из-за чего
        // gyroStuckTicks способен "перепрыгнуть" нужное кратное значение и
        // условие никогда не выполнится за весь эпизод застревания. Теперь
        // единственный гейт — cooldown: срабатывает при первом превышении
        // порога и на каждом первом тике после истечения кулдауна, пока
        // застревание продолжается.
        if (gyroStuckTicks >= GYRO_UNSTICK_TICKS_THRESHOLD && gyroUnstickCooldownLeft <= 0
                && Math.abs(yawRate) <= GYRO_STUCK_YAW_SUPPRESS_THRESHOLD) {
            MassData massDataUnstick = subLevel.getMassTracker();
            if (massDataUnstick != null && !massDataUnstick.isInvalid() && massDataUnstick.getMass() > GYRO_EPS) {
                // Импульс = масса × желаемая скорость (p = m·v) — стандартная
                // формула, даёт одинаковый эффект отрыва независимо от массы
                // конкретной конструкции.
                double impulseMagnitude = massDataUnstick.getMass() * GYRO_UNSTICK_LINEAR_VELOCITY_KICK;
                Vector3d unstickImpulse = new Vector3d(worldUp).mul(impulseMagnitude);

                // Угловой довесок (Вариант 2, исправление после первой попытки):
                // чисто вертикальный толчок лишь на краткий миг снимает
                // статический контакт с опорой, но САМ ПО СЕБЕ не поворачивает
                // конструкцию — угловая скорость коррекции (демпфированная до
                // 0.15-0.35 рад/с/тик намеренно, чтобы не давать резкий рывок)
                // недостаточна, чтобы довернуть корпус за это короткое окно
                // невесомости, поэтому корабль падает обратно на тот же бок
                // (см. логи: 660 тиков подряд stuckTicks, десяток UNSTICK'ов
                // без какого-либо прогресса по tilt=2.05→2.03). Даём угловой
                // импульс вокруг оси коррекции наклона ИМЕННО в момент отрыва
                // от опоры, когда реактивный противомомент контакта временно
                // отсутствует — только тогда угловой толчок способен реально
                // провернуть конструкцию, а не быть погашенным опорой.
                Vector3d unstickAngularImpulse = new Vector3d();
                double sUnstick = gyroGetEffectiveInverseInertia(orientation, tiltAxis, massDataUnstick);
                if (Double.isFinite(sUnstick) && sUnstick > 1e-6) {
                    double impulseScalarUnstick = GYRO_UNSTICK_ANGULAR_KICK / sUnstick;
                    if (Double.isFinite(impulseScalarUnstick)) {
                        unstickAngularImpulse = new Vector3d(tiltAxis).mul(impulseScalarUnstick);
                    }
                }

                if (Double.isFinite(unstickImpulse.x) && Double.isFinite(unstickImpulse.y) && Double.isFinite(unstickImpulse.z)
                        && Double.isFinite(unstickAngularImpulse.x) && Double.isFinite(unstickAngularImpulse.y) && Double.isFinite(unstickAngularImpulse.z)) {
                    handle.applyLinearAndAngularImpulse(unstickImpulse, unstickAngularImpulse, true);
                    gyroUnstickCooldownLeft = GYRO_UNSTICK_COOLDOWN_TICKS;
                    // Синхронизируем "прошлое" значение angVelTilt для детектора
                    // аномального скачка (LOCKDOWN) на ожидаемую величину ПОСЛЕ
                    // применения импульса — иначе собственный контролируемый
                    // угловой толчок UNSTICK на следующем тике читается как
                    // jumpTilt≈GYRO_UNSTICK_ANGULAR_KICK и может (вместе с
                    // остаточным вращением от предыдущих толчков) пробить
                    // GYRO_LOCKDOWN_JUMP_THRESHOLD, аварийно блокируя
                    // стабилизатор сразу после того, как он наконец сдвинул
                    // конструкцию с мёртвой точки.
                    gyroPrevAngVelTilt = angVelTilt + GYRO_UNSTICK_ANGULAR_KICK;
                    // Лог UNSTICK не троттлируется наравне с обычным диагностическим
                    // логом (используется LOGGER напрямую) — иначе событие могло
                    // "проглатываться" общим 1-секундным троттлингом gyroDebugLog,
                    // если обычный лог этого же тика успевал занять окно первым,
                    // из-за чего в предыдущих логах UNSTICK не было видно вообще,
                    // хотя импульс применялся.
                    final int stuckTicksLog2 = gyroStuckTicks;
                    final double angularKickLog = GYRO_UNSTICK_ANGULAR_KICK;
                    LOGGER.info("[MachineSoul][gyro] pos={} UNSTICK: застревание {} тиков подряд, вертикальный+угловой толчок применён (impulse={}, angularKick={})",
                            worldPosition, stuckTicksLog2, String.format(java.util.Locale.ROOT, "%.2f", impulseMagnitude),
                            String.format(java.util.Locale.ROOT, "%.2f", angularKickLog));
                }
            }
        }
        if (gyroUnstickCooldownLeft > 0) {
            gyroUnstickCooldownLeft--;
        }

        // ── PID по единой оси наклона (П + И с anti-windup + Д) ─────────────
        // yawGain: снижает усиление П/И/Д при быстром вращении по курсу —
        // именно в этом режиме недиагональные члены тензора инерции создают
        // гироскопическую прецессию yaw→tilt, которую иначе стабилизатор
        // воспринимает как обычную ошибку наклона и резонансно раскачивает.
        double yawGain = 1.0;
        double absYawRate = Math.abs(yawRate);
        if (absYawRate > GYRO_YAW_DESTAB_THRESHOLD) {
            // Плавное убывание 1/x к GYRO_MIN_GAIN_AT_HIGH_YAW, без разрыва в
            // точке порога (при absYawRate == threshold gain == 1.0).
            double t = GYRO_YAW_DESTAB_THRESHOLD / absYawRate; // (0,1]
            yawGain = GYRO_MIN_GAIN_AT_HIGH_YAW + (1.0 - GYRO_MIN_GAIN_AT_HIGH_YAW) * t;
        }

        // Restoring (П+И) и демпфер (Д) считаются раздельно, каждый на СВОЕЙ
        // оси: restoring — на filteredTiltAxis (фильтрованный вход, гасит
        // дребезг подвески), демпфер — на сырой tiltAxis/angVelTilt (не
        // фильтруется: должен гасить реальную угловую скорость немедленно,
        // иначе быстрые толчки от кочек перестанут демпфироваться и энергия
        // будет копиться). Раньше оба складывались в один tiltDOmega и
        // применялись по ОДНОЙ оси — с введением фильтра оси restoring и
        // демпфера в общем случае перестали совпадать, складывать их стало
        // физически некорректно.
        double tiltRestoring = 0.0;
        if (!filteredNearVertical && gyroDeadbandActive) {
            gyroIntegralTilt += filteredTiltError * timeStep;
            gyroIntegralTilt = Math.max(-GYRO_MAX_INTEGRAL, Math.min(GYRO_MAX_INTEGRAL, gyroIntegralTilt));

            double tiltPTerm = filteredTiltError * GYRO_KP * yawGain;
            double tiltITerm = gyroIntegralTilt * GYRO_KI * yawGain;

            // Восстанавливающая часть (П+И) — ограничена потолком, стабилизатор
            // не может резко "рвануть" корабль к цели. Потолок временно
            // расширяется restoringMultiplier'ом при подтверждённом
            // застревании (см. блок Breakaway выше) — иначе он не может
            // "продавить" статическое сопротивление опоры.
            double effectiveRestoringCap = GYRO_MAX_RESTORING_DELTA_OMEGA * restoringMultiplier;
            tiltRestoring = (tiltPTerm + tiltITerm) * timeStep;
            tiltRestoring = Math.max(-effectiveRestoringCap, Math.min(effectiveRestoringCap, tiltRestoring));
            double adaptiveMaxDeltaOmega = computeMaxDeltaOmegaForTilt(absTiltError);
            tiltRestoring = Math.max(-adaptiveMaxDeltaOmega, Math.min(adaptiveMaxDeltaOmega, tiltRestoring));

            // Slew-rate: приращение restoring-части относительно прошлого
            // применённого тика не может превышать GYRO_RESTORING_SLEW_PER_TICK
            // (Вариант 1) — не даёт стабилизатору самому создавать крен резким
            // изменением restoring-импульса, например на входе/выходе из
            // deadband или при скачке filteredTiltError.
            double restoringDelta = tiltRestoring - gyroPrevRestoring;
            restoringDelta = Math.max(-GYRO_RESTORING_SLEW_PER_TICK, Math.min(GYRO_RESTORING_SLEW_PER_TICK, restoringDelta));
            tiltRestoring = gyroPrevRestoring + restoringDelta;
        } else {
            // Либо near-vertical, либо deadband активен (конструкция в
            // пределах допуска выровненности) — restoring не выдаётся вообще,
            // интеграл не копим, чтобы не накапливать шум на неопределённом/
            // незначимом отклонении.
            gyroIntegralTilt = 0.0;
        }
        gyroPrevRestoring = tiltRestoring;
        if (filteredNearVertical) {
            gyroStuckTicks = 0;
            gyroPrevTiltErrorValid = false;
        }

        // Демпфер (Д) считается ОТДЕЛЬНО от потолка restoring-члена: если
        // |angVelTilt| велика, демпфер обязан быть способен погасить её
        // полностью за тик, иначе на больших скоростях (после сильного
        // внешнего удара) раскачка не гасится и корабль улетает в
        // бесконтрольное вращение. Демпфер клипуется по модулю текущей
        // angVelTilt (не может перегасить в обратную сторону), но НЕ
        // клипуется общим потолком GYRO_MAX_RESTORING_DELTA_OMEGA — это и
        // есть "физически честная" clamping-формула демпфера. Демпфер НЕ
        // ослабляется yawGain — гасить угловую скорость нужно всегда,
        // ослаблять нужно только "толкающую" restoring-часть. Демпфер также
        // НЕ проходит через deadband — деадбенд относится только к статичной
        // ошибке угла, а не к угловой скорости: даже в пределах допустимого
        // крена угловую скорость (например, от толчка на кочке) гасить нужно
        // всегда, иначе она успеет накопиться в реальный крен на след. тиках.
        double tiltDamping = 0.0;
        if (!nearVertical) {
            double tiltDampingRaw = -angVelTilt * GYRO_KD;
            double tiltDampingClamped = Math.max(-Math.abs(angVelTilt), Math.min(Math.abs(angVelTilt), tiltDampingRaw));
            if (Math.abs(angVelTilt) > GYRO_MAX_ANGVEL_BEFORE_HARD_BRAKE) {
                // Жёсткое торможение: полностью гасим angVelTilt за этот тик,
                // независимо от того, что насчитал КД-коэффициент.
                tiltDampingClamped = -angVelTilt;
            }
            tiltDamping = Math.max(-GYRO_MAX_DELTA_OMEGA_PER_TICK, Math.min(GYRO_MAX_DELTA_OMEGA_PER_TICK, tiltDampingClamped));
        }

        if (Math.abs(tiltRestoring) < 1e-12 && Math.abs(tiltDamping) < 1e-12) return;

        // ── Перевод целевых dOmega в импульс через реальный тензор инерции ──
        MassData massData = subLevel.getMassTracker();
        if (massData == null || massData.isInvalid()) {
            gyroDebugLog(() -> "massData недоступен (null=" + (massData == null) + ")");
            return;
        }

        Vector3d totalImpulse = new Vector3d();

        if (Math.abs(tiltRestoring) >= 1e-12) {
            double sRestoring = gyroGetEffectiveInverseInertia(orientation, filteredTiltAxis, massData);
            if (Double.isFinite(sRestoring) && sRestoring > 1e-6) {
                double impulseScalarRestoring = tiltRestoring / sRestoring;
                if (Double.isFinite(impulseScalarRestoring)) {
                    totalImpulse.add(new Vector3d(filteredTiltAxis).mul(impulseScalarRestoring));
                } else {
                    final double dOmegaLog = tiltRestoring, sLog = sRestoring;
                    gyroDebugLog(() -> "tilt restoring impulseScalar недействителен: dOmega=" + dOmegaLog + " s=" + sLog);
                }
            } else {
                final double sLog = sRestoring;
                gyroDebugLog(() -> "tilt restoring s недействителен: s=" + sLog);
            }
        }

        if (Math.abs(tiltDamping) >= 1e-12) {
            double s = gyroGetEffectiveInverseInertia(orientation, tiltAxis, massData);
            // Порог отсечки поднят с 1e-12 до 1e-6: почти-сингулярный s (корабль
            // почти симметричен/тонок вдоль оси коррекции) давал impulseScalar
            // порядка 10^6 и выше даже при малом dOmega — именно так возникал
            // взрывной разгон angVel, зафиксированный в логах.
            if (Double.isFinite(s) && s > 1e-6) {
                double impulseScalar = tiltDamping / s;
                if (Double.isFinite(impulseScalar)) {
                    totalImpulse.add(new Vector3d(tiltAxis).mul(impulseScalar));
                } else {
                    final double dOmegaLog = tiltDamping, sLog = s;
                    gyroDebugLog(() -> "tilt damping impulseScalar недействителен: dOmega=" + dOmegaLog + " s=" + sLog);
                }
            } else {
                final double sLog = s;
                gyroDebugLog(() -> "tilt damping s недействителен: s=" + sLog);
            }
        }

        if (!Double.isFinite(totalImpulse.x) || !Double.isFinite(totalImpulse.y) || !Double.isFinite(totalImpulse.z)) {
            return;
        }
        if (totalImpulse.lengthSquared() < 1e-24) return;

        // ── Абсолютный предохранитель от взрыва ─────────────────────────────
        // impulseScalar = dOmega / s физически корректен ТОЛЬКО если ось
        // коррекции — собственный вектор тензора инерции. Для произвольной
        // (roll/pitch) оси это лишь приближение: (a) при s→0 (корабль почти
        // симметричен/тонок вдоль этой оси) impulseScalar взрывается даже при
        // конечном dOmega; (b) сам импульс меняет angVel и по перпендикулярным
        // осям (недиагональный invI), что на следующем тике даёт паразитную
        // ошибку по ДРУГОЙ оси и рекурсивно наращивает импульс — именно так
        // angVel улетела до ~10^4 рад/с в логах. Единственная надёжная защита —
        // ограничить сам импульс так, чтобы результирующая angVel после его
        // применения не могла превысить безопасный потолок ни по одной оси.
        Vector3d predictedDeltaOmega = new Vector3d();
        {
            Vector3d impulseLocal = orientation.transformInverse(new Vector3d(totalImpulse));
            Vector3d deltaOmegaLocal = new Vector3d();
            massData.getInverseInertiaTensor().transform(impulseLocal, deltaOmegaLocal);
            predictedDeltaOmega.set(orientation.transform(deltaOmegaLocal));
        }
        if (!Double.isFinite(predictedDeltaOmega.x) || !Double.isFinite(predictedDeltaOmega.y)
                || !Double.isFinite(predictedDeltaOmega.z)) {
            gyroDebugLog(() -> "predictedDeltaOmega недействителен, импульс отменён");
            return;
        }
        Vector3d predictedAngVel = new Vector3d(angVel).add(predictedDeltaOmega);
        double predictedMag = predictedAngVel.length();
        double currentMag = angVel.length();
        // Абсолютный потолок результирующей |angVel| после коррекции — ФИКСИРОВАН
        // на GYRO_MAX_ANGVEL_BEFORE_HARD_BRAKE, а не относительно currentMag.
        // БАГ прежней версии: allowedMag = max(порог, currentMag) растягивался
        // вместе с currentMag при уже начавшемся резонансном разгоне (крен/тангаж
        // связаны через недиагональные члены тензора инерции — импульс по roll
        // паразитно меняет angVel по pitch и наоборот, что на следующем тике
        // читается как новая ошибка и импульс снова растёт). В результате
        // "предохранитель" сам себя отключал по мере роста angVel, что и
        // приводило к взрыву до ~10^4-10^5 рад/с, зафиксированному в логах.
        double allowedMag = GYRO_MAX_ANGVEL_BEFORE_HARD_BRAKE;
        // Если ТЕКУЩАЯ angVel уже превышает потолок (внешний удар, резонанс с
        // прошлого тика и т.п.) — импульс обязан быть направлен строго на
        // погашение uже накопленной angVel, а не масштабированной "версией себя".
        // Иначе scale-вниз всё равно может оставить резонансную связку нетронутой.
        if (currentMag > GYRO_MAX_ANGVEL_BEFORE_HARD_BRAKE) {
            final double currentMagLog = currentMag;
            gyroDebugLog(() -> String.format(java.util.Locale.ROOT,
                    "HARD BRAKE: |angVel|=%.2f > потолок=%.2f, покомпонентное гашение по осям roll/pitch",
                    currentMagLog, GYRO_MAX_ANGVEL_BEFORE_HARD_BRAKE));
            // Полный прямой тензор инерции в API недоступен (подтверждён только
            // getInverseInertiaTensor), поэтому гасим angVelNoYaw тем же приёмом
            // dOmega/s, что и штатный демпфер, по ЕДИНОЙ оси наклона (tiltAxis,
            // если определена) — это резкий, но единственный физически
            // согласованный способ погасить взрыв без прямого тензора.
            // Компонента вдоль worldUp/yaw намеренно не трогается (стабилизатор
            // не управляет курсом, конструкция должна свободно поворачиваться).
            Vector3d brakeImpulse = new Vector3d();
            if (!nearVertical && Math.abs(angVelTilt) > GYRO_EPS) {
                double s = gyroGetEffectiveInverseInertia(orientation, tiltAxis, massData);
                if (Double.isFinite(s) && s > 1e-6) {
                    double impulseScalar = -angVelTilt / s;
                    if (Double.isFinite(impulseScalar)) {
                        brakeImpulse.add(new Vector3d(tiltAxis).mul(impulseScalar));
                    }
                }
            }
            if (Double.isFinite(brakeImpulse.x) && Double.isFinite(brakeImpulse.y) && Double.isFinite(brakeImpulse.z)
                    && brakeImpulse.lengthSquared() > 1e-24) {
                handle.applyAngularImpulse(brakeImpulse);
            }
            gyroIntegralTilt = 0.0;
            return;
        }
        if (predictedMag > allowedMag && predictedMag > 1e-9) {
            double scale = allowedMag / predictedMag;
            totalImpulse.mul(scale);
            final double predictedMagLog = predictedMag, allowedMagLog = allowedMag;
            gyroDebugLog(() -> String.format(java.util.Locale.ROOT,
                    "impulse clamp: predicted|angVel|=%.2f > allowed=%.2f, scale=%.6f",
                    predictedMagLog, allowedMagLog, scale));
        }
        if (totalImpulse.lengthSquared() < 1e-24) return;

        final double tiltErrorLog = tiltError, angVelTiltLog = angVelTilt, tiltDOmegaLog = tiltRestoring + tiltDamping;
        final double yawRateLog = yawRate;
        final boolean nearVerticalLog = nearVertical;
        final double restoringMultiplierLog = restoringMultiplier;
        final int stuckTicksLog = gyroStuckTicks;
        final int consecutiveLockdownsLog = gyroConsecutiveLockdowns;
        gyroDebugLog(() -> String.format(java.util.Locale.ROOT,
                "tilt=%.4f angVelTilt=%.4f dOmegaTilt=%.5f yawRate(untouched)=%.4f mass=%.2f nearVertical=%b stuckTicks=%d restoringMult=%.2f consecutiveLockdowns=%d",
                tiltErrorLog, angVelTiltLog, tiltDOmegaLog, yawRateLog,
                massData.getMass(), nearVerticalLog, stuckTicksLog, restoringMultiplierLog, consecutiveLockdownsLog));

        handle.applyAngularImpulse(totalImpulse);
    }

    /**
     * Эффективная обратная инерция вдоль оси axis (мировые координаты),
     * s = axis·(invInertiaTensor_world·axis), с кэшированием между тиками.
     * Оптимизация: matrix-transform + dot — самая тяжёлая операция на
     * физтик стабилизатора, вызываемая до 2 раз (PID + возможный HARD BRAKE).
     * axis (tiltAxis) при штатной работе меняется плавно, поэтому кэш
     * переиспользуется, пока направление оси не отклонилось больше
     * GYRO_CACHE_AXIS_COS_THRESHOLD и не истёк принудительный лимит
     * GYRO_CACHE_MAX_TICKS (страхует от накопления ошибки на очень медленном,
     * но неограниченно долгом дрейфе оси, а также от устаревания кэша при
     * изменении массы/формы корабля, которое само по себе не двигает axis).
     */
    private double gyroGetEffectiveInverseInertia(Quaterniond orientation, Vector3d axis, MassData massData) {
        boolean cacheValid = gyroCachedAxis != null
                && gyroCacheTicksLeft > 0
                && !Double.isNaN(gyroCachedS)
                && gyroCachedAxis.dot(axis) >= GYRO_CACHE_AXIS_COS_THRESHOLD;
        if (cacheValid) {
            gyroCacheTicksLeft--;
            return gyroCachedS;
        }
        Vector3d nLocal = orientation.transformInverse(new Vector3d(axis));
        Vector3d invIn = new Vector3d();
        massData.getInverseInertiaTensor().transform(nLocal, invIn);
        double s = nLocal.dot(invIn);
        gyroCachedAxis = new Vector3d(axis);
        gyroCachedS = s;
        gyroCacheTicksLeft = GYRO_CACHE_MAX_TICKS;
        return s;
    }

    /**
     * Троттлированный (раз в ~1с) диагностический лог стабилизатора —
     * временный инструмент для отладки эффекта коррекции по факту в игре.
     */
    private void gyroDebugLog(java.util.function.Supplier<String> message) {
        long now = System.currentTimeMillis();
        if (now - gyroLastLogMs < 1000L) return;
        gyroLastLogMs = now;
        LOGGER.info("[MachineSoul][gyro] pos={} {}", worldPosition, message.get());
    }
    // ── end gyroscope ─────────────────────────────────────────────────────────

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
                p -> !p.isSpectator() && !p.isCreative() && p.position().distanceTo(worldCenter) <= r);

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
        // того, кто физически ближе (структурная угроза важнее одиночной цели).
        //
        // Враждебный МОБ и игрок, напротив, РАВНОПРАВНЫ вне режима FOLLOW:
        // выбирается тот, кто физически ближе. Безусловный приоритет моба
        // над игроком (моб выигрывает даже будучи в разы дальше) приводил к
        // тому, что блок игнорировал игрока в упор ради слайма за 20+ блоков
        // — это было корректно ТОЛЬКО как способ прервать followMode (что уже
        // сделано отдельной проверкой выше, до этого блока), но ошибочно
        // применялось и к обычному боевому режиму, где followMode уже false.
        // Пассивный моб — самый низкий приоритет, только если вообще
        // больше не на кого навестись (и он вообще нужен только тем, кто
        // явно просил атаковать Passive Mobs).
        Object pickedTarget; // CommanderHit | Mob | Player
        if (nearestCommander != null) {
            pickedTarget = nearestCommander;
        } else if (nearestHostileMob != null && nearestPlayer != null) {
            pickedTarget = hostileMobDistSq <= playerDistSq ? nearestHostileMob : nearestPlayer;
        } else if (nearestHostileMob != null) {
            pickedTarget = nearestHostileMob;
        } else if (nearestPlayer != null) {
            pickedTarget = nearestPlayer;
        } else if (nearestPassiveMob != null) {
            pickedTarget = nearestPassiveMob;
        } else {
            pickedTarget = null;
        }

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
        // Нейтральные по умолчанию мобы (голем, пиглины и т.п.) считаются
        // "опасными" только пока реально агрессивны — см.
        // TargetFilterData.isActuallyAggressive для той же логики.
        if (entity.getType().is(com.yourname.cbcautotarget.CBCAutoTargetTags.NEUTRAL_ENTITIES)) {
            if (entity instanceof net.minecraft.world.entity.NeutralMob neutralMob) {
                return neutralMob.isAngry();
            }
            if (entity instanceof net.minecraft.world.entity.Mob mob) {
                LivingEntity target = mob.getTarget();
                return target != null && target.isAlive();
            }
            return false;
        }
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
        // Стабилизация сохраняется как есть — настройка поведения, а не активности.
        tag.putBoolean("GyroStabilization", gyroStabilizationActive);
        tag.putBoolean("CreativeLocked", creativeLocked);
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
        tag.putBoolean("GyroStabilization", gyroStabilizationActive);
        tag.putBoolean("CreativeLocked", creativeLocked);
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
            gyroStabilizationActive = !tag.contains("GyroStabilization") || tag.getBoolean("GyroStabilization");
            creativeLocked = tag.contains("CreativeLocked") && tag.getBoolean("CreativeLocked");
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
            gyroStabilizationActive = !schematicBackup.contains("GyroStabilization") || schematicBackup.getBoolean("GyroStabilization");
            creativeLocked = schematicBackup.contains("CreativeLocked") && schematicBackup.getBoolean("CreativeLocked");
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
        tag.putBoolean("CreativeLocked", creativeLocked);
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
            gyroStabilizationActive = !tag.contains("GyroStabilization") || tag.getBoolean("GyroStabilization");
            creativeLocked = tag.contains("CreativeLocked") && tag.getBoolean("CreativeLocked");
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