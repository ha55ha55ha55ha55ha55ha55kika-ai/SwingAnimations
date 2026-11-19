package com.kitkatvisuals.module;

import com.kitkatvisuals.render.Batch;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * WorldParticles: атмосферные частицы вокруг игрока (снег, пепел, пыль). Всё рисуется клиентом как билборды,
 * ничего не отправляется на сервер и на геймплей не влияет. Частицы падают с лёгким дрейфом или парят,
 * при быстром движении появляются дальше впереди, стоя на месте распределяются равномерно вокруг.
 */
public final class WorldParticlesModule extends Module {
    private static final int MAX_PARTICLES = 1500;
    private static final float SNOW_TEMP = 0.15f;

    private final Setting.Mode type = add(new Setting.Mode("type", "Particle type", "Snowflake", "Snow", "Ash", "White Ash", "Dust"));
    private final Setting.Mode condition = add(new Setting.Mode("condition", "Work mode", "Always", "Snowy biomes", "Snowfall"));
    private final Setting.Num density = add(new Setting.Num("density", "Density (per tick)", 3f, 1f, 10f, 1f));
    private final Setting.Num radius = add(new Setting.Num("radius", "Spawn radius", 12f, 3f, 20f, 1f));
    private final Setting.Num height = add(new Setting.Num("height", "Start height", 6f, 1f, 10f, 1f));
    private final Setting.Num fall = add(new Setting.Num("fall_speed", "Fall speed", 0.04f, 0.0f, 0.2f, 0.005f));
    private final Setting.Num drift = add(new Setting.Num("drift", "Drift", 0.015f, 0.0f, 0.1f, 0.005f));
    private final Setting.Num size = add(new Setting.Num("size", "Size", 0.07f, 0.02f, 0.3f, 0.01f));
    private final Setting.Bool customColor = add(new Setting.Bool("custom_color", "Custom color", false));
    private final Setting.Num red = add(new Setting.Num("red", "Red", 255f, 0f, 255f, 1f).<Setting.Num>visibleWhen(() -> customColor.value));
    private final Setting.Num green = add(new Setting.Num("green", "Green", 255f, 0f, 255f, 1f).<Setting.Num>visibleWhen(() -> customColor.value));
    private final Setting.Num blue = add(new Setting.Num("blue", "Blue", 255f, 0f, 255f, 1f).<Setting.Num>visibleWhen(() -> customColor.value));

    private static final class P {
        double x, y, z, px, py, pz;
        float phase, rot, rotSpeed, shade, aspect, fallMul, bvx, bvz;
        int age, life;
    }

    private final List<P> parts = new ArrayList<>();
    private final Random rng = new Random();

    public WorldParticlesModule() {
        super("world_particles", "WorldParticles", "render");
    }

    @Override
    public void onDisable() {
        parts.clear();
    }

    // ---------------- параметры типа ----------------

    private int kind() {
        return type.is("Snowflake") ? 0 : type.is("Snow") ? 1 : type.is("Ash") ? 2 : type.is("White Ash") ? 3 : 4;
    }

    private static int defaultColor(int kind) {
        return switch (kind) {
            case 0 -> 0xFFFFFF;
            case 1 -> 0xEEF3FF;
            case 2 -> 0x57575C;
            case 3 -> 0xC9C9CE;
            default -> 0xD9CDB4;
        };
    }

    /** Множитель падения: пыль почти парит, пепел падает медленнее снега. */
    private static float fallFactor(int kind) {
        return switch (kind) {
            case 1 -> 1.2f;
            case 2, 3 -> 0.6f;
            case 4 -> 0.2f;
            default -> 1f;
        };
    }

    private static float driftFactor(int kind) {
        return switch (kind) {
            case 1 -> 0.7f;
            case 2, 3 -> 1.3f;
            case 4 -> 1.6f;
            default -> 1f;
        };
    }

    private int rgb() {
        return customColor.value
                ? ((int) red.value << 16) | ((int) green.value << 8) | (int) blue.value
                : defaultColor(kind());
    }

    // ---------------- условия ----------------

    private boolean conditionOk(ClientWorld w, ClientPlayerEntity p) {
        if (condition.is("Always")) return true;
        boolean cold = w.getBiome(p.getBlockPos()).value().getTemperature() <= SNOW_TEMP;
        if (condition.is("Snowy biomes")) return cold;
        return cold && w.isRaining(); // снегопад: в холодном биоме идёт осадок
    }

