package com.kitkatvisuals.module;

import com.kitkatvisuals.gui.Hud;
import com.kitkatvisuals.gui.Ui;
import com.kitkatvisuals.render.Batch;
import com.kitkatvisuals.render.Projection;
import com.kitkatvisuals.util.BlockWarsColor;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.World;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * BlockWarsESP: отдельная функция подсветки цели. Читает цвет из title / subtitle / actionbar,
 * подсвечивает всю шерсть этого цвета, ставит над ближайшими стрелки с расстоянием, луч над ближайшим
 * и указатель на краю экрана. Строит самый короткий путь до ближайшего блока (обходит дыры и стены) и рисует его лентой
 * со стрелками на полу; указатель на краю экрана показывает направление пути. Работает без бота: можно включать отдельно.
 */
public final class BlockWarsEspModule extends Module {
    private final Setting.Num range = add(new Setting.Num("range", "Range", 24f, 8f, 40f, 1f));
    private final Setting.Bool pathLine = add(new Setting.Bool("path", "Path to nearest block", true));
    private final Setting.Bool arrows = add(new Setting.Bool("arrows", "Arrows above target blocks", true));
    private final Setting.Bool outline = add(new Setting.Bool("outline", "Outline target blocks", true));
    private final Setting.Bool beam = add(new Setting.Bool("beam", "Beam over nearest block", true));
    private final Setting.Bool pointer = add(new Setting.Bool("pointer", "Off-screen pointer", true));
    private final Setting.Bool colorChip = add(new Setting.Bool("color_chip", "Show read color on top", true));
    private final Setting.Bool throughWalls = add(new Setting.Bool("through_walls", "ESP through walls", true));
    private final Setting.Bool debug = add(new Setting.Bool("debug", "Log received texts to chat", false));

    private long seenSeq;
    private long loggedSeq;
    private DyeColor targetColor;
    private Block targetBlock;
    private List<BlockPos> espPos = List.of();
    private int espTick;
    /** Путь до ближайшего блока: точки {x, y(ноги), z}, от игрока к цели. null = пути нет. */
    private double[][] path;
    private BlockPos pathGoal;      // блок цели, к которому ведёт путь (он же главный в списке)
    private int pathTick;
    private BlockPos pathFrom;      // где стоял игрок при прошлом расчёте
    private boolean standingNow;
    private final float[] scr = new float[2];

    public BlockWarsEspModule() {
        super("block_wars_esp", "BlockWarsESP", "render");
    }

    @Override public boolean restoreOnLoad() { return false; }

    /** Скрыт так же, как BlockWarsBot: виден только после секретного кода в поиске меню. */
    @Override public boolean secret() { return true; }

    @Override
    public void onEnable() {
        seenSeq = BlockWarsColor.latestSeq(); // старые сообщения игнорируем
        loggedSeq = seenSeq;
        targetColor = null;
        targetBlock = null;
        espPos = List.of();
        path = null;
        pathGoal = null;
    }

    @Override
    public void onDisable() {
        targetColor = null;
        targetBlock = null;
        espPos = List.of();
        path = null;
        pathGoal = null;
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

        long top = BlockWarsColor.latestSeq();
        if (top != seenSeq) {
            List<BlockWarsColor.Msg> fresh = BlockWarsColor.since(seenSeq);
            seenSeq = top;
            DyeColor c = BlockWarsColor.pickColor(fresh);
            if (c != null) {
                targetColor = c;
                targetBlock = BlockWarsColor.wool(c);
                espPos = List.of();
                espTick = 0;
                path = null;
                pathGoal = null;
                pathFrom = null;
                if (debug.value) say(mc, "цель: " + c.name().toLowerCase(Locale.ROOT));
            }
        }
        if (targetBlock == null) { espPos = List.of(); path = null; pathGoal = null; return; }
        if (++espTick % 2 == 0 || espPos.isEmpty()) scan(mc.world, p);

        BlockPos under = BlockPos.ofFloored(p.getX(), p.getY() - 0.2, p.getZ());
        standingNow = mc.world.getBlockState(under).isOf(targetBlock);
        if (!pathLine.value || standingNow || espPos.isEmpty()) {
            path = null;
            pathGoal = null;
            return;
        }
        // путь пересчитываем не чаще раза в 3-8 тиков (чаще, пока игрок идёт и меняет блок)
        BlockPos here = p.getBlockPos();
        boolean moved = pathFrom == null || !pathFrom.equals(here);
        if (++pathTick >= (moved ? 3 : 8) || path == null) {
            pathTick = 0;
            pathFrom = here;
            computePath(mc.world, p);
        }
    }

