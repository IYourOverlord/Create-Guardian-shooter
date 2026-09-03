package com.yourname.cbcautotarget.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * API для взаимодействия сторонних модов с блоком «Командер» (Commander).
 * <p>
 * Получение экземпляра:
 * <pre>{@code
 * ICommander commander = CbcAutotargetAPI.getCommander(level, pos);
 * if (commander != null) { commander.activate(); }
 * }</pre>
 */
public interface ICommander {

    // ── Активация / деактивация ──────────────────────────────────────────────

    /**
     * Рассылает сигнал активации всем контроллерам в радиусе.
     * Эквивалентно подаче редстоун-сигнала на блок.
     * Вызывать только на серверной стороне.
     */
    void activate();

    /**
     * Рассылает сигнал деактивации всем контроллерам в радиусе.
     * Эквивалентно снятию редстоун-сигнала с блока.
     * Вызывать только на серверной стороне.
     */
    void deactivate();

    /**
     * Рассылает обновление фильтра всем подключённым контроллерам
     * без изменения состояния активации.
     */
    void broadcastFilterUpdate();

    // ── Владелец и альянс ────────────────────────────────────────────────────

    /** UUID игрока-владельца командера, или null если не задан. */
    @Nullable UUID getOwnerUUID();

    /**
     * Устанавливает UUID владельца.
     * Влияет на логику союзников: контроллеры не атакуют союзников владельца.
     */
    void setOwnerUUID(@Nullable UUID uuid);

    /**
     * Ключ альянса — строка, которую разделяют союзные командеры.
     * Контроллеры одного альянса не атакуют друг друга.
     */
    String getAllianceKey();

    /**
     * Устанавливает ключ альянса. Передайте null или пустую строку чтобы сбросить.
     * После изменения рекомендуется вызвать {@link #broadcastFilterUpdate()}.
     */
    void setAllianceKey(@Nullable String key);

    // ── Маска фильтра целей ──────────────────────────────────────────────────

    /**
     * Возвращает текущую битовую маску фильтра целей.
     * Биты соответствуют категориям из {@code TargetCategory}.
     */
    int getFilterMask();

    /**
     * Устанавливает битовую маску фильтра целей.
     * После изменения рекомендуется вызвать {@link #broadcastFilterUpdate()}.
     */
    void setFilterMask(int mask);

    // ── Позиция ──────────────────────────────────────────────────────────────

    /** Позиция блока в уровне. */
    BlockPos getBlockPos();

    /** Уровень в котором находится блок. */
    Level getLevel();
}
