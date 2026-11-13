package com.swinganimations.module;

import com.swinganimations.render.Batch;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;

/**
 * Маркер на цели: Кольцо, Ромб или Призраки. Цель = существо, которое вы ударили, и/или на которое наведён
 * прицел. Рисуется С проверкой глубины: сквозь стены не видно, это чисто косметика.
 */
public final class TargetEspModule extends Module {
    private final Setting.Mode mode = add(new Setting.Mode("mode", "Mode", "Ring", "Diamond", "Ghosts"));
    private final Setting.Num size = add(new Setting.Num("size", "Size", 1f, 0.5f, 1.5f, 0.05f));
    private final Setting.Num speed = add(new Setting.Num("speed", "Speed", 1f, 0.3f, 3f, 0.1f));
    private final Setting.Num hold = add(new Setting.Num("hold", "Hold time (ms)", 1500f, 300f, 5000f, 100f));
    private final Setting.Mode source = add(new Setting.Mode("source", "Target", "Attacked", "Crosshair", "Both"));
    private final Setting.Bool hurt = add(new Setting.Bool("hurt", "Flash red on hit", true));
    private final ColorSettings color = new ColorSettings(this);

    private Entity target;
    private long firstSeen;
    private long lastSeen;

    public TargetEspModule() {
        super("target_esp", "TargetESP", "render");
    }

    @Override
    public void onDisable() {
        target = null;
    }

    /** Вызывается из AttackEntityCallback (только клиент, пакеты не затрагиваются). */
    public void onAttack(Entity e) {
        if (!isEnabled() || source.is("Crosshair")) return;
        select(e);
    }

    private void select(Entity e) {
        if (!(e instanceof LivingEntity le) || !le.isAlive()) return;
        if (e == MinecraftClient.getInstance().player) return;
        long now = System.currentTimeMillis();
        if (e != target || now - lastSeen > hold.value + 400f) firstSeen = now;
        target = e;
        lastSeen = now;
    }

    @Override
    public void tick(MinecraftClient mc) {
        if (source.is("Attacked")) return;
        Entity t = mc.targetedEntity;
        if (t != null) select(t);
    }

    @Override
    public void render(RenderContext c) {
        Entity e = target;
        if (e == null) return;
        if (!e.isAlive() || e.isRemoved()) {
            target = null;
            return;
        }
        long now = c.now;
        float elapsed = now - lastSeen;
        float fade = elapsed <= hold.value ? 1f : 1f - (elapsed - hold.value) / 300f;
        if (fade <= 0f) {
            target = null;
            return;
        }
        float a = fade * Math.min(1f, (now - firstSeen) / 200f);

        double x = MathHelper.lerp(c.tickDelta, e.lastRenderX, e.getX());
        double y = MathHelper.lerp(c.tickDelta, e.lastRenderY, e.getY());
        double z = MathHelper.lerp(c.tickDelta, e.lastRenderZ, e.getZ());
        float w = e.getWidth();
        float h = e.getHeight();

        float hurtF = (hurt.value && e instanceof LivingEntity le) ? MathHelper.clamp(le.hurtTime / 10f, 0f, 1f) : 0f;
        int rgb = Batch.mix(color.rgb(now, 0f), 0xFF3030, hurtF);

        switch (mode.get()) {
            case "Diamond" -> diamond(c, x, y + h * 0.5, z, rgb, a, hurtF);
            case "Ghosts" -> ghosts(c, x, y, z, w, h, rgb, a);
            default -> ring(c, x, y, z, w, h, rgb, a);
        }
    }

    // ---------------- Кольцо ----------------

    private void ring(RenderContext c, double x, double y, double z, float w, float h, int rgb, float a) {
        double phase = c.now / 1000.0 * speed.value;
        float r = (w * 0.5f + 0.22f) * size.value;
        Batch.begin(true, true);
        for (int k = 0; k < 4; k++) {
            double t = 0.5 + 0.5 * Math.sin((phase - k * 0.14) * 2.2);
            double yy = y + h * t;
            float al = a * (k == 0 ? 0.95f : 0.45f / k);
            wall(c.m, x, yy - 0.14, z, r, 0.14f, Batch.argb(rgb, 0f), Batch.argb(rgb, al));
            wall(c.m, x, yy, z, r, 0.04f, Batch.argb(rgb, al), Batch.argb(rgb, 0f));
        }
        Batch.end();
    }

