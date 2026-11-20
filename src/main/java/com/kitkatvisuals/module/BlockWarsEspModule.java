package com.kitkatvisuals.module;

import com.kitkatvisuals.gui.Hud;
import com.kitkatvisuals.gui.Ui;
import com.kitkatvisuals.render.Batch;
import com.kitkatvisuals.render.Projection;
import com.kitkatvisuals.util.BlockWarsColor;
import net.minecraft.block.Block;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * BlockWarsESP: отдельная функция подсветки цели. Читает цвет из title / subtitle / actionbar,
 * подсвечивает всю шерсть этого цвета, ставит над ближайшими стрелки с расстоянием, луч над ближайшим
 * и указатель на краю экрана. Работает без бота: можно включать отдельно.
 */
public final class BlockWarsEspModule extends Module {
    private final Setting.Num range = add(new Setting.Num("range", "Range", 24f, 8f, 40f, 1f));
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
    }

    @Override
    public void onDisable() {
        targetColor = null;
        targetBlock = null;
        espPos = List.of();
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
                if (debug.value) say(mc, "цель: " + c.name().toLowerCase(Locale.ROOT));
            }
        }
        if (targetBlock == null) { espPos = List.of(); return; }
        if (++espTick % 2 == 0 || espPos.isEmpty()) scan(mc.world, p);
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
