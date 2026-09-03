package com.yourname.cbcautotarget.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * API для взаимодействия сторонних модов с блоком «Душа машины» (MachineSoul).
 * <p>
 * Получение экземпляра:
 * <pre>{@code
 * IMachineSoul soul = CbcAutotargetAPI.getMachineSoul(level, pos);
 * if (soul != null) { soul.setActive(true); }
 * }</pre>
 */
public interface IMachineSoul {

    // ── Активация ────────────────────────────────────────────────────────────

    /** Возвращает true если блок сейчас активен (ищет и атакует цели). */
    boolean isActive();

    /**
     * Включает или выключает блок.
     * Эквивалентно нажатию кнопки в GUI или редстоун-сигналу.
     * Вызывать только на серверной стороне.
     */
    void setActive(boolean active);

    // ── Радиус обнаружения ───────────────────────────────────────────────────

    /** Текущий радиус обнаружения целей (блоки). */
    int getDetectionRadius();

    /**
     * Устанавливает радиус обнаружения.
     * Значение зажимается в [{@code MIN_DETECTION_RADIUS}, {@code MAX_DETECTION_RADIUS}].
     */
    void setDetectionRadius(int radius);

    // ── Дистанция удержания ──────────────────────────────────────────────────

    /** Дистанция на которой корабль останавливается рядом с целью (0 = выключено). */
    int getKeepDistance();

    /** Устанавливает дистанцию удержания. */
    void setKeepDistance(int distance);

    // ── Прицеливание на игроков ──────────────────────────────────────────────

    /** true если блок может атаковать игроков. */
    boolean isTargetPlayers();

    /** Включает или выключает атаку на игроков. */
    void setTargetPlayers(boolean enabled);

    // ── Требование sublevel ──────────────────────────────────────────────────

    /**
     * true если блок активен только когда находится на физическом sublevel-корабле
     * (т. е. игнорирует себя если корабль стоит на месте без физики).
     */
    boolean isRequireSubLevel();

    /** Устанавливает требование sublevel. */
    void setRequireSubLevel(boolean require);

    // ── Позиция ──────────────────────────────────────────────────────────────

    /** Позиция блока в уровне. */
    BlockPos getBlockPos();

    /** Уровень в котором находится блок. */
    Level getLevel();
}
