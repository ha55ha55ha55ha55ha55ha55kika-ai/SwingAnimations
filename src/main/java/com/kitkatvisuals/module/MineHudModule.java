package com.kitkatvisuals.module;

import com.kitkatvisuals.gui.Hud;
import com.kitkatvisuals.gui.HudLayout;
import com.kitkatvisuals.gui.Ui;
import com.kitkatvisuals.util.AnarchyDetector;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.sound.SoundEvents;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MineHud: HolyWorld, автошахты. Читает голограммы («Текущая шахта», «Следующая шахта», таймер) рядом с вами и ведёт
 * таймеры для всех анархий, где вы уже побывали. Только читает данные с экрана: ничего не отправляет на сервер и сам
 * никуда не переходит. Порт MaksaiMinersHW под 1.21.4 (без автообхода анархий).
 */
public final class MineHudModule extends Module {
    private static final double LINK_DIST_SQ = 2.25 * 2.25;
    private static final double LINE_GAP = 0.85;
    private static final int MIN_LINES = 4;
    private static final long RECENT_MS = 90_000L;

    private static final Pattern CLOCK = Pattern.compile("(\\d{1,2})\\s*[:.]\\s*(\\d{1,2})(?:\\s*[:.]\\s*(\\d{1,2}))?");
    private static final Pattern HOURS = Pattern.compile("(\\d+)\\s*(?:ч|час|h|hr)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern MINUTES = Pattern.compile("(\\d+)\\s*(?:мин|min|м\\b)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SECONDS = Pattern.compile("(\\d+)\\s*(?:сек|sec|с\\b)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TIME_WORD = Pattern.compile("обновлени|осталос|время|time|reset", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern LAST_NUMBER = Pattern.compile("(\\d{1,3})(?!.*\\d)");

    private static final int COL_COMMON = 0xFFF4F6FB;
    private static final int COL_EPIC = 0xFFE283FF;
    private static final int COL_LEGENDARY = 0xFFFFC75B;
    private static final int COL_ALERT = 0xFFFF5B68;

    private final Setting.Mode worlds = add(new Setting.Mode("worlds", "Show", "All", "Overworld", "Nether+End"));
    private final Setting.Mode rarity = add(new Setting.Mode("rarity", "Rarity", "All", "Epic+", "Legendary"));
    private final Setting.Num rows = add(new Setting.Num("rows", "Rows", 12f, 3f, 30f, 1f));
    private final Setting.Num blink = add(new Setting.Num("blink", "Blink red at (s)", 30f, 0f, 120f, 5f));
    private final Setting.Num interval = add(new Setting.Num("interval", "Scan interval (s)", 5f, 2f, 30f, 1f));
    private final Setting.Num cycle = add(new Setting.Num("cycle", "Default mine cycle (s)", 270f, 60f, 3600f, 10f));
    private final Setting.Bool recentOn = add(new Setting.Bool("recent", "Show recently reset mines", true));
    private final Setting.Bool sound = add(new Setting.Bool("sound", "Sound on reset", true));
    private final Setting.Bool rescan = add(new Setting.Bool("rescan", "Scan now", false));
    private final Setting.Bool clear = add(new Setting.Bool("clear", "Clear saved mines", false));

    private final HudLayout.Win win = HudLayout.win("mine_hud");

    private enum Type {
        OVERWORLD("Обычный мир"), NETHER("Ад"), END("Энд"), UNKNOWN("?");
        final String label;
        Type(String l) { label = l; }

        static Type of(String raw) {
            String s = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
            if (s.contains("энд")) return END;
            if (s.contains("ад")) return NETHER;
            if (s.contains("верхнего мира") || s.contains("верхний мир") || s.contains("обычный мир") || s.contains("обычная шахта")) return OVERWORLD;
            return UNKNOWN;
        }
    }

    private enum Rar {
        COMMON("Обычная"), EPIC("Эпическая"), LEGENDARY("Легендарная"), UNKNOWN("?");
        final String label;
        Rar(String l) { label = l; }

        static Rar of(String raw) {
            String s = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
            if (s.contains("легендар")) return LEGENDARY;
            if (s.contains("эпичес")) return EPIC;
            if (s.contains("обыч")) return COMMON;
            return UNKNOWN;
        }

        int color() { return this == EPIC ? COL_EPIC : this == LEGENDARY ? COL_LEGENDARY : COL_COMMON; }
    }

    private static final class Mine {
        final int anarchy;
        final boolean netherEnd;
        Type cur, next;
        Rar curRar, nextRar;
        long resetAt;   // момент следующего обновления (мс)
        int cycleSec;

        Mine(int anarchy, boolean netherEnd) {
            this.anarchy = anarchy;
            this.netherEnd = netherEnd;
        }

        int left() { return (int) Math.max(0L, (resetAt - System.currentTimeMillis() + 999L) / 1000L); }

        Type shownType() { return next != Type.UNKNOWN ? next : cur; }

        Rar shownRar() { return nextRar != Rar.UNKNOWN ? nextRar : curRar; }
    }

    private record Reset(int anarchy, Type type, Rar rar, long at) { }

    private final Map<String, Mine> mines = new ConcurrentHashMap<>();
    private final ArrayDeque<Reset> recent = new ArrayDeque<>();
    private long nextScan;
    private Object lastWorld;

    public MineHudModule() {
        super("mine_hud", "MineHud", "hud");
    }

    @Override
    public void onDisable() {
        // данные не стираем: таймеры продолжают считаться в памяти, пока игра запущена
    }

    // ------------------------------------------------------------------ тик

    @Override
    public void tick(MinecraftClient mc) {
        if (clear.value) {
            clear.value = false;
            mines.clear();
            recent.clear();
        }
        if (mc.world == null) {
            lastWorld = null;
            return;
        }
        long now = System.currentTimeMillis();
        rollExpired(mc, now);
        while (!recent.isEmpty() && now - recent.peekFirst().at() > RECENT_MS) recent.pollFirst();

        boolean forced = rescan.value;
        if (mc.world != lastWorld) {
            lastWorld = mc.world;
            nextScan = now + 3000L; // голограммы подгружаются не сразу
        }
        if (forced) {
            rescan.value = false;
            nextScan = 0L;
        }
        if (now >= nextScan) {
            nextScan = now + (long) (interval.value * 1000f);
            scan(mc, now);
        }
    }

    private void rollExpired(MinecraftClient mc, long now) {
        for (Mine m : mines.values()) {
            if (now < m.resetAt) continue;
            int period = m.cycleSec > 0 ? m.cycleSec : (int) cycle.value;
            long cycles = (now - m.resetAt) / (period * 1000L) + 1L;
            m.resetAt += cycles * period * 1000L;
            if (passes(m.shownType(), m.shownRar(), m.netherEnd)) {
                recent.addLast(new Reset(m.anarchy, m.shownType(), m.shownRar(), now));
                if (sound.value) mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_ANVIL_LAND, 1.0f));
            }
        }
    }

