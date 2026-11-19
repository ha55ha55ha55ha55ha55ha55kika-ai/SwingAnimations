package com.kitkatvisuals.util;

import java.util.Locale;

/**
 * Редкость ивента из поля metadata.rare API HolyWorld. В документации есть только примеры вида
 * "ship_roskoshni_f" и "default", полного списка ключей нет, поэтому ключ разбирается по признакам в тексте.
 * Неизвестный ключ не теряется: он показывается как есть, получает ранг 1 и так же фильтруется.
 * Ранг: 1 обычный, 2 редкий, 3 эпический, 4 легендарный (от него зависит цвет).
 */
public final class Rarity {
    private Rarity() {}

    public static int rank(String rare) {
        if (rare == null) return 1;
        String r = rare.toLowerCase(Locale.ROOT);
        if (has(r, "legend", "mythic", "roskosh", "smert", "deadly", "death")) return 4;
        if (has(r, "epic", "zazhit", "vzryv", "explos")) return 3;
        if (has(r, "rare", "redk")) return 2;
        return 1;
    }

    /** Читаемое название редкости для окна фильтра и HUD. */
    public static String label(String rare) {
        if (rare == null || rare.isBlank()) return "Обычный";
        String r = rare.toLowerCase(Locale.ROOT);
        if (r.equals("default") || has(r, "common", "normal", "obych", "obich")) return "Обычный";
        if (has(r, "legend")) return "Легендарный";
        if (has(r, "mythic")) return "Мифический";
        if (has(r, "epic")) return "Эпический";
        if (has(r, "rare", "redk")) return "Редкий";
        if (has(r, "roskosh")) return "Роскошный";
        if (has(r, "zazhit")) return "Зажиточный";
        if (has(r, "smert", "deadly", "death")) return "Смертельный";
        if (has(r, "vzryv", "explos")) return "Взрывной";
        if (has(r, "mirn", "peace")) return "Мирный";
        return rare; // неизвестный ключ: показываем как пришёл
    }

    public static int color(int rank) {
        return switch (rank) {
            case 4 -> 0xFFFFC93C;
            case 3 -> 0xFFB65CFF;
            case 2 -> 0xFF5CB8FF;
            default -> 0xFFE8EAF0;
        };
    }

    private static boolean has(String s, String... parts) {
        for (String p : parts) if (s.contains(p)) return true;
        return false;
    }
}
