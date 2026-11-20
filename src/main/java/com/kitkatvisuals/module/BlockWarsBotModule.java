package com.kitkatvisuals.module;

import com.kitkatvisuals.gui.Hud;
import com.kitkatvisuals.gui.Ui;
import com.kitkatvisuals.render.Batch;
import com.kitkatvisuals.render.Projection;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BlockWars-бот: читает цвет из title / subtitle / actionbar, бежит к ближайшей шерсти этого цвета
 * (спринт, без прыжков на ровном месте), на нужном блоке приседает (Shift).
 * ESP: подсвечивает все блоки нужного цвета в радиусе и над ближайшими ставит стрелку с расстоянием, как метки EventHud.
 */
public final class BlockWarsBotModule extends Module {
    private final Setting.Num range = add(new Setting.Num("range", "Search range", 24f, 8f, 40f, 1f));
    private final Setting.Num reaction = add(new Setting.Num("reaction", "Reaction delay (ticks)", 0f, 0f, 10f, 1f));
    private final Setting.Num turnSpeed = add(new Setting.Num("turn_speed", "Turn speed (deg/tick)", 180f, 10f, 180f, 5f));
    private final Setting.Bool sneakOnTarget = add(new Setting.Bool("sneak", "Sneak on target", true));
    private final Setting.Bool esp = add(new Setting.Bool("esp", "Highlight target wool", true));
    private final Setting.Bool arrows = add(new Setting.Bool("arrows", "Arrows above target blocks", true));
    private final Setting.Bool outline = add(new Setting.Bool("outline", "Outline target blocks", true));
    private final Setting.Bool beam = add(new Setting.Bool("beam", "Beam over nearest block", true));
    private final Setting.Bool pointer = add(new Setting.Bool("pointer", "Off-screen pointer", true));
    private final Setting.Bool colorChip = add(new Setting.Bool("color_chip", "Show read color on top", true));
    private final Setting.Bool throughWalls = add(new Setting.Bool("through_walls", "ESP through walls", true));
    private final Setting.Bool debug = add(new Setting.Bool("debug", "Log received texts to chat", false));

    // ---- приём текста из хуков (клиентский поток) ----
    /** 0 = title, 1 = subtitle, 2 = actionbar (overlay). */
    private record Msg(String text, int channel, long seq) { }

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

    private static long latestSeq() {
        synchronized (RECENT) { return seqCounter; }
    }