    private boolean passes(Type type, Rar rar, boolean netherEnd) {
        boolean ne = netherEnd || type == Type.NETHER || type == Type.END;
        if (worlds.is("Overworld") && ne) return false;
        if (worlds.is("Nether+End") && !ne) return false;
        if (rarity.is("Epic+") && rar != Rar.EPIC && rar != Rar.LEGENDARY) return false;
        if (rarity.is("Legendary") && rar != Rar.LEGENDARY) return false;
        return true;
    }

    // ------------------------------------------------------------------ сканирование голограмм

    private record Stand(double x, double y, double z, String name) { }

    private void scan(MinecraftClient mc, long now) {
        int anarchy = currentAnarchy(mc);
        if (anarchy <= 0) return;
        List<Stand> stands = new ArrayList<>();
        try {
            for (Entity e : mc.world.getEntities()) {
                if (!(e instanceof ArmorStandEntity st) || !st.hasCustomName() || st.getCustomName() == null) continue;
                String name = clean(st.getCustomName().getString());
                if (!name.isEmpty() && relevant(name)) stands.add(new Stand(st.getX(), st.getY(), st.getZ(), name));
            }
        } catch (RuntimeException ignored) {
            return; // список сущностей менялся во время обхода: попробуем в следующий раз
        }
        if (stands.isEmpty()) return;
        List<List<String>> groups = new ArrayList<>();
        for (List<Stand> cluster : clusters(stands)) groups.addAll(verticalGroups(cluster));
        for (List<String> lines : groups) {
            if (!isAutoMine(lines)) continue;
            parse(lines, anarchy, now);
        }
    }

    /** Номер анархии из «КланЛайт #28» / «Анархия 17» на боковой панели. */
    private static int currentAnarchy(MinecraftClient mc) {
        String label = AnarchyDetector.label(mc);
        if (label == null || label.isEmpty()) return 0;
        Matcher m = LAST_NUMBER.matcher(label);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static String clean(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u00a7') { i++; continue; }
            out.append(c);
        }
        return out.toString().replace('\u00a0', ' ').trim();
    }