    private static void wall(Matrix4f m, double cx, double y0, double cz, float r, float hh, int cBottom, int cTop) {
        Batch s = Batch.strip();
        int segs = 48;
        for (int i = 0; i <= segs; i++) {
            double ang = Math.PI * 2.0 * i / segs;
            double px = cx + Math.cos(ang) * r;
            double pz = cz + Math.sin(ang) * r;
            s.v(m, px, y0, pz, cBottom);
            s.v(m, px, y0 + hh, pz, cTop);
        }
        s.flush();
    }

    // ---------------- Ромб ----------------

    private void diamond(RenderContext c, double x, double y, double z, int rgb, float a, float hurtF) {
        double phase = c.now / 1000.0 * speed.value;
        float ang = (float) (Math.sin(phase * 1.7) * Math.PI);
        float s = 0.45f * size.value * (1.3f - 0.45f * hurtF);
        Batch.begin(true, true);
        Batch t = Batch.triangles();
        rhombus(t, c, x, y, z, s * 1.7f, ang, Batch.argb(rgb, a * 0.30f), Batch.argb(rgb, 0f));
        rhombus(t, c, x, y, z, s, ang, Batch.argb(rgb, a * 0.95f), Batch.argb(rgb, a * 0.55f));
        t.flush();
        Batch.end();
    }

    private static void rhombus(Batch t, RenderContext c, double x, double y, double z, float s, float ang,
                                int centerCol, int rimCol) {
        float[] lx = {0f, 0.62f * s, 0f, -0.62f * s};
        float[] ly = {s, 0f, -s, 0f};
        float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            t.v(c.m, x, y, z, centerCol);
            vert(t, c, x, y, z, lx[i], ly[i], ca, sa, rimCol);
            vert(t, c, x, y, z, lx[j], ly[j], ca, sa, rimCol);
        }
    }

    private static void vert(Batch t, RenderContext c, double x, double y, double z,
                             float lx, float ly, float ca, float sa, int col) {
        float rx = lx * ca - ly * sa;
        float ry = lx * sa + ly * ca;
        t.v(c.m, x + c.rx * rx + c.ux * ry, y + c.ry * rx + c.uy * ry, z + c.rz * rx + c.uz * ry, col);
    }

    // ---------------- Призраки ----------------

    private void ghosts(RenderContext c, double x, double y, double z, float w, float h, int rgb, float a) {
        double phase = c.now / 1000.0 * speed.value;
        float orbit = (w * 0.5f + 0.35f) * size.value;
        Batch.begin(true, true);
        Batch t = Batch.triangles();
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 10; j++) {
                double th = phase * 2.2 + i * (Math.PI * 2.0 / 3.0) - j * 0.09;
                double yy = y + h * (0.45 + 0.35 * Math.sin(phase * 1.5 + i * 2.1 - j * 0.05));
                double px = x + Math.cos(th) * orbit;
                double pz = z + Math.sin(th) * orbit;
                float k = 1f - j / 10f;
                disc(t, c, px, yy, pz, 0.16f * size.value * (0.4f + 0.6f * k),
                        Batch.argb(rgb, a * 0.85f * k), Batch.argb(rgb, 0f));
            }
        }
        t.flush();
        Batch.end();
    }

    private static void disc(Batch t, RenderContext c, double x, double y, double z, float r, int centerCol, int rimCol) {
        int segs = 12;
        for (int i = 0; i < segs; i++) {
            double a0 = Math.PI * 2.0 * i / segs;
            double a1 = Math.PI * 2.0 * (i + 1) / segs;
            t.v(c.m, x, y, z, centerCol);
            edge(t, c, x, y, z, r, a0, rimCol);
            edge(t, c, x, y, z, r, a1, rimCol);
        }
    }

    private static void edge(Batch t, RenderContext c, double x, double y, double z, float r, double ang, int col) {
        float cx = (float) Math.cos(ang) * r;
        float cy = (float) Math.sin(ang) * r;
        t.v(c.m, x + c.rx * cx + c.ux * cy, y + c.ry * cx + c.uy * cy, z + c.rz * cx + c.uz * cy, col);
    }
}
