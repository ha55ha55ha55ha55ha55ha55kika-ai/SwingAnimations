package com.kitkatvisuals.module;

import com.kitkatvisuals.gui.Hud;
import com.kitkatvisuals.gui.MarksScreen;
import com.kitkatvisuals.gui.Ui;
import com.kitkatvisuals.render.Batch;
import com.kitkatvisuals.render.Projection;
import com.kitkatvisuals.util.AnarchyDetector;
import com.kitkatvisuals.util.DeathTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Marks («Метки»): свои метки по координатам. В мире: световой луч, вращающийся кристалл и кольцо; на экране: ромб,
 * подпись с названием и расстоянием, стрелка у края экрана, когда метка не в кадре.
 * Каждая метка помнит сервер, измерение и анархию, на которой её поставили, и показывается только там
 * (метка без анархии, «Все», видна на любой анархии этого сервера). В списке (клавиша или кнопка в настройках)
 * у каждой метки подписана её анархия.
 * Хранится в скрытой настройке «list»: строка на метку, поля через табуляцию.
 */
public final class MarksModule extends Module implements WaypointSource {
    public static final int MAX_MARKS = 200;
    public static final int[] PALETTE = {0x4FC3F7, 0xFFB74D, 0x81C784, 0xBA68C8, 0xF06292, 0xFFF176, 0xFF5252, 0xFFFFFF};
    private static final int MAX_DRAWN = 32;

    public static final class Mark {
        public String name = "";
        public int x, y, z;
        public String dim = "";
        public String server = "";
        /** Номер анархии, на которой поставлена метка; 0 = видна на любой. */
        public int anarchy;
        public String anarchyLabel = "";
        public int rgb = PALETTE[0];
    }

    private final Setting.Bool beam = add(new Setting.Bool("beam", "3D marker in the world", true));
    private final Setting.Num height = add(new Setting.Num("height", "Beam height", 96f, 16f, 256f, 16f));
    private final Setting.Bool labels = add(new Setting.Bool("labels", "Name and distance", true));
    private final Setting.Bool arrows = add(new Setting.Bool("arrows", "Arrows at screen edge", true));
    private final Setting.Bool throughWalls = add(new Setting.Bool("through_walls", "Through walls", false));
    private final Setting.Bool allAnarchies = add(new Setting.Bool("all_anarchies", "Show marks from all anarchies", false));
    private final Setting.Bool addHere = add(new Setting.Bool("add_here", "Add mark here", false));
    private final Setting.Bool openList = add(new Setting.Bool("open", "Open marks list", false));
    private final Setting.Str list = add(new Setting.Str("list", "Marks", ""));

    private final List<Mark> marks = new ArrayList<>();
    private final List<Mark> visible = new ArrayList<>();
    private final float[] scr = new float[3];
    private String parsedFrom;
    private boolean dirty = true;
    private int timer;

    public MarksModule() {
        super("marks", "Marks", "hud");
    }

    // ---------------- данные ----------------

    /** Все метки (всех серверов и анархий). Список живой: менять только через методы модуля. */
    public List<Mark> all() {
        String raw = list.value == null ? "" : list.value;
        if (!raw.equals(parsedFrom)) {
            parsedFrom = raw;
            marks.clear();
            dirty = true;
            for (String line : raw.split("\n")) {
                String[] f = line.split("\t", -1);
                if (f.length < 9) continue;
                try {
                    Mark m = new Mark();
                    m.name = f[0];
                    m.x = Integer.parseInt(f[1]);
                    m.y = Integer.parseInt(f[2]);
                    m.z = Integer.parseInt(f[3]);
                    m.dim = f[4];
                    m.server = f[5];
                    m.anarchy = Integer.parseInt(f[6]);
                    m.anarchyLabel = f[7];
                    m.rgb = Integer.parseInt(f[8]) & 0xFFFFFF;
                    marks.add(m);
                } catch (NumberFormatException ignored) {
                    // битая строка пропускается
                }
                if (marks.size() >= MAX_MARKS) break;
            }
        }
        return marks;
    }

