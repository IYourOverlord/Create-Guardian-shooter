package com.yourname.cbcautotarget.blockentity;

import com.yourname.cbcautotarget.compat.SableCompat;
import com.yourname.cbcautotarget.filter.TargetFilterData;
import com.yourname.cbcautotarget.menu.CommanderMenu;
import com.yourname.cbcautotarget.network.SyncCommanderDataPacket;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CommanderBlockEntity extends BlockEntity implements MenuProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("cbc_autotarget/Commander");

    private static final ConcurrentHashMap<
            net.minecraft.resources.ResourceKey<Level>,
            ConcurrentHashMap<BlockPos, CommanderBlockEntity>
            > COMMANDER_REGISTRY = new ConcurrentHashMap<>();

    public static final int COMMANDER_RADIUS = 20;
    private static final int SUBLEVEL_CACHE_INTERVAL = 40;

    private final TargetFilterData filterData = new TargetFilterData();

    /**
     * Флаг защиты данных от перезаписи при deploy схематики.
     * Create при размещении вызывает loadAdditional() дважды:
     *   1й раз — с корректным NBT из .nbt файла (FilterMask есть → флаг выставляется)
     *   2й раз — с дефолтным NBT (FilterMask отсутствует → без флага затёрло бы фильтр)
     * Флаг сбрасывается в onLoad() после завершения всей последовательности загрузок.
     */
    private boolean schematicDataLoaded = false;

    /**
     * Флаг защиты от перезаписи через handleUpdateTag при deploy схематики.
     * Create (через SafeNbtWriter или SchematicPrinter) может вызвать handleUpdateTag
     * уже ПОСЛЕ onLoad(), когда schematicDataLoaded сброшен.
     * Этот флаг живёт дольше: сбрасывается только когда handleUpdateTag вызван
     * с реальными данными (FilterMask присутствует), либо при следующей обычной загрузке.
     *
     * Сценарий деплоя:
     *   loadAdditional(nbtFromFile) → schematicDataLoaded=true, schematicNbtApplied=true
     *   loadAdditional(emptyNbt)    → пропускаем (schematicDataLoaded)
     *   onLoad()                    → schematicDataLoaded=false  (schematicNbtApplied остаётся true)
     *   handleUpdateTag(emptyTag)   → пропускаем (schematicNbtApplied)
     *   handleUpdateTag(realTag)    → применяем, schematicNbtApplied=false
     */
    private boolean schematicNbtApplied = false;

    @Nullable private ServerSubLevel commanderSubLevel = null;
    private int subLevelCacheTimer = SUBLEVEL_CACHE_INTERVAL;

    /**
     * Мировая позиция этого командера — конвертирует локальные координаты
     * SubLevel'а (Sable) в мировые, если командер физически стоит на корабле.
     * Иначе локальные и мировые координаты совпадают.
     *
     * Используется извне (например, MachineSoulBlockEntity) для сравнения
     * дистанций между сущностями/блоками, находящимися на РАЗНЫХ кораблях —
     * сравнивать их через getBlockPos() напрямую некорректно, так как это
     * координаты в разных локальных системах отсчёта.
     */
    public Vec3 getWorldPos() {
        return (commanderSubLevel != null)
                ? SableCompat.toWorldPos(commanderSubLevel, Vec3.atCenterOf(worldPosition))
                : Vec3.atCenterOf(worldPosition);
    }

    /**
     * Флаг активного состояния. Сохраняется в NBT, чтобы при пересоздании блока
     * Sable (hotswap при движении SubLevel) контроллеры оставались активными.
     */
    private boolean wasActive = false;

    /**
     * Уникальный UUID этого командера. Генерируется один раз при первом onLoad
     * и сохраняется в NBT. Передаётся контроллерам при активации, чтобы они
     * могли игнорировать команды деактивации от чужих командеров.
     */
    private UUID commanderUUID = null;

    /**
     * Генерирует случайную строку (4 символа: A-Z, 0-9) для предзаполнения
     * поля ключа альянса при установке блока игроком. Игрок может оставить
     * её как есть или заменить/очистить в GUI — это обычное значение поля
     * allianceKey, а не отдельный неизменяемый идентификатор.
     */
    private static final String RANDOM_KEY_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int    RANDOM_KEY_LENGTH    = 4;

    public static String generateRandomAllianceKey() {
        var rnd = java.util.concurrent.ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(RANDOM_KEY_LENGTH);
        for (int i = 0; i < RANDOM_KEY_LENGTH; i++)
            sb.append(RANDOM_KEY_ALPHABET.charAt(rnd.nextInt(RANDOM_KEY_ALPHABET.length())));
        return sb.toString();
    }

    /**
     * Флаг подавления deactivate при onRemove.
     * Выставляется в true перед setRemoved() когда блок в SubLevel (Sable hotswap),
     * чтобы не деактивировать контроллеры при временном удалении.
     */
    private boolean suppressDeactivate = false;

    @Nullable private UUID ownerUUID = null;

    private String allianceKey = "";

    public CommanderBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // ── Реестр ───────────────────────────────────────────────────────────────

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            COMMANDER_REGISTRY
                    .computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>())
                    .put(worldPosition, this);

            // Генерируем UUID если ещё не был создан (новый блок)
            if (commanderUUID == null) {
                commanderUUID = UUID.randomUUID();
                LOGGER.debug("[onLoad] Generated new commanderUUID={} at {}", commanderUUID, worldPosition);
                setChanged();
            }

            // Сбрасываем флаг защиты данных схематики.
            // К этому моменту все loadAdditional уже отработали,
            // правильные данные (загруженные первым loadAdditional) сохранены.
            // schematicNbtApplied НЕ сбрасываем здесь — он живёт до первого
            // handleUpdateTag, защищая от перезаписи пустым тегом SafeNbtWriter.
            schematicDataLoaded = false;

            if (level instanceof ServerLevel sl) {
                commanderSubLevel = SableCompat.getSubLevelForBlock(sl, worldPosition);
                subLevelCacheTimer = 0;

                // Sable hotswap: если блок был активен (двигался в SubLevel),
                // восстанавливаем активное состояние контроллеров.
                // При обычной загрузке мира — НЕ активируем, активация только через
                // редстоун-сигнал в CommanderBlock.neighborChanged.
                if (wasActive && commanderSubLevel != null) {
                    final ServerLevel captured = sl;
                    captured.getServer().execute(() -> {
                        if (!isRemoved() && level != null) {
                            LOGGER.debug("[onLoad] Sable hotswap reactivate at {}", worldPosition);
                            broadcastActivate();
                        }
                    });
                }
            }
        }
    }

    /**
     * Вызывается из SafeNbtWriter при deploy схематики.
     * SafeNbtWriter передаёт пустой tag для заполнения (направление BE→tag).
     * Нам заполнять нечего — данные уже загружены первым loadAdditional() и защищены флагом.
     * Метод оставлен для совместимости с регистрацией в SafeNbtWriterRegistry.
     */
    public void applySchematicTag(CompoundTag tag) {
        LOGGER.debug("[applySchematicTag] Called (no-op, data protected by schematicDataLoaded) at {}", worldPosition);
    }
    private void scheduleDelayed(ServerLevel sl, int delayTicks, Runnable task) {
        if (delayTicks <= 0) {
            sl.getServer().execute(task);
            return;
        }
        sl.getServer().execute(() -> scheduleDelayed(sl, delayTicks - 1, task));
    }
    /**
     * Планирует вызов broadcastActivate через 2 тика, давая контроллерам
     * время завершить собственный onLoad и попасть в SERVER_REGISTRY.
     */
    private void scheduleActivateBroadcast(ServerLevel sl) {
        // Используем MinecraftServer.execute() — это thread-safe очередь на следующий тик
        sl.getServer().execute(() ->
                sl.getServer().execute(() -> {
                    if (!isRemoved() && level != null) {
                        LOGGER.debug("[onLoad] Deferred broadcastActivate at {}", worldPosition);
                        broadcastActivate();
                    }
                })
        );
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) {
            ConcurrentHashMap<BlockPos, CommanderBlockEntity> map = COMMANDER_REGISTRY.get(level.dimension());
            if (map != null) {
                map.remove(worldPosition, this);
                LOGGER.debug("[Registry] Unregistered Commander at {} (dim={})", worldPosition, level.dimension().location());
            }
            // Если блок в Sable SubLevel — он будет пересоздан (hotswap).
            // Подавляем broadcastDeactivate чтобы контроллеры не выключались.
            if (commanderSubLevel != null) {
                suppressDeactivate = true;
                LOGGER.debug("[setRemoved] Suppressing deactivate (SubLevel hotswap) at {}", worldPosition);
            }
        }
        super.setRemoved();
    }

    // ── Владелец / ключ ───────────────────────────────────────────────────────

    @Nullable
    public UUID getOwnerUUID() { return ownerUUID; }

    public void setOwnerUUID(@Nullable UUID uuid) {
        this.ownerUUID = uuid;
        setChanged();
    }

    public String getAllianceKey() { return allianceKey; }

    /**
     * Устанавливает ключ альянса. Максимум 64 символа, пробелы по краям обрезаются.
     * Если передана пустая строка или null — ключ сбрасывается.
     */
    public void setAllianceKey(@Nullable String key) {
        this.allianceKey = (key == null) ? "" : key.strip();
        if (this.allianceKey.length() > 64) this.allianceKey = this.allianceKey.substring(0, 64);
        setChanged();
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    public static void serverTick(Level level, BlockPos pos, BlockState state, CommanderBlockEntity be) {
        be.tick(level);
    }

    private void tick(Level level) {
        if (!SableCompat.isAvailable()) return;
        if (!(level instanceof ServerLevel sl)) {
            if (subLevelCacheTimer == SUBLEVEL_CACHE_INTERVAL) {
                LOGGER.debug("[Tick] Level is NOT ServerLevel but {}, Sable SubLevel will not be cached. pos={}",
                        level.getClass().getSimpleName(), worldPosition);
            }
            return;
        }
        if (++subLevelCacheTimer >= SUBLEVEL_CACHE_INTERVAL) {
            subLevelCacheTimer = 0;
            ServerSubLevel prev = commanderSubLevel;
            commanderSubLevel = SableCompat.getSubLevelForBlock(sl, worldPosition);
            if (commanderSubLevel != prev) {
                LOGGER.debug("[Tick] SubLevel changed: {} -> {} at pos={}",
                        prev == null ? "null" : "SubLevel",
                        commanderSubLevel == null ? "null" : "SubLevel",
                        worldPosition);
            }
        }
    }

    // ── Broadcast ────────────────────────────────────────────────────────────

    public void broadcastActivate() {
        if (level == null || level.isClientSide) return;
        wasActive = true;
        setChanged();
        List<ControllerBlockEntity> controllers = findNearbyControllers();
        LOGGER.debug("[Activate] Found {} controllers for commander at {}", controllers.size(), worldPosition);
        for (ControllerBlockEntity ctrl : controllers) {
            LOGGER.debug("[Activate] -> sending activate to controller at {}", ctrl.getBlockPos());
            ctrl.applyFromCommander(filterData, true, worldPosition, commanderUUID);
        }
    }

    public void broadcastDeactivate() {
        if (level == null || level.isClientSide) return;
        wasActive = false;
        setChanged();
        List<ControllerBlockEntity> controllers = findNearbyControllers();
        LOGGER.debug("[Deactivate] Found {} controllers for commander at {}", controllers.size(), worldPosition);
        for (ControllerBlockEntity ctrl : controllers) {
            ctrl.applyFromCommander(filterData, false, worldPosition, commanderUUID);
        }
    }

    public void broadcastFilterUpdate() {
        if (level == null || level.isClientSide) return;
        List<ControllerBlockEntity> controllers = findNearbyControllers();
        LOGGER.debug("[FilterUpdate] Found {} controllers, mask={}", controllers.size(), Integer.toBinaryString(filterData.getMask()));
        for (ControllerBlockEntity ctrl : controllers) {
            ctrl.applyFromCommander(filterData, ctrl.isActive(), worldPosition, commanderUUID);
        }
    }

    public void onRemoved() {
        if (suppressDeactivate) {
            LOGGER.debug("[onRemoved] Suppressed deactivate at {}", worldPosition);
            return;
        }
        wasActive = false;
        broadcastDeactivate();
    }

    public boolean isSuppressingDeactivate() { return suppressDeactivate; }

    // ── Controller discovery (только контроллеры в радиусе) ──────────────────

    private List<ControllerBlockEntity> findNearbyControllers() {
        List<ControllerBlockEntity> result = new ArrayList<>();
        if (level == null || !(level instanceof ServerLevel sl)) return result;

        // Вычисляем мировые координаты командера:
        // если командер внутри SubLevel — конвертируем локальные -> мировые,
        // иначе локальные и мировые совпадают.
        Vec3 worldCenter = (commanderSubLevel != null)
                ? SableCompat.toWorldPos(commanderSubLevel,
                Vec3.atCenterOf(worldPosition))
                : Vec3.atCenterOf(worldPosition);
        double radiusSq = (double) COMMANDER_RADIUS * COMMANDER_RADIUS;

        LOGGER.debug("[Find] commanderSubLevel={} level={} worldCenter={}",
                commanderSubLevel == null ? "null" : "present",
                level.getClass().getSimpleName(),
                worldCenter);

        // 1. Контроллеры в основном уровне — поиск через глобальный реестр,
        //    без перебора блоков. Координаты реестра == мировые координаты.
        for (ControllerBlockEntity ctrl :
                ControllerBlockEntity.getControllersInDimension(sl.dimension())) {
            if (ctrl.isRemoved()) continue;
            Vec3 ctrlWorld =
                    Vec3.atCenterOf(ctrl.getBlockPos());
            if (ctrlWorld.distanceToSqr(worldCenter) <= radiusSq) result.add(ctrl);
        }
        if (SableCompat.isAvailable()) {
            dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container =
                    dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(sl);
            if (container != null) {
                for (ServerSubLevel ssl :
                        container.getAllSubLevels()) {
                    if (ssl.isRemoved()) continue;
                    for (ControllerBlockEntity ctrl :
                            ControllerBlockEntity.getControllersInDimension(
                                    ssl.getLevel().dimension())) {
                        if (ctrl.isRemoved()) continue;
                        Vec3 ctrlWorld = SableCompat.toWorldPos(
                                ssl,
                                Vec3.atCenterOf(ctrl.getBlockPos()));
                        if (ctrlWorld.distanceToSqr(worldCenter) <= radiusSq)
                            result.add(ctrl);
                    }
                }
            }
        }

        try {
            List<com.simibubi.create.content.contraptions.AbstractContraptionEntity> contraptionEntities =
                    sl.getEntitiesOfClass(
                            com.simibubi.create.content.contraptions.AbstractContraptionEntity.class,
                            AABB.ofSize(worldCenter, COMMANDER_RADIUS * 2 + 2, COMMANDER_RADIUS * 2 + 2, COMMANDER_RADIUS * 2 + 2)
                    );
            for (com.simibubi.create.content.contraptions.AbstractContraptionEntity ace : contraptionEntities) {
                Level clevel = ace.level();
                if (clevel == null || clevel == sl) continue;
                for (ControllerBlockEntity ctrl :
                        ControllerBlockEntity.getControllersInDimension(clevel.dimension())) {
                    if (ctrl.isRemoved()) continue;
                    if (result.contains(ctrl)) continue;
                    // Конвертируем локальную позицию контроллера в мировую через entity
                    Vec3 localVec =
                            Vec3.atCenterOf(ctrl.getBlockPos());
                    Vec3 ctrlWorld = ace.toGlobalVector(localVec, 1.0f);
                    if (ctrlWorld.distanceToSqr(worldCenter) <= radiusSq) {
                        result.add(ctrl);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[Find] Contraption scan failed: {}", e.getMessage());
        }

        LOGGER.debug("[Find] Registry-based search: found={} worldCenter={}", result.size(), worldCenter);
        return result;
    }
    /**
     * Результат поиска: командер + его точная мировая позиция на момент поиска.
     * Нужен потому, что cmd.getBlockPos() возвращает ЛОКАЛЬНЫЕ координаты
     * (в системе отсчёта корабля/контрапшена, на котором стоит командер) —
     * сравнивать их напрямую с координатами вызывающего блока (который может
     * стоять на ДРУГОМ корабле) некорректно. worldPos уже сконвертирована.
     */
    public static final class CommanderHit {
        public final CommanderBlockEntity commander;
        public final Vec3 worldPos;
        public CommanderHit(CommanderBlockEntity commander, Vec3 worldPos) {
            this.commander = commander;
            this.worldPos  = worldPos;
        }
    }

    public static java.util.Collection<CommanderBlockEntity> getCommandersInDimension(
            net.minecraft.resources.ResourceKey<Level> dim) {
        ConcurrentHashMap<BlockPos, CommanderBlockEntity> map = COMMANDER_REGISTRY.get(dim);
        return map != null ? map.values() : java.util.Collections.emptyList();
    }

    /**
     * @param origin      локальные координаты точки отсчёта В СИСТЕМЕ searchLevel
     *                     (используются для поиска командеров в главном мире,
     *                     где локальные и мировые координаты совпадают)
     * @param worldOrigin МИРОВАЯ позиция той же точки отсчёта — обязательна для
     *                     корректного сравнения дистанций с командерами на ДРУГИХ
     *                     кораблях/SubLevel'ах. Если вызывающий блок сам стоит на
     *                     корабле, origin (локальный) и worldOrigin (мировой) —
     *                     РАЗНЫЕ точки, и передавать одно вместо другого нельзя.
     */
    /**
     * @param origin       локальные координаты точки отсчёта В СИСТЕМЕ searchLevel
     *                      (используются для getBlockEntity и т.п. локальных операций)
     * @param worldOrigin  МИРОВАЯ позиция той же точки отсчёта — обязательна для
     *                      корректного сравнения дистанций с командерами на ДРУГИХ
     *                      кораблях/SubLevel'ах.
     * @param selfSubLevel SubLevel, на котором физически стоит ВЫЗЫВАЮЩИЙ блок,
     *                      если он стоит на корабле — иначе null. ЯВНО передаётся
     *                      вызывающей стороной (у неё это значение уже закешировано,
     *                      см. MachineSoulBlockEntity.cachedSubLevel,
     *                      ControllerBlockEntity.controllerSubLevel), а не
     *                      угадывается внутри метода — попытка угадать это по
     *                      расхождению origin/worldOrigin (координаты почти
     *                      совпадают ⇒ не на корабле) ЛОМАЛАСЬ именно тогда, когда
     *                      она была нужнее всего: если вызывающий блок САМ на
     *                      корабле, origin и worldOrigin ВСЕГДА расходятся, даже
     *                      для командеров, реально стоящих в главном мире — и
     *                      применение "сдвига корабля" к их (уже верным, мировым)
     *                      координатам уводило их далеко за пределы радиуса.
     */
    public static List<CommanderHit> findCommandersInRadius(
            Level searchLevel, BlockPos origin, Vec3 worldOrigin, int radius,
            @Nullable ServerSubLevel selfSubLevel) {
        List<CommanderHit> result = new ArrayList<>();
        if (!(searchLevel instanceof ServerLevel sl)) return result;

        // ИСПРАВЛЕНО (раунд 1): раньше этот метод искал командеров ТОЛЬКО в
        // COMMANDER_REGISTRY.get(searchLevel.dimension()) — то есть только
        // среди командеров, зарегистрированных в буквально том же измерении,
        // что и searchLevel. Но CommanderBlockEntity.onLoad() регистрирует
        // командера по dimension() ЕГО СОБСТВЕННОГО level — а если командер
        // физически стоит на корабле Sable/Create Contraption, это dimension
        // конкретно ЭТОГО корабля (свой SubLevel), а не главный мир.
        //
        // ИСПРАВЛЕНО (раунд 2): первая версия фикса добавила обход всех
        // SubLevel'ов и конвертацию координат КАЖДОГО НАЙДЕННОГО командера
        // через toWorldPos — но точку ОТСЧЁТА (origin) по-прежнему сравнивала
        // как есть, БЕЗ конвертации.
        //
        // ИСПРАВЛЕНО (раунд 3): вторая версия пыталась угадать конвертацию
        // через эвристику "origin отличается от worldOrigin ⇒ применить их
        // разницу как сдвиг ко всем координатам ветки 1". Это ломало ровно
        // противоположный случай: вызывающий блок на своём корабле ищет
        // командеров, реально стоящих в ГЛАВНОМ МИРЕ (не на корабле) —
        // их координаты уже мировые и сдвигать их нельзя. Из-за эвристики
        // они сдвигались "в никуда" и переставали проходить проверку
        // радиуса, хотя Controller (использующий отдельный, независимый от
        // этого метода путь для командеров на своём корабле — см.
        // scanForCommanderTargets) их прекрасно находил. Теперь вызывающая
        // сторона передаёт selfSubLevel явно (уже know из своего кэша) —
        // никакого угадывания.
        double radiusSq = (double) radius * radius;
        java.util.Set<CommanderBlockEntity> seen = new java.util.HashSet<>();

        // 1. Командеры в том же уровне, что и searchLevel. Если вызывающий
        //    блок сам на корабле (selfSubLevel != null), координаты этих
        //    командеров ЛОКАЛЬНЫЕ для этого корабля — конвертируем. Если
        //    вызывающий блок в главном мире (selfSubLevel == null), это
        //    и есть главный мир — координаты уже мировые.
        for (CommanderBlockEntity cmd : getCommandersInDimension(sl.dimension())) {
            if (cmd.isRemoved()) continue;
            Vec3 cmdWorld = (selfSubLevel != null)
                    ? SableCompat.toWorldPos(selfSubLevel, Vec3.atCenterOf(cmd.getBlockPos()))
                    : Vec3.atCenterOf(cmd.getBlockPos());
            if (cmdWorld.distanceToSqr(worldOrigin) <= radiusSq && seen.add(cmd))
                result.add(new CommanderHit(cmd, cmdWorld));
        }

        // 2. Командеры на Sable SubLevel'ах — конвертируем через toWorldPos
        if (SableCompat.isAvailable()) {
            dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container =
                    dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(sl);
            if (container != null) {
                for (ServerSubLevel ssl : container.getAllSubLevels()) {
                    if (ssl.isRemoved()) continue;
                    for (CommanderBlockEntity cmd :
                            getCommandersInDimension(ssl.getLevel().dimension())) {
                        if (cmd.isRemoved() || seen.contains(cmd)) continue;
                        Vec3 cmdWorld = SableCompat.toWorldPos(
                                ssl, Vec3.atCenterOf(cmd.getBlockPos()));
                        if (cmdWorld.distanceToSqr(worldOrigin) <= radiusSq && seen.add(cmd))
                            result.add(new CommanderHit(cmd, cmdWorld));
                    }
                }
            }
        }

        // 3. Командеры на обычных Create-контрапшенах (составы/платформы) —
        //    конвертируем через AbstractContraptionEntity.toGlobalVector
        try {
            List<com.simibubi.create.content.contraptions.AbstractContraptionEntity> contraptionEntities =
                    sl.getEntitiesOfClass(
                            com.simibubi.create.content.contraptions.AbstractContraptionEntity.class,
                            AABB.ofSize(worldOrigin, radius * 2 + 2, radius * 2 + 2, radius * 2 + 2));
            for (com.simibubi.create.content.contraptions.AbstractContraptionEntity ace : contraptionEntities) {
                Level clevel = ace.level();
                if (clevel == null || clevel == sl) continue;
                for (CommanderBlockEntity cmd : getCommandersInDimension(clevel.dimension())) {
                    if (cmd.isRemoved() || seen.contains(cmd)) continue;
                    Vec3 localVec = Vec3.atCenterOf(cmd.getBlockPos());
                    Vec3 cmdWorld = ace.toGlobalVector(localVec, 1.0f);
                    if (cmdWorld.distanceToSqr(worldOrigin) <= radiusSq && seen.add(cmd))
                        result.add(new CommanderHit(cmd, cmdWorld));
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[FindCommanders] Contraption scan failed: {}", e.getMessage());
        }

        LOGGER.debug("[FindCommanders] found={} worldOrigin={} radius={}", result.size(), worldOrigin, radius);
        return result;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.cbc_autotarget.commander");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory playerInv, Player player) {
        if (player instanceof ServerPlayer sp) {
            // Синхронизируем полное состояние (filterMask + вайтлист + ключ альянса) при открытии GUI
            PacketDistributor.sendToPlayer(sp, new SyncCommanderDataPacket(
                    worldPosition,
                    filterData.getMask(),
                    filterData.isWhitelistEnabled(),
                    new ArrayList<>(filterData.getWhitelist()),
                    allianceKey
            ));
        }
        return new CommanderMenu(id, playerInv, this);
    }

    public TargetFilterData getFilterData() { return filterData; }
    public int getFilterMask() { return filterData.getMask(); }
    public void setFilterMask(int mask) {
        LOGGER.debug("[setFilterMask] {} -> {} at {}", Integer.toBinaryString(filterData.getMask()), Integer.toBinaryString(mask), worldPosition);
        filterData.setMask(mask);
        broadcastFilterUpdate();
        setChanged();
    }
    /** Только для клиентского dummy — без side-эффектов. */
    public void setFilterMaskClient(int mask) { filterData.setMask(mask); }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        filterData.saveToNBT(tag);
        tag.putString("AllianceKey", allianceKey);
        tag.putBoolean("WasActive", wasActive);
        if (ownerUUID != null) tag.putUUID("OwnerUUID", ownerUUID);
        if (commanderUUID != null) tag.putUUID("CommanderUUID", commanderUUID);
        LOGGER.debug("[Save] mask={} allianceKey='{}' at {}", Integer.toBinaryString(filterData.getMask()), allianceKey, worldPosition);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("FilterMask")) {
            // Тег содержит FilterMask — это либо обычная загрузка мира, либо первый
            // loadAdditional при deploy схематики с корректным NBT.
            // Выставляем оба флага защиты: следующий loadAdditional без FilterMask не
            // затрёт данные, и handleUpdateTag с пустым тегом тоже не затрёт.
            filterData.loadFromNBT(tag);
            schematicDataLoaded = true;
            schematicNbtApplied = true;
        } else if (schematicDataLoaded) {
            // FilterMask отсутствует, но флаг выставлен — это второй loadAdditional при deploy
            // схематики с дефолтным/пустым NBT. Пропускаем перезапись фильтра.
            LOGGER.debug("[Load] Skipped overwrite (schematicDataLoaded) mask={} at {}",
                    Integer.toBinaryString(filterData.getMask()), worldPosition);
            // AllianceKey, WasActive, OwnerUUID тоже не перезаписываем — они уже корректны
            return;
        } else {
            // Обычная загрузка без FilterMask — сбрасываем оба флага.
            schematicNbtApplied = false;
        }
        allianceKey = tag.contains("AllianceKey") ? tag.getString("AllianceKey") : "";
        wasActive   = tag.contains("WasActive")   && tag.getBoolean("WasActive");
        ownerUUID   = tag.hasUUID("OwnerUUID")    ? tag.getUUID("OwnerUUID")     : null;
        commanderUUID = tag.hasUUID("CommanderUUID") ? tag.getUUID("CommanderUUID") : null;
        LOGGER.debug("[Load] mask={} allianceKey='{}' at {}", Integer.toBinaryString(filterData.getMask()), allianceKey, worldPosition);
    }

    /**
     * Возвращает NBT тег для клиентской синхронизации и размещения из структур (.nbt).
     * WasActive намеренно НЕ включён: при размещении структуры блок должен
     * стартовать неактивным — активацию должен дать редстоун или MachineSoul.
     * Это предотвращает применение дефолтных фильтров поверх сохранённых.
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        filterData.saveToNBT(tag);
        tag.putString("AllianceKey", allianceKey);
        tag.putBoolean("WasActive", false); // при размещении структуры всегда неактивен
        if (ownerUUID != null) tag.putUUID("OwnerUUID", ownerUUID);
        return tag;
    }

    /**
     * Вызывается клиентом при получении пакета синхронизации,
     * а также Create Schematic при размещении структуры на сервере.
     * Применяем фильтры из тега чтобы они не сбрасывались к дефолту.
     *
     * Если schematicNbtApplied=true — данные уже корректно загружены из loadAdditional,
     * SafeNbtWriter может прислать тег с актуальными данными BE (они правильные),
     * но мы всё равно их применяем, т.к. они идентичны текущим. Флаг сбрасываем.
     */
    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        if (schematicNbtApplied) {
            // Данные уже загружены из схематики через loadAdditional.
            // handleUpdateTag при deploy приходит с тем что SafeNbtWriter записал в tag —
            // то есть с текущим состоянием BE (правильным). Но если tag пустой или дефолтный
            // (FilterMask=ALL_MASK без реальных изменений) — лучше не трогать.
            // Пропускаем и сбрасываем флаг.
            LOGGER.debug("[handleUpdateTag] Skipped (schematicNbtApplied) mask={} at {}",
                    Integer.toBinaryString(filterData.getMask()), worldPosition);
            schematicNbtApplied = false;
            return;
        }
        if (tag.contains("FilterMask")) {
            filterData.loadFromNBT(tag);
        }
        if (tag.contains("AllianceKey")) {
            allianceKey = tag.getString("AllianceKey");
        }
        // WasActive намеренно НЕ восстанавливаем при handleUpdateTag —
        // активация только через редстоун или MachineSoul
        if (tag.contains("OwnerUUID")) {
            ownerUUID = tag.getUUID("OwnerUUID");
        }
        LOGGER.debug("[handleUpdateTag] Loaded mask={} at {}",
                Integer.toBinaryString(filterData.getMask()), worldPosition);
    }
}