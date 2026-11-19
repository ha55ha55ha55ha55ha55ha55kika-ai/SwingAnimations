package com.kitkatvisuals.module;

import com.kitkatvisuals.render.Batch;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * ChinaHat: конусная «китайская шляпа» над головой игрока. Ванильной модели нет, геометрия рисуется прямо в мире:
 * конус из треугольников (градиент по альфе: у вершины ярче, у основания прозрачнее) и непрозрачная обводка по краю.
 * Чисто косметика: ничего не считает за игрока и не взаимодействует с сервером.
 */
public final class ChinaHatModule extends Module {
    private static final int SEGMENTS = 64;
    private static final double MAX_DIST = 64.0;

    private final ColorSettings color = new ColorSettings(this);
    private final Setting.Num radius = add(new Setting.Num("radius", "Radius", 0.7f, 0.3f, 1.5f, 0.05f));
    private final Setting.Num height = add(new Setting.Num("height", "Height", 0.45f, 0.1f, 1.0f, 0.05f));
    private final Setting.Num offsetY = add(new Setting.Num("offset_y", "Offset Y", 0.0f, -0.5f, 0.5f, 0.01f));
    private final Setting.Num alpha = add(new Setting.Num("alpha", "Opacity (%)", 55f, 5f, 100f, 1f));
    private final Setting.Bool outline = add(new Setting.Bool("outline", "Outline", true));
    private final Setting.Mode outlineColor = add(new Setting.Mode("outline_color", "Outline color", "Same", "White", "Black").<Setting.Mode>visibleWhen(() -> outline.value));
    private final Setting.Num spin = add(new Setting.Num("spin", "Spin speed", 1.0f, 0f, 5f, 0.1f));
    private final Setting.Bool bob = add(new Setting.Bool("bob", "Bobbing", false));
    private final Setting.Num bobRange = add(new Setting.Num("bob_range", "Bobbing range", 0.05f, 0.01f, 0.2f, 0.01f).<Setting.Num>visibleWhen(() -> bob.value));
    private final Setting.Bool showSelf = add(new Setting.Bool("show_self", "Show on me", true));
    private final Setting.Bool showOthers = add(new Setting.Bool("show_others", "Show on others", true));

    public ChinaHatModule() {
        super("china_hat", "ChinaHat", "render");
    }

    @Override
    public boolean wantsWorldRender() { return showSelf.value || showOthers.value; }

    @Override
    public void render(RenderContext c) {
        MinecraftClient mc = c.mc;
        if (mc.world == null || mc.player == null) return;
        boolean third = !mc.options.getPerspective().isFirstPerson();
        Vec3d cam = c.camera.getPos();
        Batch tris = null;
        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            boolean self = p == mc.player;
            if (self ? !(showSelf.value && third) : !showOthers.value) continue; // свою шляпу не рисуем от первого лица
            if (!shouldDraw(p) || (!self && p.squaredDistanceTo(cam) > MAX_DIST * MAX_DIST)) continue;
            if (tris == null) {
                Batch.begin(true, false);
                tris = Batch.triangles();
            }
            draw(tris, c, p);
        }
        if (tris != null) {
            tris.flush();
            Batch.end();
        }
    }

    /** Скрываем у невидимых, спектаторов и в позах, где шляпа торчала бы нелепо (плавание, ползание, полёт на элитрах, сон). */
    private static boolean shouldDraw(PlayerEntity p) {
        if (p.isRemoved() || p.isSpectator() || p.isInvisible()) return false;
        String pose = p.getPose().name();
        return switch (pose) {
            case "SWIMMING", "GLIDING", "FALL_FLYING", "SLEEPING", "DYING", "SPIN_ATTACK" -> false;
            default -> true;
        };
    }

    private void draw(Batch b, RenderContext c, PlayerEntity p) {
        double x = MathHelper.lerp(c.tickDelta, p.lastRenderX, p.getX());
        double y = MathHelper.lerp(c.tickDelta, p.lastRenderY, p.getY());
        double z = MathHelper.lerp(c.tickDelta, p.lastRenderZ, p.getZ());
        // getHeight() уже меньше при приседании, поэтому шляпа сама опускается вместе с головой
        double base = y + p.getHeight() + offsetY.value;
        if (bob.value) base += Math.sin(c.now / 350.0) * bobRange.value;
        double r = radius.value;
        double apexY = base + height.value;
        double spinAngle = (c.now % 3_600_000L) / 1000.0 * spin.value * 1.2;

        Matrix4f m = c.m;
        float a = alpha.value / 100f;
        int fillRgb = color.rgb(c.now, 0f);
        int apexCol = Batch.argb(fillRgb, a);

        double[] cx = new double[SEGMENTS + 1];
        double[] cz = new double[SEGMENTS + 1];
        int[] cols = new int[SEGMENTS + 1];
        for (int i = 0; i <= SEGMENTS; i++) {
            double ang = spinAngle + i * (Math.PI * 2.0 / SEGMENTS);
            cx[i] = x + Math.cos(ang) * r;
            cz[i] = z + Math.sin(ang) * r;
            // лёгкие полосы по окружности, чтобы вращение было видно даже при однотонном цвете
            int rgb = fillRgb;
            if (spin.value > 0f) {
                float band = 0.88f + 0.12f * (float) Math.sin(ang * 4.0);
                rgb = Batch.mix(0x000000, fillRgb, band);
            }
            cols[i] = Batch.argb(rgb, a * 0.35f);
        }
        for (int i = 0; i < SEGMENTS; i++) {
            b.v(m, x, apexY, z, apexCol);
            b.v(m, cx[i], base, cz[i], cols[i]);
            b.v(m, cx[i + 1], base, cz[i + 1], cols[i + 1]);
        }

        if (outline.value) {
            int rgb = outlineColor.is("White") ? 0xFFFFFF : outlineColor.is("Black") ? 0x000000 : fillRgb;
            int oc = Batch.argb(rgb, 1f);
            // полоска вдоль поверхности конуса у края: видна и сбоку, и сверху
            double t = 0.07;
            double ry = base + height.value * t;
            for (int i = 0; i < SEGMENTS; i++) {
                double ax = x + (cx[i] - x) * (1 - t), az = z + (cz[i] - z) * (1 - t);
                double bx = x + (cx[i + 1] - x) * (1 - t), bz = z + (cz[i + 1] - z) * (1 - t);
                b.v(m, cx[i], base, cz[i], oc);
                b.v(m, cx[i + 1], base, cz[i + 1], oc);
                b.v(m, ax, ry, az, oc);
                b.v(m, cx[i + 1], base, cz[i + 1], oc);
                b.v(m, bx, ry, bz, oc);
                b.v(m, ax, ry, az, oc);
            }
        }
    }
}
