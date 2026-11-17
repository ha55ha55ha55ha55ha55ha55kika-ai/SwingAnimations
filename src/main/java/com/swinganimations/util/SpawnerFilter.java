package com.swinganimations.util;

import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Список зачарований меча, по которым можно искать спавнеры, и сама проверка «подходит ли спавнер под фильтр».
 * Первым стоит серверное зачарование «Фармер»: оно ищется и в id зачарования, и в lore меча, и в custom_data.
 * Чтобы добавить ещё одно серверное зачарование, допишите строку в ENTRIES (custom = true).
 */
public final class SpawnerFilter {
    public record Entry(String key, String label, boolean custom, int maxLevel, String[] aliases) {
        public Text title() {
            return custom ? Text.literal(label) : Text.translatable("enchantment.minecraft." + key);
        }

        /** Подходит ли зачарование с таким id/названием под эту запись. */
        public boolean matches(String shortId, String name) {
            String id = shortId == null ? "" : shortId.toLowerCase(Locale.ROOT);
            String nm = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (!custom) return id.equals(key) || nm.equals(key);
            for (String a : aliases) if (id.contains(a) || nm.contains(a)) return true;
            return false;
        }
    }

    public static final List<Entry> ENTRIES = List.of(
            new Entry("farmer", "Фармер", true, 5, new String[]{"farmer", "фармер"}),
            new Entry("sharpness", "", false, 5, new String[]{"sharpness"}),
            new Entry("smite", "", false, 5, new String[]{"smite"}),
            new Entry("bane_of_arthropods", "", false, 5, new String[]{"bane_of_arthropods"}),
            new Entry("fire_aspect", "", false, 2, new String[]{"fire_aspect"}),
            new Entry("looting", "", false, 3, new String[]{"looting"}),
            new Entry("sweeping_edge", "", false, 3, new String[]{"sweeping_edge"}),
            new Entry("knockback", "", false, 2, new String[]{"knockback"}),
            new Entry("unbreaking", "", false, 3, new String[]{"unbreaking"}),
            new Entry("mending", "", false, 1, new String[]{"mending"}));

    private SpawnerFilter() {}

    public static List<Entry> customEntries() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : ENTRIES) if (e.custom()) out.add(e);
        return out;
    }

    public static Entry byKey(String key) {
        for (Entry e : ENTRIES) if (e.key().equals(key)) return e;
        return null;
    }

    // ---------------- выбранные зачарования (строка «farmer,sharpness») ----------------

    public static Set<String> parse(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null) return out;
        for (String s : csv.split(",")) {
            String k = s.trim().toLowerCase(Locale.ROOT);
            if (!k.isEmpty() && byKey(k) != null) out.add(k);
        }
        return out;
    }

    public static String toggle(String csv, String key) {
        Set<String> s = parse(csv);
        if (!s.remove(key)) s.add(key);
        return String.join(",", s);
    }

    // ---------------- проверка ----------------

    /** Максимальный уровень этого зачарования у меча в спавнере (0, если нет). */
    public static int levelOf(SpawnerInfo info, Entry e) {
        int best = 0;
        for (SpawnerInfo.Ench en : info.enchants) {
            if (e.matches(en.shortId(), en.label())) best = Math.max(best, en.level());
        }
        return best;
    }

    /**
     * @param keys     выбранные зачарования
     * @param matchAll true: нужны все выбранные, false: хотя бы одно
     * @param minLevel минимальный уровень каждого выбранного зачарования
     * @param mobId    нужный моб (minecraft:zombie) или null, если любой
     */
    public static boolean matches(SpawnerInfo info, Set<String> keys, boolean matchAll, int minLevel, String mobId) {
        if (mobId != null && (info.mobId == null || !info.mobId.equals(mobId))) return false;
        if (keys.isEmpty()) return true;
        boolean any = false;
        for (String k : keys) {
            Entry e = byKey(k);
            if (e == null) continue;
            boolean ok = levelOf(info, e) >= Math.max(1, minLevel);
            if (ok) any = true;
            else if (matchAll) return false;
        }
        return matchAll || any;
    }
}
