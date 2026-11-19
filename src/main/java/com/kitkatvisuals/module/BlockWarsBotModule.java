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
        espPos = List.of();
    }

    @Override
    public void onDisable() {
        targetBlock = null;
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
                resetRound();
                if (debug.value) say(mc, "цель: " + c.name().toLowerCase(Locale.ROOT));
            }
        }
        if (targetBlock == null) { espPos = List.of(); release(mc); return; }

        World w = mc.world;

        if (esp.value && (++espTick % 4 == 0 || espPos.isEmpty())) scanEsp(w, p);
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
        for (int dy = -3; dy <= 3; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    m.set(o.getX() + dx, o.getY() + dy, o.getZ() + dz);
                    if (w.getBlockState(m).isOf(targetBlock)) found.add(m.toImmutable());
                }
            }
        }
        found.sort(Comparator.comparingDouble(q -> {
            double ax = q.getX() + 0.5 - p.getX(), ay = q.getY() + 0.5 - p.getY(), az = q.getZ() + 0.5 - p.getZ();
            return ax * ax + ay * ay + az * az;
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

    // ---------------- ESP в мире: подсветка блоков цвета ----------------

    @Override
    public boolean wantsWorldRender() { return ModuleManager.adminUnlocked() && esp.value && targetBlock != null && !espPos.isEmpty(); }

    @Override
    public void render(RenderContext c) {
        if (!ModuleManager.adminUnlocked() || !esp.value || espPos.isEmpty()) return;
        float pulse = 0.5f + 0.5f * (float) Math.sin(c.now / 220.0);
        int rgb = Ui.ACCENT & 0xFFFFFF;
        Batch.begin(true, true);
        Batch b = Batch.triangles();
        Matrix4f m = c.m;
        int max = Math.min(espPos.size(), 150);
        for (int i = 0; i < max; i++) {
            BlockPos p = espPos.get(i);
            float a = (i == 0 ? 0.55f : 0.28f) * (0.75f + 0.25f * pulse);
            double x0 = p.getX() + 0.03, x1 = p.getX() + 0.97;
            double z0 = p.getZ() + 0.03, z1 = p.getZ() + 0.97;
            double y0 = p.getY() + 0.01, y1 = p.getY() + 1.02;
            // верх
            quad(b, m,
                    x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1,
                    Batch.argb(rgb, a), Batch.argb(rgb, a), Batch.argb(rgb, a), Batch.argb(rgb, a));
            // стенки: снизу прозрачно, сверху ярко
            int bot = Batch.argb(rgb, 0f), top = Batch.argb(rgb, a * 0.6f);
            quad(b, m, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, bot, bot, top, top);
            quad(b, m, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, bot, bot, top, top);
            quad(b, m, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, bot, bot, top, top);
            quad(b, m, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, bot, bot, top, top);
        }
        b.flush();
        Batch.end();
    }

    /** Четырёхугольник из двух треугольников. Вершины по часовой/против часовой — куллинг выключен. */
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

    // ---------------- стрелки над блоками (как метки EventHud) ----------------

    @Override
    public void renderHud(DrawContext ctx, float tickDelta) {
        if (!ModuleManager.adminUnlocked() || !esp.value || !arrows.value || espPos.isEmpty() || !Projection.valid()) return;
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        int n = Math.min(espPos.size(), 5);
        for (int i = n - 1; i >= 0; i--) { // дальние рисуем первыми, ближайшая сверху
            BlockPos p = espPos.get(i);
            double cx = p.getX() + 0.5, cy = p.getY() + 1.0, cz = p.getZ() + 0.5;
            if (!Projection.project(cx, cy + 0.6, cz, scr, sw, sh)) continue;
            int px = Math.round(scr[0]);
            int py = Math.round(scr[1]);
            int d = Math.round((float) Projection.distance(cx, p.getY() + 0.5, cz));
            boolean near = i == 0;
            String text = (near ? "ЦЕЛЬ  " : "") + d + " м";
            int tw = Hud.w(text) + 16;
            Hud.chip(ctx, px - tw / 2, py - 24, tw, 16);
            Hud.text(ctx, text, px - tw / 2 + 8, py - 20, near ? Ui.ACCENT : Ui.TEXT);
            int ac = near ? Ui.ACCENT : 0x88FFFFFF;
            for (int r = 0; r < 4; r++) ctx.fill(px - (4 - r), py - 8 + r, px + (4 - r), py - 7 + r, ac);
        }
    }
}