    // ---------------- поиск пути ----------------

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int NONE = Integer.MIN_VALUE;
    private final BlockPos.Mutable pm = new BlockPos.Mutable();

    private static final class Node {
        final int x, y, z;
        final float g;
        Node(int x, int y, int z, float g) { this.x = x; this.y = y; this.z = z; this.g = g; }
    }

    private boolean passable(World w, int x, int y, int z) {
        pm.set(x, y, z);
        BlockState st = w.getBlockState(pm);
        return st.getCollisionShape(w, pm).isEmpty() && st.getFluidState().isEmpty();
    }

    private boolean solidBelow(World w, int x, int y, int z) {
        pm.set(x, y, z);
        return !w.getBlockState(pm).getCollisionShape(w, pm).isEmpty();
    }

    /** Можно ли стоять ногами в клетке (x, y, z): свободно на высоте ног и головы, твёрдый блок под ногами. */
    private boolean standable(World w, int x, int y, int z) {
        return passable(w, x, y, z) && passable(w, x, y + 1, z) && solidBelow(w, x, y - 1, z);
    }

    /** Куда попадёт шаг из (cx, cy, cz) в колонку (nx, nz): ровно, на блок выше или спуск до 3 блоков. NONE = нельзя. */
    private int stepY(World w, int cx, int cy, int cz, int nx, int nz) {
        if (standable(w, nx, cy, nz)) return cy;
        if (passable(w, cx, cy + 2, cz) && standable(w, nx, cy + 1, nz)) return cy + 1;
        if (!passable(w, nx, cy, nz) || !passable(w, nx, cy + 1, nz)) return NONE; // стена
        for (int d = 1; d <= 3; d++) if (standable(w, nx, cy - d, nz)) return cy - d;
        return NONE; // дыра
    }