    /** Сообщения, пришедшие после seq. */
    private static List<Msg> since(long seq) {
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

    static DyeColor parse(String raw) {
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
    private static DyeColor pickColor(List<Msg> fresh) {
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

    private static Block wool(DyeColor c) {
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

    // ---- состояние раунда ----
    private long seenSeq;
    private long loggedSeq;
    private Block targetBlock;
    private DyeColor targetColor;
    private BlockPos targetPos;
    private int delayLeft;
    private int rescanIn;
    private List<BlockPos> espPos = List.of();
    private int espTick;
    private final float[] scr = new float[2];

    public BlockWarsBotModule() {
        super("block_wars_bot", "BlockWarsBot", "movement");
    }

    @Override public boolean restoreOnLoad() { return false; }

    /** Скрытый модуль: виден только после секретного кода в поиске меню. */
    @Override public boolean secret() { return true; }

    @Override
    public void onEnable() {
        seenSeq = latestSeq(); // старые сообщения игнорируем
        loggedSeq = seenSeq;
        resetRound();
        targetBlock = null;
        targetColor = null;
        espPos = List.of();
    }

    @Override
    public void onDisable() {
        targetBlock = null;
        targetColor = null;
        targetPos = null;
        espPos = List.of();
        KeyBinding.updatePressedStates(); // вернуть клавиши в реальное состояние
    }

    private void resetRound() {
        targetPos = null;
        rescanIn = 0;
        delayLeft = (int) reaction.value;
    }

    @Override
    public void tick(MinecraftClient mc) {
        if (!ModuleManager.adminUnlocked()) {
            if (isEnabled()) setEnabled(false);
            return;
        }
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return;

        if (debug.value) logNew(mc);

        if (mc.currentScreen != null) { release(mc); return; }

        // новый раунд? смотрим только на сообщения, пришедшие после прошлой проверки
        long top = latestSeq();
        if (top != seenSeq) {
            List<Msg> fresh = since(seenSeq);
            seenSeq = top;
            DyeColor c = pickColor(fresh);
            if (c != null) {
                targetBlock = wool(c);
                targetColor = c;
                resetRound();
                if (debug.value) say(mc, "цель: " + c.name().toLowerCase(Locale.ROOT));
            }
        }
        if (targetBlock == null) { espPos = List.of(); release(mc); return; }

        World w = mc.world;

        if (esp.value && (++espTick % 2 == 0 || espPos.isEmpty())) scanEsp(w, p);
        if (!esp.value) espPos = List.of();

        if (delayLeft > 0) { delayLeft--; release(mc); return; }

        boolean onGround = p.isOnGround();

        // стоим ли на нужной шерсти
        BlockPos under = BlockPos.ofFloored(p.getX(), p.getY() - 0.2, p.getZ());
        boolean onTarget = w.getBlockState(under).isOf(targetBlock);
        if (onTarget) {
            mc.options.forwardKey.setPressed(false);
            mc.options.sprintKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
            mc.options.sneakKey.setPressed(sneakOnTarget.value);
            return;
        }
        mc.options.sneakKey.setPressed(false);

        // поиск цели
        if (targetPos == null || --rescanIn <= 0 || !w.getBlockState(targetPos).isOf(targetBlock)) {
            targetPos = findNearest(w, p);
            rescanIn = 10;
        }
        if (targetPos == null) { release(mc); return; }

        // поворот к центру блока
        double dx = targetPos.getX() + 0.5 - p.getX();
        double dz = targetPos.getZ() + 0.5 - p.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        float wantYaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
        float diff = MathHelper.wrapDegrees(wantYaw - p.getYaw());
        float step = turnSpeed.value;
        p.setYaw(p.getYaw() + MathHelper.clamp(diff, -step, step));

        boolean close = dist < 1.1; // у самой цели: без спринта, чтобы не перелететь за край
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(!close && dist > 3.0);

        // прыжок только когда реально нужен: впереди стена, или цель на блок выше. На ровном полу не прыгаем.
        boolean stepUp = targetPos.getY() > p.getBlockY();
        boolean jump = onGround && !close && (p.horizontalCollision || (stepUp && dist < 3.0));
        mc.options.jumpKey.setPressed(jump);
    }

    private void scanEsp(World w, ClientPlayerEntity p) {
        int r = Math.min(40, (int) range.value);
        BlockPos o = p.getBlockPos();
        List<BlockPos> found = new ArrayList<>();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int dy = -5; dy <= 3; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    m.set(o.getX() + dx, o.getY() + dy, o.getZ() + dz);
                    if (w.getBlockState(m).isOf(targetBlock)) found.add(m.toImmutable());
                }
            }
        }
        // та же метрика, что и у бота: ближайший по земле, высота весит больше, закрытые сверху хуже
        found.sort(Comparator.comparingDouble(q -> {
            double ax = q.getX() + 0.5 - p.getX(), ay = q.getY() - p.getY(), az = q.getZ() + 0.5 - p.getZ();
            double d = ax * ax + az * az + ay * ay * 4.0;
            if (!w.getBlockState(q.up()).isAir()) d += 4.0;
            return d;
        }));
        espPos = found.size() > 300 ? List.copyOf(found.subList(0, 300)) : List.copyOf(found);
    }

    private BlockPos findNearest(World w, ClientPlayerEntity p) {
        int r = (int) range.value;
        BlockPos o = p.getBlockPos();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int dy = -4; dy <= 2; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    m.set(o.getX() + dx, o.getY() + dy, o.getZ() + dz);
                    BlockState s = w.getBlockState(m);
                    if (!s.isOf(targetBlock)) continue;
                    double d = dx * dx + dz * dz + dy * dy * 4.0;
                    if (!w.getBlockState(m.up()).isAir()) d += 4.0; // сверху занято: берём, но свободные предпочитаем
                    if (d < bestD) { bestD = d; best = m.toImmutable(); }
                }
            }
        }
        return best;
    }

    private void release(MinecraftClient mc) {
        mc.options.forwardKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
    }

    private void logNew(MinecraftClient mc) {
        for (Msg m : since(loggedSeq)) {
            String ch = m.channel() == 0 ? "title" : m.channel() == 1 ? "subtitle" : "actionbar";
            say(mc, "[" + ch + "] " + m.text());
        }
        loggedSeq = latestSeq();
    }

    private static void say(MinecraftClient mc, String msg) {
        if (mc.inGameHud == null) return;
        mc.inGameHud.getChatHud().addMessage(Text.literal("[BlockWars] " + msg));
    }

    // ---------------- цвета для подсветки ----------------

    private static int woolRgb(DyeColor c) {
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
    private static int visibleRgb(DyeColor c) {
        int rgb = woolRgb(c);
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        int lum = (r * 3 + g * 6 + b) / 10;
        if (lum < 100) {
            int k = lum < 50 ? 65 : 45;
            r += (255 - r) * k / 100; g += (255 - g) * k / 100; b += (255 - b) * k / 100;
        }
        return (r << 16) | (g << 8) | b;
    }

    private static String ruName(DyeColor c) {
        return switch (c) {
            case WHITE -> "Белый"; case ORANGE -> "Оранжевый"; case MAGENTA -> "Пурпурный";
            case LIGHT_BLUE -> "Голубой"; case YELLOW -> "Жёлтый"; case LIME -> "Салатовый";
            case PINK -> "Розовый"; case GRAY -> "Серый"; case LIGHT_GRAY -> "Светло-серый";
            case CYAN -> "Бирюзовый"; case PURPLE -> "Фиолетовый"; case BLUE -> "Синий";
            case BROWN -> "Коричневый"; case GREEN -> "Зелёный"; case RED -> "Красный";
            case BLACK -> "Чёрный";
        };
    }

    // ---------------- ESP в мире ----------------

    @Override
    public boolean wantsWorldRender() { return ModuleManager.adminUnlocked() && esp.value && targetBlock != null && !espPos.isEmpty(); }

    @Override
    public void render(RenderContext c) {
        if (!ModuleManager.adminUnlocked() || !esp.value || espPos.isEmpty() || targetColor == null) return;
        float pulse = 0.5f + 0.5f * (float) Math.sin(c.now / 200.0);
        int rgb = visibleRgb(targetColor);
        Matrix4f m = c.m;
        Batch.begin(!throughWalls.value, false);
        Batch b = Batch.triangles();
        int max = Math.min(espPos.size(), 150);
        for (int i = 0; i < max; i++) {
            BlockPos p = espPos.get(i);
            boolean main = i == 0;
            float a = main ? 0.45f + 0.20f * pulse : 0.24f;
            double x0 = p.getX() + 0.02, x1 = p.getX() + 0.98;
            double z0 = p.getZ() + 0.02, z1 = p.getZ() + 0.98;
            double y0 = p.getY() + 0.01, y1 = p.getY() + 1.015;
            int fill = Batch.argb(rgb, a);
            quad(b, m, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, fill, fill, fill, fill);
            int bot = Batch.argb(rgb, 0f), top = Batch.argb(rgb, a * 0.7f);
            quad(b, m, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, bot, bot, top, top);
            quad(b, m, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, bot, bot, top, top);
            quad(b, m, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, bot, bot, top, top);
            quad(b, m, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, bot, bot, top, top);
            if (outline.value) {
                int line = Batch.argb(rgb, main ? 1f : 0.85f);
                double t = main ? 0.07 : 0.045, yy = y1 + 0.002;
                flat(b, m, x0, z0, x1, z0 + t, yy, line);
                flat(b, m, x0, z1 - t, x1, z1, yy, line);
                flat(b, m, x0, z0, x0 + t, z1, yy, line);
                flat(b, m, x1 - t, z0, x1, z1, yy, line);
                int pil = Batch.argb(rgb, 0.7f);
                double pt = 0.04;
                for (double[] cn : new double[][]{{x0, z0}, {x1, z0}, {x1, z1}, {x0, z1}}) {
                    quad(b, m, cn[0] - pt, y0, cn[1], cn[0] + pt, y0, cn[1], cn[0] + pt, y1, cn[1], cn[0] - pt, y1, cn[1], pil, pil, pil, pil);
                    quad(b, m, cn[0], y0, cn[1] - pt, cn[0], y0, cn[1] + pt, cn[0], y1, cn[1] + pt, cn[0], y1, cn[1] - pt, pil, pil, pil, pil);
                }
            }
        }
        // луч вверх над ближайшим блоком: видно издалека
        if (beam.value) {
            BlockPos p = espPos.get(0);
            double cx = p.getX() + 0.5, cz = p.getZ() + 0.5, by = p.getY() + 1.0, h = 40, r = 0.13;
            int lo = Batch.argb(rgb, 0.55f + 0.15f * pulse), hi = Batch.argb(rgb, 0f);
            for (int i = 0; i < 4; i++) {
                double a0 = i * Math.PI / 2 + Math.PI / 4, a1 = (i + 1) * Math.PI / 2 + Math.PI / 4;
                double ax = cx + Math.cos(a0) * r, az = cz + Math.sin(a0) * r;
                double bx = cx + Math.cos(a1) * r, bz = cz + Math.sin(a1) * r;
                quad(b, m, ax, by, az, bx, by, bz, bx, by + h, bz, ax, by + h, az, lo, lo, hi, hi);
            }
        }
        // объёмные стрелки остриём вниз над тремя ближайшими
        if (arrows.value) {
            int n = Math.min(3, espPos.size());
            for (int i = 0; i < n; i++) {
                BlockPos p = espPos.get(i);
                double bob = 0.14 * Math.sin(c.now / 230.0 + i * 1.3);
                arrow3d(b, m, p.getX() + 0.5, p.getY() + 1.45 + bob + (i == 0 ? 0.15 : 0), p.getZ() + 0.5, rgb, i == 0 ? 1.0 : 0.7);
            }
        }
        b.flush();
        Batch.end();
    }

    private static void flat(Batch b, Matrix4f m, double x0, double z0, double x1, double z1, double y, int col) {
        quad(b, m, x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1, col, col, col, col);
    }

    /** Четырёхугольник из двух треугольников. Вершины по часовой/против часовой: куллинг выключен. */
    private static void quad(Batch b, Matrix4f m,
                             double ax, double ay, double az, double bx, double by, double bz,
                             double cx, double cy, double cz, double dx, double dy, double dz,
                             int ca, int cb, int cc, int cd) {
        b.v(m, ax, ay, az, ca);
        b.v(m, bx, by, bz, cb);
        b.v(m, cx, cy, cz, cc);
        b.v(m, ax, ay, az, ca);
        b.v(m, cx, cy, cz, cc);
        b.v(m, dx, dy, dz, cd);
    }

    /** Стрелка остриём вниз: конус и стержень. y0 = остриё, k = масштаб. */
    private static void arrow3d(Batch b, Matrix4f m, double cx, double y0, double cz, int rgb, double k) {
        int col = Batch.argb(rgb, 0.97f);
        int dark = Batch.argb(Batch.mix(rgb, 0x000000, 0.35f), 0.97f);
        double ring = y0 + 0.5 * k, top = y0 + 1.1 * k, rc = 0.3 * k, rs = 0.1 * k;
        for (int i = 0; i < 4; i++) {
            double a0 = i * Math.PI / 2, a1 = (i + 1) * Math.PI / 2;
            double ax = cx + Math.cos(a0) * rc, az = cz + Math.sin(a0) * rc;
            double bx = cx + Math.cos(a1) * rc, bz = cz + Math.sin(a1) * rc;
            int side = (i & 1) == 0 ? col : dark;
            b.v(m, cx, y0, cz, side); b.v(m, ax, ring, az, side); b.v(m, bx, ring, bz, side);
            b.v(m, cx, ring, cz, dark); b.v(m, ax, ring, az, dark); b.v(m, bx, ring, bz, dark);
            double sx0 = cx + Math.cos(a0) * rs, sz0 = cz + Math.sin(a0) * rs;
            double sx1 = cx + Math.cos(a1) * rs, sz1 = cz + Math.sin(a1) * rs;
            b.v(m, sx0, ring, sz0, side); b.v(m, sx1, ring, sz1, side); b.v(m, sx1, top, sz1, side);
            b.v(m, sx0, ring, sz0, side); b.v(m, sx1, top, sz1, side); b.v(m, sx0, top, sz0, side);
            b.v(m, cx, top, cz, col); b.v(m, sx0, top, sz0, col); b.v(m, sx1, top, sz1, col);
        }
    }

    // ---------------- HUD: плашка цвета, расстояния, указатель за экраном ----------------

    @Override
    public void renderHud(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!ModuleManager.adminUnlocked() || targetColor == null || mc.player == null || mc.world == null || mc.options.hudHidden) return;
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        int rgb = visibleRgb(targetColor);
        ClientPlayerEntity pl = mc.player;

        boolean standing = targetBlock != null
                && mc.world.getBlockState(BlockPos.ofFloored(pl.getX(), pl.getY() - 0.2, pl.getZ())).isOf(targetBlock);

        // плашка сверху: какой цвет прочитан (и «стой», когда уже на нём)
        if (colorChip.value) {
            String name = ruName(targetColor) + (standing ? "  ✔ СТОЙ" : "");
            int tw = Hud.w(name) + 34;
            int x = sw / 2 - tw / 2, y = 10;
            Hud.chip(ctx, x, y, tw, 20);
            ctx.fill(x + 7, y + 4, x + 19, y + 16, 0xFF000000);
            ctx.fill(x + 8, y + 5, x + 18, y + 15, 0xFF000000 | woolRgb(targetColor));
            Hud.text(ctx, name, x + 25, y + 6, standing ? 0xFF55FF55 : (0xFF000000 | rgb));
        }

        if (!esp.value || espPos.isEmpty() || !Projection.valid()) return;

        // расстояния над ближайшими блоками, ближайший рисуется последним (сверху)
        boolean mainOnScreen = false;
        if (arrows.value) {
            int n = Math.min(espPos.size(), 5);
            for (int i = n - 1; i >= 0; i--) {
                BlockPos p = espPos.get(i);
                double cx = p.getX() + 0.5, cz = p.getZ() + 0.5;
                if (!Projection.project(cx, p.getY() + 2.4, cz, scr, sw, sh)) continue;
                if (i == 0) mainOnScreen = true;
                int px = Math.round(scr[0]), py = Math.round(scr[1]);
                int d = Math.round((float) Projection.distance(cx, p.getY() + 0.5, cz));
                boolean near = i == 0;
                String text = (near ? "ЦЕЛЬ  " : "") + d + " м";
                int tw = Hud.w(text) + 16;
                Hud.chip(ctx, px - tw / 2, py - 20, tw, 16);
                Hud.text(ctx, text, px - tw / 2 + 8, py - 16, near ? (0xFF000000 | rgb) : Ui.TEXT);
            }
        } else {
            BlockPos p0 = espPos.get(0);
            mainOnScreen = Projection.project(p0.getX() + 0.5, p0.getY() + 1.0, p0.getZ() + 0.5, scr, sw, sh);
        }

        // указатель по краю экрана, если ближайший блок не в кадре (сзади или сбоку)
        if (pointer.value && !mainOnScreen && !standing) {
            BlockPos p = espPos.get(0);
            double dx = p.getX() + 0.5 - pl.getX(), dz = p.getZ() + 0.5 - pl.getZ();
            float want = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
            float rel = MathHelper.wrapDegrees(want - pl.getYaw()); // 0 = прямо, + = справа
            double rad = Math.toRadians(rel);
            float radius = Math.min(sw, sh) / 2f - 46f;
            int px = Math.round(sw / 2f + (float) Math.sin(rad) * radius);
            int py = Math.round(sh / 2f - (float) Math.cos(rad) * radius);
            int d = Math.round((float) Math.sqrt(dx * dx + dz * dz));
            int col = 0xFF000000 | rgb;
            var ms = ctx.getMatrices();
            ms.push();
            ms.translate(px, py, 0);
            ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(rel));
            for (int r = 0; r < 12; r++) ctx.fill(-(r / 2 + 1), r - 6, r / 2 + 2, r - 5, 0xFF000000);
            for (int r = 1; r < 11; r++) ctx.fill(-(r / 2), r - 6, r / 2 + 1, r - 5, col);
            ms.pop();
            String t = d + " м";
            int tw = Hud.w(t) + 14;
            Hud.chip(ctx, px - tw / 2, py + 12, tw, 15);
            Hud.text(ctx, t, px - tw / 2 + 7, py + 15, col);
        }
    }
}
