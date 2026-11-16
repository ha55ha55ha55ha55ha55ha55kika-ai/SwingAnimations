package com.swinganimations.util;

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
 * Пытается понять, на какой анархии вы сейчас играете: ищет слово «анархия»/«anarchy» с номером в боковой панели
 * (заголовок и строки scoreboard). Результат кэшируется на 3 секунды. 0 = не удалось определить.
 */
public final class AnarchyDetector {
    private static final Pattern P = Pattern.compile("(?i)(?:\\p{L}*анарх\\p{L}*|anarch\\p{L}*)\\W{0,6}(\\d{1,2})");
    private static long at;
    private static int cached;

    private AnarchyDetector() {}

    public static int detect(MinecraftClient mc) {
        long now = System.currentTimeMillis();
        if (now - at < 3000L) return cached;
        at = now;
        cached = 0;
        try {
            if (mc.world == null) return 0;
            Scoreboard sb = mc.world.getScoreboard();
            ScoreboardObjective obj = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
            if (obj == null) return 0;
            cached = find(obj.getDisplayName().getString());
            if (cached > 0) return cached;
            for (ScoreHolder h : sb.getKnownScoreHolders()) {
                String name = h.getNameForScoreboard();
                String line = Team.decorateName(sb.getScoreHolderTeam(name), Text.literal(name)).getString();
                cached = find(line);
                if (cached > 0) return cached;
            }
        } catch (Throwable ignored) {
            // не критично: останется выбранная вручную анархия
        }
        return cached;
    }

    private static int find(String s) {
        Matcher m = P.matcher(s);
        if (!m.find()) return 0;
        int n = Integer.parseInt(m.group(1));
        return n >= 1 && n <= 36 ? n : 0;
    }
}
