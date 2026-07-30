package com.yourname.cbcautotarget;

import net.neoforged.neoforge.common.ModConfigSpec;

public class CBCAutoTargetConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue MAX_SIM_TICKS;
    public static final ModConfigSpec.DoubleValue MUZZLE_SPEED_BLOCKS_PER_TICK;
    public static final ModConfigSpec.DoubleValue DEFAULT_GRAVITY;
    public static final ModConfigSpec.DoubleValue DEFAULT_DRAG;
    /**
     * Длина ствола в блоках от центра CannonMount до конца дула.
     * Используется для точного вычисления позиции выстрела.
     * Если ствол 3 блока — ставь 1.5 (половина длины от mount-центра).
     * Если ствол 5 блоков — ставь 2.5 и т.д.
     * Значение по умолчанию 0.0 = считать от центра mount (старое поведение).
     */
    public static final ModConfigSpec.DoubleValue BARREL_LENGTH;
    /**
     * Максимальное количество кандидатов, для которых выполняется raycast (LOS-проверка).
     * Кандидаты уже отсортированы по расстоянию, поэтому ограничение не меняет логику
     * выбора цели в типичных сценариях (ближайший с прямой видимостью), но резко снижает
     * нагрузку на серверный тик при большом скоплении сущностей в радиусе скана.
     * Каждый raycast = 3 трассировки луча через блоки → 5 кандидатов = максимум 15 лучей.
     */
    public static final ModConfigSpec.IntValue MAX_RAYCAST_CANDIDATES;

    static {
        BUILDER.push("targeting");
        SCAN_INTERVAL_TICKS     = BUILDER.defineInRange("scan_interval_ticks", 20, 1, 200);
        MAX_SIM_TICKS           = BUILDER.defineInRange("max_sim_ticks", 400, 50, 2000);
        MUZZLE_SPEED_BLOCKS_PER_TICK = BUILDER.defineInRange("muzzle_speed_blocks_per_tick", 10.0, 0.5, 100.0);
        DEFAULT_GRAVITY         = BUILDER.defineInRange("default_gravity", -0.05, -1.0, 0.0);
        DEFAULT_DRAG            = BUILDER.defineInRange("default_drag", 0.0, 0.0, 1.0);
        BARREL_LENGTH           = BUILDER.defineInRange("barrel_length", 0.0, 0.0, 32.0);
        MAX_RAYCAST_CANDIDATES  = BUILDER.defineInRange("max_raycast_candidates", 9, 1, 50);
        BUILDER.pop();
        SPEC = BUILDER.build();
    }
}