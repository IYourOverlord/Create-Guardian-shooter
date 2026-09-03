package com.yourname.cbcautotarget.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Точка входа в публичный API мода CBC Autotarget.
 * <p>
 * Зависимость в build.gradle стороннего мода:
 * <pre>
 * dependencies {
 *     compileOnly "com.yourname:cbc-autotarget:VERSION"
 * }
 * </pre>
 *
 * Проверяй наличие мода перед вызовом:
 * <pre>{@code
 * if (ModList.get().isLoaded("cbc_autotarget")) {
 *     IMachineSoul soul = CbcAutotargetAPI.getMachineSoul(level, pos);
 *     if (soul != null) soul.setActive(true);
 * }
 * }</pre>
 */
public final class CbcAutotargetAPI {

    private CbcAutotargetAPI() {}

    /**
     * Возвращает {@link IMachineSoul} для блока по позиции,
     * или null если по этой позиции нет блока «Душа машины».
     * <p>
     * Вызывать только на серверной стороне.
     */
    @Nullable
    public static IMachineSoul getMachineSoul(Level level, BlockPos pos) {
        if (level == null || pos == null) return null;
        var be = level.getBlockEntity(pos);
        if (be instanceof com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity soul) {
            return new MachineSoulAdapter(soul);
        }
        return null;
    }

    /**
     * Возвращает {@link ICommander} для блока по позиции,
     * или null если по этой позиции нет блока «Командер».
     * <p>
     * Вызывать только на серверной стороне.
     */
    @Nullable
    public static ICommander getCommander(Level level, BlockPos pos) {
        if (level == null || pos == null) return null;
        var be = level.getBlockEntity(pos);
        if (be instanceof com.yourname.cbcautotarget.blockentity.CommanderBlockEntity commander) {
            return new CommanderAdapter(commander);
        }
        return null;
    }
}
