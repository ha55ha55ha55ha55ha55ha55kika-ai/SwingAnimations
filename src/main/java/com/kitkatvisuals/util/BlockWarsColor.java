package com.kitkatvisuals.util;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Общая «читалка цвета» для BlockWarsBot и BlockWarsESP. Хук складывает сюда title / subtitle / actionbar,
 * а каждый модуль сам смотрит новые сообщения (по своему seq) и выбирает цвет раунда.
 */
public final class BlockWarsColor {
    private BlockWarsColor() {}

    /** 0 = title, 1 = subtitle, 2 = actionbar (overlay). */
    public record Msg(String text, int channel, long seq) { }

    private static final ArrayDeque<Msg> RECENT = new ArrayDeque<>();
    private static final int KEEP = 16;
    private static long seqCounter;

    public static void feed(Text t, int channel) {
        if (t == null) return;
        String s = t.getString();
        if (s == null || s.isBlank()) return;
        synchronized (RECENT) {
            RECENT.addLast(new Msg(s, channel, ++seqCounter));
            while (RECENT.size() > KEEP) RECENT.removeFirst();
        }
    }

    public static long latestSeq() {
        synchronized (RECENT) { return seqCounter; }
    }

    /** Сообщения, пришедшие после seq. */
    public static List<Msg> since(long seq) {
        List<Msg> out = new ArrayList<>();
        synchronized (RECENT) {
            for (Msg m : RECENT) if (m.seq() > seq) out.add(m);
        }
        return out;
    }

    // ---- цвета: слова (RU / EN) -> DyeColor. Совпадение только с начала слова, берётся самое длинное ----
    private static final Map<String, DyeColor> NAMES = new LinkedHashMap<>();
    private static void n(DyeColor c, String... keys) { for (String k : keys) NAMES.put(k, c); }
    static {
        n(DyeColor.WHITE, "white", "бел");
        n(DyeColor.ORANGE, "orange", "оранж");
        n(DyeColor.MAGENTA, "magenta", "маджент", "малинов", "пурпурн");
        n(DyeColor.LIGHT_BLUE, "light blue", "light_blue", "lightblue", "голуб", "светло-син", "светло син");
        n(DyeColor.YELLOW, "yellow", "желт", "жёлт");
        n(DyeColor.LIME, "lime", "лайм", "салатов", "светло-зел", "светло зел");
        n(DyeColor.PINK, "pink", "розов");
        n(DyeColor.GRAY, "gray", "grey", "серы", "сера", "серо", "серу", "сером", "тёмно-сер", "темно-сер");
        n(DyeColor.LIGHT_GRAY, "light gray", "light grey", "light_gray", "светло-сер", "светло сер");
        n(DyeColor.CYAN, "cyan", "бирюз", "циан");
        n(DyeColor.PURPLE, "purple", "фиолет");
        n(DyeColor.BLUE, "blue", "син");
        n(DyeColor.BROWN, "brown", "коричн");
        n(DyeColor.GREEN, "green", "зелен", "зелён");
        n(DyeColor.RED, "red", "красн");
        n(DyeColor.BLACK, "black", "чёрн", "черн");
    }

    /** Слова, по которым понятно, что цвет именно цель раунда (а не название команды и т.п.). */
    private static final String[] TARGET_HINTS = {
            "шерст", "блок", "цвет", "color", "colour", "wool", "block", "найд", "найти", "встан", "стань", "стой",
            "иди", "беги", "find", "stand", "go to"
    };

    private static boolean containsWordStart(String s, String k) {
        int i = s.indexOf(k);
        while (i >= 0) {
            if (i == 0 || !Character.isLetter(s.charAt(i - 1))) return true;
            i = s.indexOf(k, i + 1);
        }
        return false;
    }