    private static boolean relevant(String line) {
        String n = line.toLowerCase(Locale.ROOT);
        return n.contains("до обновления") || n.contains("осталось") || n.contains("текущая шахта") || n.contains("следующая шахта")
                || seconds(line) >= 0 || Type.of(line) != Type.UNKNOWN || Rar.of(line) != Rar.UNKNOWN;
    }

    private static List<List<Stand>> clusters(List<Stand> stands) {
        List<List<Stand>> out = new ArrayList<>();
        boolean[] seen = new boolean[stands.size()];
        for (int i = 0; i < stands.size(); i++) {
            if (seen[i]) continue;
            List<Stand> cluster = new ArrayList<>();
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(i);
            seen[i] = true;
            while (!queue.isEmpty()) {
                Stand cur = stands.get(queue.removeFirst());
                cluster.add(cur);
                for (int j = 0; j < stands.size(); j++) {
                    if (seen[j]) continue;
                    double dx = cur.x() - stands.get(j).x();
                    double dz = cur.z() - stands.get(j).z();
                    if (dx * dx + dz * dz <= LINK_DIST_SQ) {
                        seen[j] = true;
                        queue.addLast(j);
                    }
                }
            }
            out.add(cluster);
        }
        return out;
    }

    private static List<List<String>> verticalGroups(List<Stand> cluster) {
        cluster.sort(Comparator.comparingDouble(Stand::y).reversed());
        List<List<String>> groups = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        double prevY = Double.NaN;
        for (Stand s : cluster) {
            if (!cur.isEmpty() && prevY - s.y() > LINE_GAP) {
                if (cur.size() >= MIN_LINES) groups.add(cur);
                cur = new ArrayList<>();
            }
            cur.add(s.name());
            prevY = s.y();
        }
        if (cur.size() >= MIN_LINES) groups.add(cur);
        return groups;
    }

    private static boolean isAutoMine(List<String> lines) {
        boolean timer = false, current = false, next = false;
        for (String l : lines) {
            String n = l.toLowerCase(Locale.ROOT);
            if (n.contains("до обновления") || n.contains("осталось") || n.contains("мин") || n.contains("сек")) timer = true;
            if (n.contains("текущая шахта")) current = true;
            if (n.contains("следующая шахта")) next = true;
        }
        return timer && current && next;
    }

    // ------------------------------------------------------------------ разбор

    private void parse(List<String> lines, int anarchy, long now) {
        int secs = -1;
        for (String l : lines) {
            int s = seconds(l);
            if (s >= 0) secs = s;
        }
        Type cur = Type.UNKNOWN, next = Type.UNKNOWN;
        Rar curRar = Rar.UNKNOWN, nextRar = Rar.UNKNOWN;
        for (int i = 0; i < lines.size(); i++) {
            String n = lines.get(i).toLowerCase(Locale.ROOT);
            if (n.contains("текущая шахта")) {
                String v = valueAfter(lines, i);
                cur = Type.of(v);
                curRar = Rar.of(v);
            }
            if (n.contains("следующая шахта")) {
                String v = valueAfter(lines, i);
                next = Type.of(v);
                nextRar = Rar.of(v);
            }
        }
        boolean ne = cur == Type.NETHER || cur == Type.END || next == Type.NETHER || next == Type.END;
        if (!ne) {
            if (cur == Type.UNKNOWN) cur = Type.OVERWORLD;
            if (next == Type.UNKNOWN) next = Type.OVERWORLD;
        }
        if (secs < 0 || nextRar == Rar.UNKNOWN) return;

        String key = (ne ? "ne-" : "ow-") + anarchy;
        Mine m = mines.computeIfAbsent(key, k -> new Mine(anarchy, ne));
        m.cur = cur;
        m.curRar = curRar;
        m.next = next;
        m.nextRar = nextRar;
        m.resetAt = now + secs * 1000L;
        // период запоминаем по самому большому увиденному таймеру (но не меньше стандартного)
        m.cycleSec = Math.min(7200, Math.max(Math.max(m.cycleSec, secs), (int) cycle.value));
    }

    private static String valueAfter(List<String> lines, int idx) {
        String line = lines.get(idx);
        int colon = line.indexOf(':');
        if (colon >= 0 && colon + 1 < line.length()) {
            String v = line.substring(colon + 1).trim();
            if (!v.isEmpty()) return v;
        }
        for (int i = idx + 1; i < lines.size() && i <= idx + 2; i++) {
            String c = lines.get(i).trim();
            String n = c.toLowerCase(Locale.ROOT);
            if (!c.isEmpty() && !n.contains("текущая шахта") && !n.contains("следующая шахта") && !n.contains("до обновления") && !n.contains("осталось")) return c;
        }
        return "";
    }