    /**
     * Самый короткий путь до ближайшего нужного блока (по настоящей поверхности, а не по прямой): ищем волной от игрока,
     * пока не дойдём до клетки над любым блоком цвета. Обходит дыры и стены. Объём работы ограничен по узлам и по времени.
     */
    private void computePath(World w, ClientPlayerEntity pl) {
        long deadline = System.nanoTime() + 3_000_000L; // не больше ~3 мс за расчёт
        int sx = MathHelper.floor(pl.getX()), sz = MathHelper.floor(pl.getZ());
        int sy = MathHelper.floor(pl.getY() + 0.01);
        int startY = NONE;
        for (int k = 0; k <= 4; k++) if (standable(w, sx, sy - k, sz)) { startY = sy - k; break; }
        if (startY == NONE) { path = null; pathGoal = null; return; }

        // цели: клетки над ближайшими блоками цвета (только те, где можно встать)
        Set<Long> goals = new HashSet<>();
        Map<Long, BlockPos> goalBlock = new HashMap<>();
        int n = Math.min(espPos.size(), 80);
        for (int i = 0; i < n; i++) {
            BlockPos b = espPos.get(i);
            if (standable(w, b.getX(), b.getY() + 1, b.getZ())) {
                long k = BlockPos.asLong(b.getX(), b.getY() + 1, b.getZ());
                goals.add(k);
                goalBlock.put(k, b);
            }
        }
        if (goals.isEmpty()) { path = null; pathGoal = null; return; }

        int R = (int) range.value + 2;
        PriorityQueue<Node> pq = new PriorityQueue<>((a, b) -> Float.compare(a.g, b.g));
        Map<Long, Float> best = new HashMap<>();
        Map<Long, Long> parent = new HashMap<>();
        long startKey = BlockPos.asLong(sx, startY, sz);
        pq.add(new Node(sx, startY, sz, 0f));
        best.put(startKey, 0f);
        long goalKey = Long.MIN_VALUE;
        int expanded = 0;
        while (!pq.isEmpty() && expanded < 1600) {
            Node cur = pq.poll();
            long ck = BlockPos.asLong(cur.x, cur.y, cur.z);
            Float known = best.get(ck);
            if (known != null && cur.g > known + 1e-4f) continue;
            if (goals.contains(ck)) { goalKey = ck; break; }
            if ((++expanded & 31) == 0 && System.nanoTime() > deadline) break;
            for (int[] d : DIRS) {
                int nx = cur.x + d[0], nz = cur.z + d[1];
                if (Math.abs(nx - sx) > R || Math.abs(nz - sz) > R) continue;
                boolean diag = d[0] != 0 && d[1] != 0;
                if (diag && !(passable(w, cur.x + d[0], cur.y, cur.z) && passable(w, cur.x, cur.y, cur.z + d[1]))) continue; // без срезания углов
                int ny = stepY(w, cur.x, cur.y, cur.z, nx, nz);
                if (ny == NONE) continue;
                float g = cur.g + (diag ? 1.414f : 1f) + (ny != cur.y ? 0.5f : 0f);
                long nk = BlockPos.asLong(nx, ny, nz);
                Float old = best.get(nk);
                if (old != null && old <= g) continue;
                best.put(nk, g);
                parent.put(nk, ck);
                pq.add(new Node(nx, ny, nz, g));
            }
        }
        if (goalKey == Long.MIN_VALUE) { path = null; pathGoal = null; return; }

        // собираем путь от цели к игроку и разворачиваем
        List<double[]> pts = new ArrayList<>();
        long k = goalKey;
        int guard = 0;
        while (guard++ < 2000) {
            pts.add(new double[]{BlockPos.unpackLongX(k) + 0.5, BlockPos.unpackLongY(k), BlockPos.unpackLongZ(k) + 0.5});
            if (k == startKey) break;
            Long pk = parent.get(k);
            if (pk == null) break;
            k = pk;
        }
        Collections.reverse(pts);
        // выбрасываем промежуточные точки на прямых участках
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) {
            if (i == 0 || i == pts.size() - 1) { out.add(pts.get(i)); continue; }
            double[] a = pts.get(i - 1), b = pts.get(i), c = pts.get(i + 1);
            boolean straight = Math.abs((b[0] - a[0]) - (c[0] - b[0])) < 1e-6
                    && Math.abs((b[2] - a[2]) - (c[2] - b[2])) < 1e-6
                    && Math.abs((b[1] - a[1]) - (c[1] - b[1])) < 1e-6;
            if (!straight) out.add(b);
        }
        path = out.toArray(new double[0][]);
        pathGoal = goalBlock.get(goalKey);
        // главным (луч, ярлык «ЦЕЛЬ») делаем блок, к которому реально ведёт самый короткий путь
        if (pathGoal != null && !pathGoal.equals(espPos.get(0))) promote(pathGoal);
    }

    /** Ставит блок первым в списке, не меняя остальной порядок. */
    private void promote(BlockPos b) {
        List<BlockPos> l = new ArrayList<>(espPos);
        if (l.remove(b)) {
            l.add(0, b);
            espPos = List.copyOf(l);
        }
    }


    private void scan(World w, ClientPlayerEntity p) {
        int r = (int) range.value;
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
        if (pathGoal != null && found.remove(pathGoal)) found.add(0, pathGoal); // главный блок не прыгает между расчётами пути
        espPos = found.size() > 300 ? List.copyOf(found.subList(0, 300)) : List.copyOf(found);
    }

    private long dbgSeq() { return loggedSeq; }

    private void logNew(MinecraftClient mc) {
        for (BlockWarsColor.Msg m : BlockWarsColor.since(dbgSeq())) {
            String ch = m.channel() == 0 ? "title" : m.channel() == 1 ? "subtitle" : "actionbar";
            say(mc, "[" + ch + "] " + m.text());
        }
        loggedSeq = BlockWarsColor.latestSeq();
    }

    private static void say(MinecraftClient mc, String msg) {
        if (mc.inGameHud == null) return;
        mc.inGameHud.getChatHud().addMessage(Text.literal("[BlockWarsESP] " + msg));
    }

    // ---------------- ESP в мире ----------------

    @Override
    public boolean wantsWorldRender() { return ModuleManager.adminUnlocked() && targetBlock != null && !espPos.isEmpty(); }

    @Override
    public void render(RenderContext c) {
        if (!ModuleManager.adminUnlocked() || espPos.isEmpty() || targetColor == null) return;
        float pulse = 0.5f + 0.5f * (float) Math.sin(c.now / 200.0);
        int rgb = BlockWarsColor.visibleRgb(targetColor);
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
        // путь до ближайшего блока: лента и бегущие стрелки по полу
        double[][] pp = path;
        if (pathLine.value && pp != null && pp.length > 0) drawPath(b, m, pp, rgb, c);
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

    /** Лента пути (слабая сплошная линия) и бегущие к цели стрелки-шевроны. Начинается от ног игрока. */
    private void drawPath(Batch b, Matrix4f m, double[][] pts, int rgb, RenderContext c) {
        ClientPlayerEntity pl = c.mc.player;
        if (pl == null) return;
        double px = MathHelper.lerp(c.tickDelta, pl.lastRenderX, pl.getX());
        double pz = MathHelper.lerp(c.tickDelta, pl.lastRenderZ, pl.getZ());
        // пропускаем точки, которые игрок уже прошёл
        int i0 = 0;
        while (i0 < pts.length - 1) {
            double[] a = pts[i0], n2 = pts[i0 + 1];
            double dPlayer = Math.hypot(px - n2[0], pz - n2[2]);
            double dNode = Math.hypot(a[0] - n2[0], a[2] - n2[2]);
            if (dPlayer < dNode) i0++; else break;
        }
        int cnt = pts.length - i0 + 1;
        double[] xs = new double[cnt], ys = new double[cnt], zs = new double[cnt];
        xs[0] = px; ys[0] = pts[i0][1]; zs[0] = pz;
        for (int i = i0; i < pts.length; i++) {
            xs[i - i0 + 1] = pts[i][0];
            ys[i - i0 + 1] = pts[i][1];
            zs[i - i0 + 1] = pts[i][2];
        }
        double[] cum = new double[cnt];
        for (int i = 1; i < cnt; i++) cum[i] = cum[i - 1] + Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
        double total = cum[cnt - 1];
        if (total < 0.5) return;

        int line = Batch.argb(rgb, 0.38f);
        double hw = 0.05;
        for (int i = 1; i < cnt; i++) {
            double dx = xs[i] - xs[i - 1], dz = zs[i] - zs[i - 1], len = Math.hypot(dx, dz);
            if (len < 1e-6) continue;
            double nx = -dz / len * hw, nz = dx / len * hw;
            double y0 = ys[i - 1] + 0.035, y1 = ys[i] + 0.035;
            quad(b, m, xs[i - 1] - nx, y0, zs[i - 1] - nz, xs[i - 1] + nx, y0, zs[i - 1] + nz,
                    xs[i] + nx, y1, zs[i] + nz, xs[i] - nx, y1, zs[i] - nz, line, line, line, line);
        }

        // стрелки: шаг растёт на длинных путях, чтобы их было не больше ~48
        double spacing = Math.max(0.9, total / 48.0);
        double phase = (c.now % 1100L) / 1100.0 * spacing;
        int seg = 1;
        for (double sdist = phase; sdist < total; sdist += spacing) {
            while (seg < cnt - 1 && cum[seg] < sdist) seg++;
            double segLen = cum[seg] - cum[seg - 1];
            if (segLen < 1e-6) continue;
            double t = (sdist - cum[seg - 1]) / segLen;
            double dx = (xs[seg] - xs[seg - 1]) / segLen, dz = (zs[seg] - zs[seg - 1]) / segLen;
            double cx = xs[seg - 1] + (xs[seg] - xs[seg - 1]) * t;
            double cz = zs[seg - 1] + (zs[seg] - zs[seg - 1]) * t;
            double cy = ys[seg - 1] + (ys[seg] - ys[seg - 1]) * t + 0.05;
            double nx = -dz, nz = dx;
            float a = (float) Math.max(0.35, Math.min(1.0, 1.0 - sdist / 45.0));
            int col = Batch.argb(rgb, a);
            double tx = cx + dx * 0.30, tz = cz + dz * 0.30;                              // острие
            double lx = cx - dx * 0.16 + nx * 0.26, lz = cz - dz * 0.16 + nz * 0.26;      // левый край
            double rx = cx - dx * 0.16 - nx * 0.26, rz = cz - dz * 0.16 - nz * 0.26;      // правый край
            double qx = cx - dx * 0.02, qz = cz - dz * 0.02;                              // вырез сзади
            b.v(m, tx, cy, tz, col); b.v(m, lx, cy, lz, col); b.v(m, qx, cy, qz, col);
            b.v(m, tx, cy, tz, col); b.v(m, qx, cy, qz, col); b.v(m, rx, cy, rz, col);
        }
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
        int rgb = BlockWarsColor.visibleRgb(targetColor);
        ClientPlayerEntity pl = mc.player;

        boolean standing = targetBlock != null
                && mc.world.getBlockState(BlockPos.ofFloored(pl.getX(), pl.getY() - 0.2, pl.getZ())).isOf(targetBlock);

        // плашка сверху: какой цвет прочитан (и «стой», когда уже на нём)
        if (colorChip.value) {
            String name = BlockWarsColor.ruName(targetColor) + (standing ? "  ✔ СТОЙ" : "");
            int tw = Hud.w(name) + 34;
            int x = sw / 2 - tw / 2, y = 10;
            Hud.chip(ctx, x, y, tw, 20);
            ctx.fill(x + 7, y + 4, x + 19, y + 16, 0xFF000000);
            ctx.fill(x + 8, y + 5, x + 18, y + 15, 0xFF000000 | BlockWarsColor.woolRgb(targetColor));
            Hud.text(ctx, name, x + 25, y + 6, standing ? 0xFF55FF55 : (0xFF000000 | rgb));
        }

        if (espPos.isEmpty() || !Projection.valid()) return;

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
            double tx = p.getX() + 0.5, tz = p.getZ() + 0.5;
            double[][] pp = path;
            if (pathLine.value && pp != null && pp.length > 0) {
                // стрелка показывает, куда идти по пути (точка в ~3 блоках впереди), а не прямую до цели
                tx = pp[pp.length - 1][0];
                tz = pp[pp.length - 1][2];
                for (double[] pt : pp) {
                    if (Math.hypot(pt[0] - pl.getX(), pt[2] - pl.getZ()) >= 3.0) { tx = pt[0]; tz = pt[2]; break; }
                }
            }
            double dx = tx - pl.getX(), dz = tz - pl.getZ();
            float want = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
            float rel = MathHelper.wrapDegrees(want - pl.getYaw()); // 0 = прямо, + = справа
            double rad = Math.toRadians(rel);
            float radius = Math.min(sw, sh) / 2f - 46f;
            int px = Math.round(sw / 2f + (float) Math.sin(rad) * radius);
            int py = Math.round(sh / 2f - (float) Math.cos(rad) * radius);
            int d = Math.round((float) Math.hypot(p.getX() + 0.5 - pl.getX(), p.getZ() + 0.5 - pl.getZ()));
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