    public static DyeColor parse(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).replace('ё', 'е');
        DyeColor best = null;
        int bestLen = 0;
        for (Map.Entry<String, DyeColor> e : NAMES.entrySet()) {
            String k = e.getKey().replace('ё', 'е');
            if (k.length() > bestLen && containsWordStart(s, k)) {
                best = e.getValue();
                bestLen = k.length();
            }
        }
        return best;
    }

    private static boolean hasHint(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).replace('ё', 'е');
        for (String h : TARGET_HINTS) if (s.contains(h)) return true;
        return false;
    }

    /**
     * Цвет раунда из новых сообщений. Приоритет: есть подсказка-слово (шерсть, найди...), затем title/subtitle,
     * затем самое новое. Actionbar с названием команды не перебивает основной текст раунда.
     */
    public static DyeColor pickColor(List<Msg> fresh) {
        DyeColor best = null;
        int bestScore = -1;
        for (int i = fresh.size() - 1; i >= 0; i--) {
            Msg m = fresh.get(i);
            DyeColor c = parse(m.text());
            if (c == null) continue;
            int score = (hasHint(m.text()) ? 2 : 0) + (m.channel() != 2 ? 1 : 0);
            if (score > bestScore) {
                best = c;
                bestScore = score;
            }
        }
        return best;
    }

    public static Block wool(DyeColor c) {
        return switch (c) {
            case WHITE -> Blocks.WHITE_WOOL; case ORANGE -> Blocks.ORANGE_WOOL;
            case MAGENTA -> Blocks.MAGENTA_WOOL; case LIGHT_BLUE -> Blocks.LIGHT_BLUE_WOOL;
            case YELLOW -> Blocks.YELLOW_WOOL; case LIME -> Blocks.LIME_WOOL;
            case PINK -> Blocks.PINK_WOOL; case GRAY -> Blocks.GRAY_WOOL;
            case LIGHT_GRAY -> Blocks.LIGHT_GRAY_WOOL; case CYAN -> Blocks.CYAN_WOOL;
            case PURPLE -> Blocks.PURPLE_WOOL; case BLUE -> Blocks.BLUE_WOOL;
            case BROWN -> Blocks.BROWN_WOOL; case GREEN -> Blocks.GREEN_WOOL;
            case RED -> Blocks.RED_WOOL; case BLACK -> Blocks.BLACK_WOOL;
        };
    }

    public static int woolRgb(DyeColor c) {
        return switch (c) {
            case WHITE -> 0xF9FFFE; case ORANGE -> 0xF9801D; case MAGENTA -> 0xC74EBD;
            case LIGHT_BLUE -> 0x3AB3DA; case YELLOW -> 0xFED83D; case LIME -> 0x80C71F;
            case PINK -> 0xF38BAA; case GRAY -> 0x474F52; case LIGHT_GRAY -> 0x9D9D97;
            case CYAN -> 0x169C9C; case PURPLE -> 0x8932B8; case BLUE -> 0x3C44AA;
            case BROWN -> 0x835432; case GREEN -> 0x5E7C16; case RED -> 0xB02E26;
            case BLACK -> 0x1D1D21;
        };
    }

    /** Цвет шерсти, но тёмные оттенки (чёрный, серый, коричневый) осветлены, чтобы метка была видна. */
    public static int visibleRgb(DyeColor c) {
        int rgb = woolRgb(c);
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        int lum = (r * 3 + g * 6 + b) / 10;
        if (lum < 100) {
            int k = lum < 50 ? 65 : 45;
            r += (255 - r) * k / 100; g += (255 - g) * k / 100; b += (255 - b) * k / 100;
        }
        return (r << 16) | (g << 8) | b;
    }

    public static String ruName(DyeColor c) {
        return switch (c) {
            case WHITE -> "Белый"; case ORANGE -> "Оранжевый"; case MAGENTA -> "Пурпурный";
            case LIGHT_BLUE -> "Голубой"; case YELLOW -> "Жёлтый"; case LIME -> "Салатовый";
            case PINK -> "Розовый"; case GRAY -> "Серый"; case LIGHT_GRAY -> "Светло-серый";
            case CYAN -> "Бирюзовый"; case PURPLE -> "Фиолетовый"; case BLUE -> "Синий";
            case BROWN -> "Коричневый"; case GREEN -> "Зелёный"; case RED -> "Красный";
            case BLACK -> "Чёрный";
        };
    }
}