    /** Секунды из строки вида «до обновления: 03:12» или «3 мин 12 сек»; -1, если это не таймер. */
    private static int seconds(String line) {
        if (line == null || line.isEmpty()) return -1;
        int total = -1;
        Matcher clock = CLOCK.matcher(line);
        boolean hasClock = clock.find();
        if (hasClock) {
            int a = Integer.parseInt(clock.group(1));
            int b = Integer.parseInt(clock.group(2));
            total = clock.group(3) != null ? a * 3600 + b * 60 + Integer.parseInt(clock.group(3)) : a * 60 + b;
        }
        int words = 0;
        boolean found = false;
        Matcher h = HOURS.matcher(line), mi = MINUTES.matcher(line), s = SECONDS.matcher(line);
        if (h.find()) { words += Integer.parseInt(h.group(1)) * 3600; found = true; }
        if (mi.find()) { words += Integer.parseInt(mi.group(1)) * 60; found = true; }
        if (s.find()) { words += Integer.parseInt(s.group(1)); found = true; }
        if (found) total = Math.max(total, words);
        if (total < 0) return -1;
        return !hasClock && !found && !TIME_WORD.matcher(line).find() ? -1 : total;
    }

    // ------------------------------------------------------------------ HUD

    @Override
    public void renderHud(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden) return;
        boolean edit = HudLayout.editing();

        List<Mine> upcoming = new ArrayList<>();
        for (Mine m : mines.values()) {
            if (m.anarchy > 0 && passes(m.shownType(), m.shownRar(), m.netherEnd)) upcoming.add(m);
        }
        upcoming.sort(Comparator.comparingLong((Mine m) -> m.resetAt).thenComparingInt(m -> m.anarchy));
        int maxRows = (int) rows.value;
        int shown = Math.min(upcoming.size(), maxRows);

        List<Reset> rec = recentOn.value ? new ArrayList<>(recent) : List.of();
        int recShown = Math.min(rec.size(), 4);
        boolean example = edit && upcoming.isEmpty();

        int rowH = 11;
        int x = 8;
        int y = 90;
        int w = 168;
        int bodyRows = example ? 2 : Math.max(1, shown);
        int h = 20 + bodyRows * rowH + (recShown > 0 ? 6 + recShown * rowH : 0) + 4;

        HudLayout.push(ctx, win);
        Hud.panel(ctx, x, y, w, h);
        Hud.text(ctx, "Шахты", x + 7, y + 6, Ui.TEXT);
        String cnt = String.valueOf(upcoming.size());
        Hud.text(ctx, cnt, x + w - 7 - Hud.w(cnt), y + 6, Ui.TEXT_DIM);

        int ry = y + 19;
        if (example) {
            Hud.text(ctx, "#12  Ад · Эпическая", x + 7, ry, COL_EPIC);
            Hud.textRight(ctx, "2:41", x + w - 7, ry, COL_EPIC);
            Hud.text(ctx, "#27  Энд · Легендарная", x + 7, ry + rowH, COL_LEGENDARY);
            Hud.textRight(ctx, "0:20", x + w - 7, ry + rowH, COL_ALERT);
            ry += 2 * rowH;
        } else if (upcoming.isEmpty()) {
            Hud.text(ctx, "Подойдите к голограмме шахты", x + 7, ry, Ui.TEXT_DIM);
            ry += rowH;
        } else {
            long now = System.currentTimeMillis();
            for (int i = 0; i < shown; i++) {
                Mine m = upcoming.get(i);
                int left = m.left();
                int color = m.shownRar().color();
                if (blink.value > 0f && left <= (int) blink.value && (now / 500L) % 2L == 0L) color = COL_ALERT;
                Hud.text(ctx, "#" + m.anarchy + "  " + m.shownType().label + (m.shownRar() == Rar.UNKNOWN ? "" : " · " + m.shownRar().label),
                        x + 7, ry, color);
                Hud.textRight(ctx, (left / 60) + ":" + String.format(Locale.ROOT, "%02d", left % 60), x + w - 7, ry, color);
                ry += rowH;
            }
        }
        if (recShown > 0) {
            ry += 2;
            Hud.text(ctx, "Обновились", x + 7, ry - 1, Ui.TEXT_DIM);
            ry += 5;
            for (int i = 0; i < recShown; i++) {
                Reset r = rec.get(rec.size() - 1 - i);
                Hud.text(ctx, "#" + r.anarchy() + "  " + r.type().label + (r.rar() == Rar.UNKNOWN ? "" : " · " + r.rar().label),
                        x + 7, ry + 4, r.rar().color());
                ry += rowH;
            }
        }
        HudLayout.pop(ctx, win, x, y, w, h);
    }
}
