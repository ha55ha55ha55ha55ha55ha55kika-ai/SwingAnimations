package com.kitkatvisuals.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Определяет, на какой анархии вы играете, по боковой панели (scoreboard). Два формата:
 * 1) слово «анархия»/«anarchy» с номером (как раньше);
 * 2) строка «Название #N», например «КланЛайт #28» на HolyWorld (внизу панели справа).
 * Результат кэшируется на 3 секунды. detect() = номер (0: не удалось), label() = «КланЛайт #28» или «Анархия 17».
 */
public final class AnarchyDetector {
    private static final Pattern P = Pattern.compile("(?:\\p{L}*анарх\\p{L}*|anarch\\p{L}*)\\W{0,6}(\\d{1,2})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);   // UNICODE_CASE: иначе «Анархия» с большой буквы не находится
    /** «КланЛайт #28»: буквы (можно с пробелами и цифрами внутри слов), затем # и номер. Хвост вроде «NEW» не мешает. */
    private static final Pattern HASH = Pattern.compile("(\\p{L}[\\p{L}\\p{N} ]{0,23}?)\\s*#\\s*(\\d{1,3})");

    private static long at;
    private static int cachedNum;
    private static String cachedLabel = "";

    private AnarchyDetector() {}

    public static int detect(MinecraftClient mc) {
        refresh(mc);
        return cachedNum >= 1 && cachedNum <= 36 ? cachedNum : 0;
    }

    /** Подпись для друзей: «КланЛайт #28» (формат 2) или «Анархия 17» (формат 1); пустая строка, если не определилось. */
    public static String label(MinecraftClient mc) {
        refresh(mc);
        return cachedLabel;
    }

    private static void refresh(MinecraftClient mc) {
        long now = System.currentTimeMillis();
        if (now - at < 3000L) return;
        at = now;
        cachedNum = 0;
        cachedLabel = "";
        try {
            if (mc.world == null) return;
            Scoreboard sb = mc.world.getScoreboard();
            ScoreboardObjective obj = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
            if (obj == null) return;
            java.util.List<String> lines = new java.util.ArrayList<>();
            lines.add(obj.getDisplayName().getString());
            for (ScoreHolder h : sb.getKnownScoreHolders()) {
                String name = h.getNameForScoreboard();
                lines.add(Team.decorateName(sb.getScoreHolderTeam(name), Text.literal(name)).getString());
            }
            for (String s : lines) {                       // сначала «анархия N»
                Matcher m = P.matcher(s);
                if (m.find()) {
                    int n = Integer.parseInt(m.group(1));
                    if (n >= 1 && n <= 36) {
                        cachedNum = n;
                        cachedLabel = "Анархия " + n;
                        return;
                    }
                }
            }
            for (String s : lines) {                       // потом «Название #N»
                Matcher m = HASH.matcher(s);
                if (m.find()) {
                    cachedNum = Integer.parseInt(m.group(2));
                    cachedLabel = m.group(1).trim() + " #" + cachedNum;
                    return;
                }
            }
        } catch (Throwable ignored) {
            // не критично: останется выбранная вручную анархия
        }
    }
}
