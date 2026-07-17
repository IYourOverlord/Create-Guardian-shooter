package com.yourname.cbcautotarget.filter;

public enum TargetCategory {

    HOSTILE(0, "gui.cbc_autotarget.filter.hostile"),
    PASSIVE(1, "gui.cbc_autotarget.filter.passive"),
    PLAYERS(2, "gui.cbc_autotarget.filter.players"),
    ENEMY_COMMANDERS(3, "gui.cbc_autotarget.filter.enemy_commanders");

    public final int    bit;
    public final String translationKey;

    TargetCategory(int bit, String translationKey) {
        this.bit            = bit;
        this.translationKey = translationKey;
    }

    public int mask() { return 1 << bit; }

    public static final int ALL_MASK = (1 << values().length) - 1;
}
