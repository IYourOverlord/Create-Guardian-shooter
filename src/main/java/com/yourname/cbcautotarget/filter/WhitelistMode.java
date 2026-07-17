package com.yourname.cbcautotarget.filter;

/**
 * Режим поведения вайтлиста в MachineSoul (вкладка Target → Player Filter).
 *
 * TARGET  — атаковать ТОЛЬКО тех кто в списке (прежнее поведение при enabled=true).
 * IGNORE  — игнорировать тех кто в списке; атаковать всех остальных.
 * FOLLOW  — следовать за первым игроком из списка кто в радиусе;
 *           если рядом появляется ВРАЖДЕБНАЯ цель (не из списка) — немедленно
 *           переключиться на неё и атаковать по всем правилам Vision-вкладки.
 *           Когда враг уничтожен/вышел из зоны — вернуться к сопровождению.
 */
public enum WhitelistMode {
    TARGET,   // атаковать только из списка
    IGNORE,   // игнорировать из списка
    FOLLOW;   // следовать за кем-то из списка, но атаковать врагов вне списка

    private static final WhitelistMode[] VALUES = values();

    public static WhitelistMode fromId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : TARGET;
    }

    public int id() { return ordinal(); }
}
