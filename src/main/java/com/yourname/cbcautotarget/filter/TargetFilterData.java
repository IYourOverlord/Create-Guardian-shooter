package com.yourname.cbcautotarget.filter;

import com.yourname.cbcautotarget.CBCAutoTargetTags;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public class TargetFilterData {

    private static final Logger LOGGER = LoggerFactory.getLogger("cbc_autotarget/TargetFilterData");

    private static final String NBT_MASK       = "FilterMask";
    private static final String NBT_WL_ENABLED = "WhitelistEnabled";
    private static final String NBT_WL_LIST    = "PlayerWhitelist";

    public static final int MAX_WHITELIST_SIZE = 50;

    private int mask;
    private boolean whitelistEnabled = false;
    private final LinkedHashSet<String> playerWhitelist = new LinkedHashSet<>();

    public TargetFilterData()        { this.mask = TargetCategory.ALL_MASK; }
    public TargetFilterData(int mask){ this.mask = mask & TargetCategory.ALL_MASK; }

    // ── Маска ────────────────────────────────────────────────────────────────
    public boolean isEnabled(TargetCategory cat) { return (mask & cat.mask()) != 0; }
    public void    setEnabled(TargetCategory cat, boolean e) { if(e) mask|=cat.mask(); else mask&=~cat.mask(); }
    public int     getMask()           { return mask; }
    public void    setMask(int m)      { this.mask = m & TargetCategory.ALL_MASK; }

    // ── Whitelist ─────────────────────────────────────────────────────────────
    public boolean isWhitelistEnabled()           { return whitelistEnabled; }
    public void    setWhitelistEnabled(boolean e) { this.whitelistEnabled = e; }

    public boolean addToWhitelist(String name) {
        String t = name.trim();
        if (t.isEmpty() || t.length() > 16) return false;
        if (playerWhitelist.size() >= MAX_WHITELIST_SIZE) return false;
        return playerWhitelist.add(t);
    }

    public boolean removeFromWhitelist(String name) { return playerWhitelist.remove(name.trim()); }

    public void replaceWhitelist(java.util.Collection<String> names) {
        playerWhitelist.clear();
        for (String n : names) {
            String t = n.trim();
            if (!t.isEmpty() && t.length() <= 16 && playerWhitelist.size() < MAX_WHITELIST_SIZE)
                playerWhitelist.add(t);
        }
    }

    public Set<String> getWhitelist() { return Collections.unmodifiableSet(playerWhitelist); }

    // ── Проверка Entity ───────────────────────────────────────────────────────
    public boolean isAllowed(Entity entity) {
        // ENEMY_CANNONS убраны — PitchOrientedContraptionEntity больше не цель
        if (entity instanceof Player player) {
            if (player.isSpectator()) return false;
            if (player.isCreative()) return false;
            if (!isEnabled(TargetCategory.PLAYERS)) return false;
            if (whitelistEnabled) return !playerWhitelist.contains(player.getGameProfile().getName());
            return true;
        }
        // ── Нейтральные мобы (голем, пиглины, зомбифицированный пиглин и т.п.) ─
        // Эти мобы часто зарегистрированы с MobCategory.MONSTER или наследуют
        // Monster, но по геймплею атакуют только в ответ на провокацию.
        // Если такой моб сейчас реально не агрессивен (нет цели атаки / не
        // "зол"), считаем его мирным, а не враждебным.
        if (entity.getType().is(CBCAutoTargetTags.NEUTRAL_ENTITIES)) {
            boolean actuallyAggressive = isActuallyAggressive(entity);
            return isEnabled(actuallyAggressive ? TargetCategory.HOSTILE : TargetCategory.PASSIVE);
        }

        // ── Враждебные ───────────────────────────────────────────────────────
        // Monster покрывает большинство ванильных мобов. MobCategory.MONSTER —
        // более широкий признак враждебности на уровне EntityType: многие мобы
        // из других модов регистрируют свой EntityType именно с этой категорией,
        // даже если их Java-класс не наследует Monster напрямую (используют
        // свои базовые классы вроде PathfinderMob с кастомным AI). Тег даёт
        // возможность вручную доопределить сущности, не покрытые ни тем, ни другим.
        MobCategory typeCategory = entity.getType().getCategory();
        if (entity instanceof Monster
                || typeCategory == MobCategory.MONSTER
                || entity.getType().is(CBCAutoTargetTags.TARGETED_ENTITIES))
            return isEnabled(TargetCategory.HOSTILE);

        // ── Явно мирные ──────────────────────────────────────────────────────
        if (entity instanceof Animal || entity instanceof AbstractVillager)
            return isEnabled(TargetCategory.PASSIVE);

        // MobCategory для оставшихся мирных типов.
        if (typeCategory == MobCategory.CREATURE || typeCategory == MobCategory.AMBIENT
                || typeCategory == MobCategory.WATER_CREATURE || typeCategory == MobCategory.WATER_AMBIENT
                || typeCategory == MobCategory.UNDERGROUND_WATER_CREATURE)
            return isEnabled(TargetCategory.PASSIVE);

        // ── Fallback для прочих LivingEntity (в основном модовые мобы с MISC) ─
        // Ничего из вышеперечисленного не подошло — обычно потому что модовый
        // моб зарегистрирован с MobCategory.MISC (значение по умолчанию).
        // Раньше такие сущности проваливались в "return false" и турели их
        // полностью игнорировали. Классифицируем эвристически по атрибуту
        // урона атаки: если у моба есть ATTACK_DAMAGE > 0 — считаем враждебным,
        // иначе — мирным.
        if (entity instanceof LivingEntity living) {
            AttributeInstance attackAttr = living.getAttribute(Attributes.ATTACK_DAMAGE);
            boolean looksHostile = attackAttr != null && attackAttr.getBaseValue() > 0.0;
            return isEnabled(looksHostile ? TargetCategory.HOSTILE : TargetCategory.PASSIVE);
        }

        return false;
    }

    /**
     * Определяет, находится ли нейтральный по умолчанию моб (голем, пиглин и т.д.)
     * прямо сейчас в состоянии реальной агрессии, а не просто существует рядом.
     *
     * Порядок проверки:
     *  1. {@link NeutralMob#isAngry()} — официальный ванильный признак "гнева"
     *     (голем, пчела, полярный медведь, зомбифицированный пиглин, эндермен,
     *     волк). Самый надёжный вариант, если моб реализует этот интерфейс.
     *  2. Иначе, если моб — {@link Mob}, проверяем {@link Mob#getTarget()}:
     *     наличие живой цели атаки означает, что моб уже в бою (актуально для
     *     обычного Piglin, который не реализует NeutralMob, но заводит цель
     *     через свой AI при провокации).
     *  3. Если ни то, ни другое не применимо — считаем моба мирным (безопасный
     *     дефолт: лучше не выстрелить по спокойному голему, чем открыть огонь
     *     по мобу, который ни на кого не нападает).
     */
    private boolean isActuallyAggressive(Entity entity) {
        if (entity instanceof NeutralMob neutralMob) {
            return neutralMob.isAngry();
        }
        if (entity instanceof Mob mob) {
            LivingEntity target = mob.getTarget();
            return target != null && target.isAlive();
        }
        return false;
    }

    /**
     * Радиус проверки "рядом с союзником" в блоках.
     * Цель считается прикрытой союзником если тот находится ближе этого расстояния.
     *
     * Уменьшено с 3.5 до 1.5: при 3.5 блока пушка теряла возможность
     * наводиться на враждебных мобов практически при любом ближнем бое
     * игрока с ними (обычная дистанция удара — 2-3 блока), что выглядело
     * как "цель стоит перед пушкой, а она не стреляет". 1.5 блока — это
     * уже действительно вплотную (реальный риск задеть игрока), а не
     * весь радиус типичного ближнего боя.
     */
    private static final double ALLY_PROXIMITY_RADIUS = 1.5;

    /**
     * Возвращает true если рядом с целью ({@code target}) находится союзная сущность,
     * из-за которой стрелять опасно.
     *
     * Союзными считаются:
     *  1. Игроки из вайтлиста (если вайтлист включён).
     *  2. Любые игроки не в спектаторе (если PLAYERS-категория выключена —
     *     значит игроков атаковать не хотим, значит они «свои»).
     *
     * Вызывается из scanForTarget перед добавлением кандидата в список целей.
     *
     * @param target      потенциальная цель
     * @param searchLevel уровень для поиска соседних сущностей
     * @return true — пропустить цель (рядом союзник)
     */
    public boolean isNearAlly(Entity target, Level searchLevel) {
        AABB proximityBox = target.getBoundingBox().inflate(ALLY_PROXIMITY_RADIUS);

        for (LivingEntity nearby : searchLevel.getEntitiesOfClass(
                LivingEntity.class, proximityBox,
                e -> e.isAlive() && e != target)) {

            if (nearby instanceof Player player) {
                if (player.isSpectator()) continue;
                // Если вайтлист включён — союзники только те кто в нём
                if (whitelistEnabled) {
                    if (playerWhitelist.contains(player.getGameProfile().getName())) return true;
                } else {
                    // Вайтлист выключен: если PLAYERS-категория отключена,
                    // значит игроки — свои, и цель рядом с ними трогать не нужно
                    if (!isEnabled(TargetCategory.PLAYERS)) return true;
                }
            }
        }
        return false;
    }

    /**
     *
     * Новая логика (только ключ, UUID владельца игнорируется):
     *  - Оба ключа непусты И совпадают → союзник (не атаковать).
     *  - В любом другом случае → враг (атаковать).
     *
     * Примеры:
     *  myKey="A", theirKey="A"  → союзник
     *  myKey="A", theirKey="B"  → враг  (разные ключи — даже один игрок)
     *  myKey="",  theirKey="A"  → враг  (у нас нет ключа)
     *  myKey="A", theirKey=""   → враг  (у них нет ключа)
     *  myKey="",  theirKey=""   → враг  (ни у кого нет ключа)
     */
    public boolean isCommanderHostile(
            @Nullable String myAllianceKey,
            @Nullable String theirAllianceKey
    ) {
        if (!isEnabled(TargetCategory.ENEMY_COMMANDERS)) return false;

        boolean myKeySet    = myAllianceKey    != null && !myAllianceKey.isBlank();
        boolean theirKeySet = theirAllianceKey != null && !theirAllianceKey.isBlank();

        // Союзники только если оба имеют одинаковый непустой ключ
        if (myKeySet && theirKeySet && myAllianceKey.equals(theirAllianceKey)) return false;

        return true;
    }

    // ── NBT ──────────────────────────────────────────────────────────────────
    public void saveToNBT(CompoundTag tag) {
        tag.putInt(NBT_MASK, mask);
        tag.putBoolean(NBT_WL_ENABLED, whitelistEnabled);
        ListTag list = new ListTag();
        for (String n : playerWhitelist) list.add(StringTag.valueOf(n));
        tag.put(NBT_WL_LIST, list);
        LOGGER.info("[TargetFilterData] saveToNBT this={} mask={} whitelistEnabled={} whitelistSize={}",
                System.identityHashCode(this), mask, whitelistEnabled, playerWhitelist.size());
    }

    public void loadFromNBT(CompoundTag tag) {
        int maskBefore = mask;
        boolean hadMaskKey = tag.contains(NBT_MASK);
        mask = hadMaskKey
                ? (tag.getInt(NBT_MASK) & TargetCategory.ALL_MASK)
                : TargetCategory.ALL_MASK;
        whitelistEnabled = tag.getBoolean(NBT_WL_ENABLED);
        playerWhitelist.clear();
        if (tag.contains(NBT_WL_LIST, Tag.TAG_LIST)) {
            ListTag l = tag.getList(NBT_WL_LIST, Tag.TAG_STRING);
            for (int i = 0; i < l.size() && i < MAX_WHITELIST_SIZE; i++) {
                String n = l.getString(i).trim();
                if (!n.isEmpty()) playerWhitelist.add(n);
            }
        }
        LOGGER.info("[TargetFilterData] loadFromNBT this={} hadMaskKey={} rawMaskInTag={} maskBefore={} maskAfter={} whitelistEnabled={} whitelistSize={}",
                System.identityHashCode(this), hadMaskKey,
                hadMaskKey ? tag.getInt(NBT_MASK) : -1,
                maskBefore, mask, whitelistEnabled, playerWhitelist.size());
    }
    /**
     * Возвращает true, если сущность — "настоящая" враждебная угроза:
     * ванильный {@link Monster} или помечена тегом {@link CBCAutoTargetTags#TARGETED_ENTITIES}.
     *
     * В отличие от {@link #isAllowed(Entity)}, который просто проверяет попадание
     * в категорию HOSTILE (в неё, помимо реальных монстров, попадают
     * спровоцированные нейтралы вроде голема/пиглина и модовые мобы, прошедшие
     * по fallback-эвристике ATTACK_DAMAGE > 0), этот метод нужен только для
     * приоритезации цели внутри HOSTILE-категории: "настоящие" монстры должны
     * перебивать по значимости более близких, но не собственно монстров.
     */
    public boolean isPriorityThreat(Entity entity) {
        return entity instanceof Monster
                || entity.getType().is(CBCAutoTargetTags.TARGETED_ENTITIES);
    }
}