    private static String clean(String s) {
        if (s == null) return "";
        s = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > 40 ? s.substring(0, 40).trim() : s;
    }

    private void commit() {
        StringBuilder sb = new StringBuilder();
        for (Mark m : marks) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(clean(m.name)).append('\t').append(m.x).append('\t').append(m.y).append('\t').append(m.z).append('\t')
                    .append(m.dim).append('\t').append(m.server).append('\t').append(m.anarchy).append('\t')
                    .append(clean(m.anarchyLabel)).append('\t').append(m.rgb);
        }
        list.value = sb.toString();
        parsedFrom = list.value;
        dirty = true;
        ModuleManager.save();
    }

    /** Номер текущей анархии (0, если не определилась). */
    public int currentAnarchy(MinecraftClient mc) {
        return AnarchyDetector.detect(mc);
    }

    public Mark addMark(MinecraftClient mc, String name, int x, int y, int z, int anarchy) {
        List<Mark> l = all();
        while (l.size() >= MAX_MARKS) l.remove(0);
        String server = DeathTracker.serverKey(mc);
        int onServer = 0;
        for (Mark k : l) if (k.server.equals(server)) onServer++;
        Mark m = new Mark();
        m.name = clean(name).isEmpty() ? "Метка " + (onServer + 1) : clean(name);
        m.x = x;
        m.y = y;
        m.z = z;
        m.dim = DeathTracker.dimKey(mc);
        m.server = server;
        m.anarchy = anarchy;
        m.anarchyLabel = anarchy > 0 ? AnarchyDetector.label(mc) : "";
        m.rgb = PALETTE[onServer % PALETTE.length];
        l.add(m);
        commit();
        return m;
    }

    /** Метка на текущей позиции игрока, анархия берётся текущая. */
    public void addHere(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return;
        Mark m = addMark(mc, "", mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ(), currentAnarchy(mc));
        if (mc.inGameHud != null) {
            mc.inGameHud.setOverlayMessage(Text.literal("Метка «" + m.name + "» поставлена" + (m.anarchy > 0 ? " (анархия " + m.anarchy + ")" : "")), false);
        }
    }

    public void removeMark(Mark m) {
        if (marks.remove(m)) commit();
    }

    public void cycleColor(Mark m) {
        int i = 0;
        for (int k = 0; k < PALETTE.length; k++) if (PALETTE[k] == m.rgb) i = k;
        m.rgb = PALETTE[(i + 1) % PALETTE.length];
        commit();
    }

    /** Привязка метки: анархия anarchy (0 = видна везде). */
    public void setAnarchy(MinecraftClient mc, Mark m, int anarchy) {
        m.anarchy = anarchy;
        m.anarchyLabel = anarchy > 0 ? AnarchyDetector.label(mc) : "";
        commit();
    }

    /** Должна ли метка показываться прямо сейчас (сервер, измерение и анархия совпадают). */
    public boolean shownHere(Mark m, MinecraftClient mc) {
        if (mc.world == null) return false;
        if (!m.server.equals(DeathTracker.serverKey(mc)) || !m.dim.equals(DeathTracker.dimKey(mc))) return false;
        return allAnarchies.value || m.anarchy == 0 || m.anarchy == currentAnarchy(mc);
    }

    public void openList() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen instanceof MarksScreen) return;
        mc.setScreen(new MarksScreen(mc.currentScreen, this));
    }

    // ---------------- жизненный цикл ----------------

    @Override
    public void onEnable() { dirty = true; }

    @Override
    public void onDisable() { visible.clear(); }

    @Override
    public void tick(MinecraftClient mc) {
        if (openList.value) {
            openList.value = false;
            openList();
        }
        if (addHere.value) {
            addHere.value = false;
            addHere(mc);
        }
        if (dirty || ++timer >= 5) {
            timer = 0;
            dirty = false;
            visible.clear();
            if (mc.world != null) {
                for (Mark m : all()) if (shownHere(m, mc)) visible.add(m);
            }
        }
    }

    @Override
    public List<Waypoint> waypoints() {
        List<Waypoint> out = new ArrayList<>(visible.size());
        for (Mark m : visible) out.add(new Waypoint(m.x + 0.5, m.y, m.z + 0.5, m.name, m.rgb, false));
        return out;
    }

    // ---------------- мир ----------------

    @Override
    public boolean wantsWorldRender() { return beam.value && !visible.isEmpty(); }

    @Override
    public void render(RenderContext c) {
        int n = Math.min(visible.size(), MAX_DRAWN);
        for (int i = 0; i < n; i++) drawMark(c, visible.get(i), c.now);
    }

    /** Луч вверх, вращающийся кристалл над ним и бегущее кольцо на земле. */
    private void drawMark(RenderContext c, Mark k, long now) {
        int rgb = k.rgb;
        double cx = k.x + 0.5, cz = k.z + 0.5;
        float pulse = 0.8f + 0.2f * (float) Math.sin(now / 320.0 + k.x * 0.13);
        double h = height.value;
        double r = 0.16;
        Matrix4f m = c.m;
        Batch.begin(!throughWalls.value, true);
        Batch b = Batch.triangles();
        int lo = Batch.argb(rgb, 0.5f * pulse);
        int hi = Batch.argb(rgb, 0f);
        for (int i = 0; i < 4; i++) {
            double a0 = i * Math.PI / 2, a1 = (i + 1) * Math.PI / 2;
            double x0 = cx + Math.cos(a0) * r, z0 = cz + Math.sin(a0) * r;
            double x1 = cx + Math.cos(a1) * r, z1 = cz + Math.sin(a1) * r;
            b.v(m, x0, k.y, z0, lo); b.v(m, x1, k.y, z1, lo); b.v(m, x1, k.y + h, z1, hi);
            b.v(m, x0, k.y, z0, lo); b.v(m, x1, k.y + h, z1, hi); b.v(m, x0, k.y + h, z0, hi);
        }
        // кристалл (октаэдр), плавает и вращается
        double spin = now / 700.0;
        double dy = k.y + 2.6 + 0.12 * Math.sin(now / 450.0 + k.z * 0.1);
        double ar = 0.34, ah = 0.6;
        int top = Batch.argb(rgb, 0.85f * pulse);
        int mid = Batch.argb(rgb, 0.4f);
        int bot = Batch.argb(rgb, 0.7f * pulse);
        for (int i = 0; i < 4; i++) {
            double a0 = spin + i * Math.PI / 2, a1 = a0 + Math.PI / 2;
            double x0 = cx + Math.cos(a0) * ar, z0 = cz + Math.sin(a0) * ar;
            double x1 = cx + Math.cos(a1) * ar, z1 = cz + Math.sin(a1) * ar;
            b.v(m, cx, dy + ah, cz, top); b.v(m, x0, dy, z0, mid); b.v(m, x1, dy, z1, mid);
            b.v(m, cx, dy - ah, cz, bot); b.v(m, x1, dy, z1, mid); b.v(m, x0, dy, z0, mid);
        }
        b.flush();
        Batch s = Batch.strip();
        int seg = 36;
        double ringR = 0.75 + 0.06 * Math.sin(now / 400.0);
        for (int i = 0; i <= seg; i++) {
            double a = i * 2 * Math.PI / seg + spin;
            double fade = 0.5 + 0.5 * Math.sin(a * 2.0 - spin * 3.0);
            int col = Batch.argb(rgb, (float) (0.25 + 0.55 * fade) * pulse);
            s.v(m, cx + Math.cos(a) * (ringR - 0.06), k.y + 0.05, cz + Math.sin(a) * (ringR - 0.06), col);
            s.v(m, cx + Math.cos(a) * (ringR + 0.06), k.y + 0.05, cz + Math.sin(a) * (ringR + 0.06), col);
        }
        s.flush();
        Batch.end();
    }

    // ---------------- HUD ----------------

    private static String fmtDist(double d) {
        return d < 1000 ? Math.round(d) + " м" : String.format(Locale.ROOT, "%.1f км", d / 1000.0);
    }

    private static void diamond(DrawContext ctx, int px, int py, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int hw = r - Math.abs(dy);
            ctx.fill(px - hw, py + dy, px + hw + 1, py + dy + 1, color);
        }
    }

    @Override
    public void renderHud(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (visible.isEmpty() || mc.options.hudHidden || !Projection.valid()) return;
        int sw = ctx.getScaledWindowWidth();
        int sh = ctx.getScaledWindowHeight();
        long now = System.currentTimeMillis();
        int n = Math.min(visible.size(), MAX_DRAWN);
        for (int i = 0; i < n; i++) {
            Mark k = visible.get(i);
            double dist = Projection.distance(k.x + 0.5, k.y, k.z + 0.5);
            if (dist < 2.5) continue; // уже на месте
            float a = MathHelper.clamp((float) (dist - 2.5) / 4f, 0f, 1f);
            int st = Projection.projectOrEdge(k.x + 0.5, k.y + 2.6, k.z + 0.5, scr, sw, sh, 20);
            if (st == 1) drawLabel(ctx, k, dist, a, now);
            else if (st == 2 && arrows.value) drawArrow(ctx, k, dist, a, sw, sh);
        }
    }

    private void drawLabel(DrawContext ctx, Mark k, double dist, float a, long now) {
        int px = Math.round(scr[0]);
        int py = Math.round(scr[1]);
        int col = 0xFF000000 | k.rgb;
        float pulse = 0.5f + 0.5f * (float) Math.sin(now / 260.0 + k.x);
        int r = 5;
        diamond(ctx, px, py, r + 3, Ui.fade(col, (0.16f + 0.12f * pulse) * a));
        diamond(ctx, px, py, r + 1, Ui.fade(0xFF000000, 0.8f * a));
        diamond(ctx, px, py, r, Ui.fade(col, a));
        diamond(ctx, px, py, 2, Ui.fade(0xFFFFFFFF, 0.9f * a));
        if (!labels.value || a < 0.5f) return;
        String name = k.name;
        String d = fmtDist(dist);
        String tag = allAnarchies.value && k.anarchy > 0 ? "  A" + k.anarchy : "";
        int nameW = Hud.w(name);
        int distW = Hud.w(d + tag);
        int w = nameW + 8 + distW + 16;
        int cx = px - w / 2;
        int cy = py - r - 24;
        Hud.chip(ctx, cx, cy, w, 16);
        ctx.fill(cx + 6, cy + 13, cx + w - 6, cy + 14, Ui.fade(col, 0.55f)); // цветная черта снизу
        Hud.text(ctx, name, cx + 8, cy + 4, Ui.TEXT);
        Hud.text(ctx, d + tag, cx + 16 + nameW, cy + 4, col);
        ctx.fill(px, cy + 16, px + 1, py - r - 3, Ui.fade(col, 0.6f)); // «ножка» от подписи к ромбу
    }

    private void drawArrow(DrawContext ctx, Mark k, double dist, float a, int sw, int sh) {
        int col = 0xFF000000 | k.rgb;
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(scr[0], scr[1], 0f);
        m.multiply(new Quaternionf().rotateZ(scr[2]));
        for (int i = 0; i < 8; i++) { // стрелка вправо: чёрная подложка и цветная заливка
            int half = Math.round((7 - i) * 0.75f);
            ctx.fill(i - 4, -half - 1, i - 3, half + 2, 0xCC000000);
        }
        for (int i = 0; i < 7; i++) {
            int half = Math.round((6 - i) * 0.7f);
            ctx.fill(i - 3, -half, i - 2, half + 1, col);
        }
        m.pop();
        String d = fmtDist(dist);
        int tw = Hud.w(d);
        int tx = MathHelper.clamp(Math.round(scr[0]) - tw / 2, 2, sw - tw - 2);
        int ty = scr[1] < sh / 2f ? Math.round(scr[1]) + 8 : Math.round(scr[1]) - 16;
        Hud.text(ctx, d, tx, ty, Ui.fade(col, 0.95f));
    }
}
