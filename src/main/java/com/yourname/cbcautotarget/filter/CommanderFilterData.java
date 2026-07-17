package com.yourname.cbcautotarget.filter;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Фильтр "дружественных" блоков-командеров для Machine Soul.
 *
 * В отличие от {@link TargetFilterData} (который фильтрует сущностей/игроков
 * по категориям и именам), этот фильтр работает с "Alliance Key" командера —
 * произвольной строкой, которую игрок вручную вводит в поле "Alliance Key:"
 * внутри блока командера (см. CommanderBlockEntity.getAllianceKey() /
 * setAllianceKey()). Ограничение по длине здесь всегда совпадает с тем,
 * что разрешено в самом блоке командера (см. MAX_KEY_LENGTH).
 *
 * Логика простая и симметричная списку игроков, но без режимов
 * TARGET/IGNORE/FOLLOW — только одно направление:
 *   - Список содержит Alliance Key командеров, которые считаются ДРУЖЕСТВЕННЫМИ
 *     (Machine Soul их полностью игнорирует).
 *   - Любой другой обнаруженный командер (ключ отсутствует в списке, включая
 *     пустой ключ по умолчанию) автоматически становится целью — так же,
 *     как враждебный игрок.
 *   - Если список пуст — ВСЕ обнаруженные командеры являются целями
 *     (нет ни одного "друга").
 * Отдельного тумблера "включено/выключено" не требуется: наличие фильтра
 * (сама вкладка) уже подразумевает участие командеров в таргетинге.
 *
 * Сравнение регистронезависимое (нормализация через upper-case + trim),
 * но само хранимое значение ключа в CommanderBlockEntity не изменяется —
 * нормализация применяется только на стороне этого фильтра, при сравнении.
 */
public class CommanderFilterData {

    private static final Logger LOGGER = LoggerFactory.getLogger("cbc_autotarget/CommanderFilterData");

    private static final String NBT_FRIENDLY_LIST = "FriendlyCommanderIds";

    public static final int MAX_FRIENDLY_SIZE = 50;

    /**
     * Максимальная длина Alliance Key — должна совпадать с ограничением
     * в CommanderBlockEntity.setAllianceKey() (там ключ обрезается до 64
     * символов). Держим оба значения синхронизированными вручную, так как
     * они находятся в разных, не связанных напрямую классах.
     */
    public static final int MAX_KEY_LENGTH = 64;

    private final LinkedHashSet<String> friendlyIds = new LinkedHashSet<>();

    // ── Список дружественных Alliance Key ────────────────────────────────────

    public Set<String> getFriendlyIds() { return Collections.unmodifiableSet(friendlyIds); }

    /** Нормализует ввод: обрезает пробелы, приводит к верхнему регистру. */
    public static String normalize(String id) {
        return id == null ? "" : id.trim().toUpperCase(java.util.Locale.ROOT);
    }

    public boolean addFriendly(String id) {
        String n = normalize(id);
        if (n.isEmpty() || n.length() > MAX_KEY_LENGTH) return false;
        if (friendlyIds.size() >= MAX_FRIENDLY_SIZE) return false;
        return friendlyIds.add(n);
    }

    public boolean removeFriendly(String id) { return friendlyIds.remove(normalize(id)); }

    public void replaceFriendlyIds(java.util.Collection<String> ids) {
        friendlyIds.clear();
        for (String id : ids) {
            String n = normalize(id);
            if (!n.isEmpty() && n.length() <= MAX_KEY_LENGTH && friendlyIds.size() < MAX_FRIENDLY_SIZE) friendlyIds.add(n);
        }
    }

    /** true, если этот Alliance Key командера считается дружественным (не должен становиться целью). */
    public boolean isFriendly(String commanderAllianceKey) {
        String n = normalize(commanderAllianceKey);
        if (n.isEmpty()) return false; // пустой ключ (не задан) никогда не считается дружественным
        boolean result = friendlyIds.contains(n);
        LOGGER.info("[CommanderFilterData] isFriendly this={} rawKey='{}' normalizedKey='{}' friendlyIds={} -> {}",
                System.identityHashCode(this), commanderAllianceKey, n, friendlyIds, result);
        return result;
    }

    // ── NBT ──────────────────────────────────────────────────────────────────

    public void saveToNBT(CompoundTag tag) {
        ListTag list = new ListTag();
        for (String id : friendlyIds) list.add(StringTag.valueOf(id));
        tag.put(NBT_FRIENDLY_LIST, list);
        LOGGER.info("[CommanderFilterData] saveToNBT this={} friendlyIds={}", System.identityHashCode(this), friendlyIds);
    }

    public void loadFromNBT(CompoundTag tag) {
        friendlyIds.clear();
        if (!tag.contains(NBT_FRIENDLY_LIST, Tag.TAG_LIST)) {
            LOGGER.info("[CommanderFilterData] loadFromNBT this={} — no '{}' tag present, friendlyIds=empty",
                    System.identityHashCode(this), NBT_FRIENDLY_LIST);
            return;
        }
        ListTag list = tag.getList(NBT_FRIENDLY_LIST, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            String n = normalize(list.getString(i));
            if (!n.isEmpty() && n.length() <= MAX_KEY_LENGTH && friendlyIds.size() < MAX_FRIENDLY_SIZE) friendlyIds.add(n);
        }
        LOGGER.info("[CommanderFilterData] loadFromNBT this={} loaded friendlyIds={}", System.identityHashCode(this), friendlyIds);
    }
}