    // ---------------- логика (20 раз в секунду) ----------------

    @Override
    public void tick(MinecraftClient mc) {
        ClientWorld w = mc.world;
        ClientPlayerEntity p = mc.player;
        if (w == null || p == null) {
            parts.clear();
            return;
        }
        int kind = kind();
        double rad = radius.value;
        double maxH2 = rad * 1.8 * rad * 1.8;
        float fallBase = fall.value * fallFactor(kind);
        float driftBase = drift.value * driftFactor(kind);

        for (Iterator<P> it = parts.iterator(); it.hasNext(); ) {
            P q = it.next();
            q.px = q.x;
            q.py = q.y;
            q.pz = q.z;
            q.age++;
            double wob = Math.sin(q.age * 0.11 + q.phase);
            double wob2 = Math.cos(q.age * 0.083 + q.phase * 1.7);
            q.x += q.bvx * driftBase + wob * driftBase * 0.6;
            q.z += q.bvz * driftBase + wob2 * driftBase * 0.6;
            q.y -= fallBase * q.fallMul;
            double dx = q.x - p.getX(), dz = q.z - p.getZ();
            if (q.age >= q.life || dx * dx + dz * dz > maxH2 || q.y < p.getY() - 8.0
                    || !w.getBlockState(BlockPos.ofFloored(q.x, q.y, q.z)).isAir()) {
                it.remove();
            }
        }

        if (!conditionOk(w, p)) return; // новые не создаём; уже летящие плавно доживают

        // при быстром движении центр спавна смещается вперёд по ходу, на месте частицы равномерно вокруг
        Vec3d v = p.getVelocity();
        double shift = Math.min(rad * 0.8, Math.sqrt(v.x * v.x + v.z * v.z) * 10.0);
        double len = Math.sqrt(v.x * v.x + v.z * v.z);
        double cx = p.getX() + (len > 1e-4 ? v.x / len * shift : 0.0);
        double cz = p.getZ() + (len > 1e-4 ? v.z / len * shift : 0.0);
        int n = (int) density.value;
        for (int i = 0; i < n && parts.size() < MAX_PARTICLES; i++) {
            double ang = rng.nextDouble() * Math.PI * 2.0;
            double rr = rad * Math.sqrt(rng.nextDouble());
            double x = cx + Math.cos(ang) * rr;
            double z = cz + Math.sin(ang) * rr;
            double y = p.getEyeY() + rng.nextDouble() * height.value;
            if (!w.getBlockState(BlockPos.ofFloored(x, y, z)).isAir()) continue;
            P q = new P();
            q.x = q.px = x;
            q.y = q.py = y;
            q.z = q.pz = z;
            q.phase = rng.nextFloat() * 6.2832f;
            q.rot = rng.nextFloat() * 6.2832f;
            q.rotSpeed = (rng.nextFloat() - 0.5f) * 0.06f;
            q.shade = 0.85f + rng.nextFloat() * 0.15f;
            q.aspect = 0.7f + rng.nextFloat() * 0.6f;
            q.fallMul = 0.7f + rng.nextFloat() * 0.6f;
            q.bvx = (rng.nextFloat() - 0.5f);
            q.bvz = (rng.nextFloat() - 0.5f);
            float fb = Math.max(0.004f, fallBase);
            q.life = MathHelper.clamp((int) ((height.value + 6.0) / fb), 40, 400);
            parts.add(q);
        }
    }

    // ---------------- отрисовка ----------------

    @Override
    public boolean wantsWorldRender() { return !parts.isEmpty(); }

    @Override
    public void render(RenderContext c) {
        if (parts.isEmpty() || c.mc.player == null) return;
        int kind = kind();
        int base = rgb();
        float s = size.value;
        double rad = radius.value;
        double px0 = c.mc.player.getX(), pz0 = c.mc.player.getZ();
        Matrix4f m = c.m;
        Batch.begin(true, false);
        Batch b = Batch.triangles();
        for (P q : parts) {
            double x = MathHelper.lerp(c.tickDelta, q.px, q.x);
            double y = MathHelper.lerp(c.tickDelta, q.py, q.y);
            double z = MathHelper.lerp(c.tickDelta, q.pz, q.z);
            double dx = x - px0, dz = z - pz0;
            double d = Math.sqrt(dx * dx + dz * dz);
            float fadeIn = Math.min(1f, q.age / 12f);
            float fadeOut = Math.min(1f, (q.life - q.age) / 20f);
            float edge = 1f - MathHelper.clamp((float) ((d - rad * 0.6) / (rad * 0.6)), 0f, 1f); // «туман»: к краю радиуса растворяются
            float a = 0.9f * fadeIn * fadeOut * edge;
            if (a <= 0.01f) continue;
            int rgb = Batch.mix(0x000000, base, q.shade);
            float ang = q.rot + q.rotSpeed * (q.age + c.tickDelta);
            float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
            // повёрнутые оси билборда
            float rx = c.rx * ca + c.ux * sa, ry = c.ry * ca + c.uy * sa, rz = c.rz * ca + c.uz * sa;
            float ux = -c.rx * sa + c.ux * ca, uy = -c.ry * sa + c.uy * ca, uz = -c.rz * sa + c.uz * ca;
            switch (kind) {
                case 0 -> star(b, m, x, y, z, rx, ry, rz, ux, uy, uz, s * 1.3f, Batch.argb(rgb, a));
                case 1 -> blob(b, m, x, y, z, rx, ry, rz, ux, uy, uz, s, Batch.argb(rgb, a), Batch.argb(rgb, a * 0.35f));
                case 4 -> blob(b, m, x, y, z, rx, ry, rz, ux, uy, uz, s * 1.6f, Batch.argb(rgb, a * 0.6f), Batch.argb(rgb, 0f));
                default -> flake(b, m, x, y, z, rx, ry, rz, ux, uy, uz, s, q.aspect, Batch.argb(rgb, a));
            }
        }
        b.flush();
        Batch.end();
    }

    private static void v(Batch b, Matrix4f m, double x, double y, double z,
                          float rx, float ry, float rz, float ux, float uy, float uz,
                          float ox, float oy, int col) {
        b.v(m, x + rx * ox + ux * oy, y + ry * ox + uy * oy, z + rz * ox + uz * oy, col);
    }

    /** Шестиконечная снежинка: два наложенных треугольника. */
    private static void star(Batch b, Matrix4f m, double x, double y, double z,
                             float rx, float ry, float rz, float ux, float uy, float uz, float r, int col) {
        for (int t = 0; t < 2; t++) {
            for (int k = 0; k < 3; k++) {
                double a = Math.toRadians(90 + t * 60 + k * 120);
                v(b, m, x, y, z, rx, ry, rz, ux, uy, uz, (float) Math.cos(a) * r, (float) Math.sin(a) * r, col);
            }
        }
    }

    /** Мягкое пятно: шестиугольник, в центре ярче, к краю прозрачнее. */
    private static void blob(Batch b, Matrix4f m, double x, double y, double z,
                             float rx, float ry, float rz, float ux, float uy, float uz, float r, int centre, int edge) {
        for (int k = 0; k < 6; k++) {
            double a0 = Math.toRadians(k * 60), a1 = Math.toRadians((k + 1) * 60);
            v(b, m, x, y, z, rx, ry, rz, ux, uy, uz, 0f, 0f, centre);
            v(b, m, x, y, z, rx, ry, rz, ux, uy, uz, (float) Math.cos(a0) * r, (float) Math.sin(a0) * r, edge);
            v(b, m, x, y, z, rx, ry, rz, ux, uy, uz, (float) Math.cos(a1) * r, (float) Math.sin(a1) * r, edge);
        }
    }

    /** Хлопья пепла: плоский повёрнутый прямоугольник неправильной пропорции. */
    private static void flake(Batch b, Matrix4f m, double x, double y, double z,
                              float rx, float ry, float rz, float ux, float uy, float uz, float r, float aspect, int col) {
        float w = r * aspect, h = r * (2f - aspect);
        v(b, m, x, y, z, rx, ry, rz, ux, uy, uz, -w, -h, col);
        v(b, m, x, y, z, rx, ry, rz, ux, uy, uz, w, -h, col);
        v(b, m, x, y, z, rx, ry, rz, ux, uy, uz, w, h, col);
        v(b, m, x, y, z, rx, ry, rz, ux, uy, uz, -w, -h, col);
        v(b, m, x, y, z, rx, ry, rz, ux, uy, uz, w, h, col);
        v(b, m, x, y, z, rx, ry, rz, ux, uy, uz, -w, h, col);
    }
}
