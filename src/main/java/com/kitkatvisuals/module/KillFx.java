package com.kitkatvisuals.module;

import com.kitkatvisuals.render.Batch;
import com.kitkatvisuals.util.Perf;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Эффекты убийства «Freeze», «Burn» и «Telekinesis» для KillEffectModule.
 * Тело цели заменяется процедурными кубами (по палитре сущности), которые живут по сценарию эффекта,
 * а в конце превращаются в осколки льда, пепел или улетают. Физика в тиках, рендер интерполируется.
 * Весь код клиентский, ничего не пишет в мир и не шлёт пакетов.
 */
final class KillFx {
    static final int FREEZE = 1, BURN = 2, TELE = 3;

    private static final class Shard {
        double x, y, z, px, py, pz, vx, vy, vz;
        float rx, ry, rz, prx, pry, prz, avx, avy, avz;
        float hx, hy, hz;
        int rgb;
        float alpha = 1f;
        boolean glow;     // true: мягкий билборд с аддитивным смешиванием (огонь, искры)
        boolean ground;   // true: сталкивается с блоками
        float gravity = 1f;
        float drag = 0.985f;
        int age, maxAge;
        boolean resting;
        float lx, ly, lz; // положение относительно ног (для частей тела)
    }

    private static final class Body {
        int type, age, dur, light = 15;
        double ex, ey, ez;
        float yaw, w, h;
        double kx, kz;
        final List<Shard> parts = new ArrayList<>();
    }

    private final Random rnd = new Random();
    private final List<Body> bodies = new ArrayList<>();
    private final List<Shard> shards = new ArrayList<>();

    private static final Matrix4f MAT = new Matrix4f();
    private static final Vector3f V = new Vector3f();
    private static final Vector3f NRM = new Vector3f();
    private static final int[][] FACE_V = {{1, 3, 7, 5}, {0, 2, 6, 4}, {2, 3, 7, 6}, {0, 1, 5, 4}, {4, 5, 7, 6}, {0, 1, 3, 2}};
    private static final float[][] FACE_N = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    private final float[] cx = new float[8], cy = new float[8], cz = new float[8];

    boolean active() { return !bodies.isEmpty() || !shards.isEmpty(); }

    void clear() {
        bodies.clear();
        shards.clear();
    }

    /** Сколько тиков живёт «тело» до финала эффекта. */
    static int duration(int type, float speed) {
        int base = type == FREEZE ? 44 : (type == BURN ? 38 : 52);
        return Math.max(12, Math.round(base / Math.max(0.3f, speed)));
    }

    private static float quality() { return Perf.quality() == 0 ? 1f : (Perf.quality() == 1 ? 0.55f : 0.3f); }

    private int shardCap() { return Perf.quality() == 0 ? 520 : (Perf.quality() == 1 ? 260 : 120); }

    // ---------------- запуск ----------------

    void spawn(int type, String id, double ex, double ey, double ez, float yaw, float w, float h,
               double kx, double kz, int light, float speed) {
        while (bodies.size() >= 6) bodies.remove(0);
        Body b = new Body();
        b.type = type;
        b.dur = duration(type, speed);
        b.ex = ex; b.ey = ey; b.ez = ez;
        b.yaw = yaw; b.w = w; b.h = h;
        b.kx = kx; b.kz = kz;
        b.light = light;
        int[] pal = KillEffectModule.palette(id);
        buildParts(b, id, pal);
        for (Shard s : b.parts) {
            double cos = Math.cos(yaw), sin = Math.sin(yaw);
            double ox = s.lx * cos - s.lz * sin, oz = s.lx * sin + s.lz * cos;
            s.x = s.px = ex + ox;
            s.y = s.py = ey + s.ly;
            s.z = s.pz = ez + oz;
            s.ry = s.pry = -yaw;
            if (type == FREEZE) s.rgb = Batch.mix(s.rgb, 0xA8E6FF, 0.6f);
        }
        bodies.add(b);
    }

    private void buildParts(Body b, String id, int[] pal) {
        float h = b.h, w = b.w;
        if (KillEffectModule.humanoid(id)) {
            float u = h / 32f;
            for (int side = -1; side <= 1; side += 2) {
                b.parts.add(part(side * 2 * u, 6 * u, 0, 2 * u, 6 * u, 2 * u, pal[2]));
                b.parts.add(part(side * 6 * u, 18 * u, 0, 2 * u, 6 * u, 2 * u, pal[3]));
            }
            b.parts.add(part(0, 18 * u, 0, 4 * u, 6 * u, 2 * u, pal[1]));
            b.parts.add(part(0, 28 * u, 0, 4 * u, 4 * u, 4 * u, pal[0]));
        } else {
            float hw = Math.max(0.06f, w * 0.34f);
            float len = Math.max(hw, w * 0.62f);
            b.parts.add(part(0, h * 0.55f, 0, hw, Math.max(0.05f, h * 0.2f), len, pal[1]));
            float hs = Math.max(0.05f, h * 0.17f);
            b.parts.add(part(0, h * 0.78f, len + hs * 0.6f, hs, hs, hs, pal[0]));
            if (h >= 0.4f) {
                float lh = Math.max(0.05f, h * 0.17f);
                float lw = Math.max(0.03f, w * 0.1f);
                for (int sx = -1; sx <= 1; sx += 2)
                    for (int sz = -1; sz <= 1; sz += 2)
                        b.parts.add(part(sx * w * 0.25f, lh, sz * len * 0.6f, lw, lh, lw, pal[2]));
            }
        }
    }

    private static Shard part(float lx, float ly, float lz, float hx, float hy, float hz, int rgb) {
        Shard s = new Shard();
        s.lx = lx; s.ly = ly; s.lz = lz;
        s.hx = hx; s.hy = hy; s.hz = hz;
        s.rgb = rgb;
        return s;
    }

    // ---------------- тики ----------------

    void tick(World w) {
        for (int i = bodies.size() - 1; i >= 0; i--) {
            Body b = bodies.get(i);
            b.age++;
            switch (b.type) {
                case FREEZE -> tickFreeze(b);
                case BURN -> tickBurn(b, w);
                default -> tickTele(b);
            }
            if (b.age >= b.dur) {
                finish(b);
                bodies.remove(i);
            }
        }
        for (int i = shards.size() - 1; i >= 0; i--) {
            Shard s = shards.get(i);
            tickShard(w, s);
            if (s.age > s.maxAge) shards.remove(i);
        }
        while (shards.size() > shardCap()) shards.remove(0);
    }

    private void tickFreeze(Body b) {
        for (Shard s : b.parts) {
            s.px = s.x; s.py = s.y; s.pz = s.z;
            s.prx = s.rx; s.pry = s.ry; s.prz = s.rz;
        }
        // иней: мелкие белые искры поднимаются от тела
        int n = Math.round(2 * quality());
        for (int k = 0; k < n; k++) {
            Shard s = new Shard();
            s.x = s.px = b.ex + (rnd.nextDouble() - 0.5) * b.w * 1.2;
            s.y = s.py = b.ey + rnd.nextDouble() * b.h;
            s.z = s.pz = b.ez + (rnd.nextDouble() - 0.5) * b.w * 1.2;
            s.vy = 0.01 + rnd.nextDouble() * 0.02;
            s.vx = (rnd.nextDouble() - 0.5) * 0.02;
            s.vz = (rnd.nextDouble() - 0.5) * 0.02;
            s.hx = s.hy = s.hz = 0.07f + rnd.nextFloat() * 0.05f;
            s.rgb = Batch.mix(0xFFFFFF, 0x8FDFFF, rnd.nextFloat());
            s.glow = true;
            s.gravity = 0f;
            s.maxAge = 16 + rnd.nextInt(10);
            shards.add(s);
        }
    }

    private void tickBurn(Body b, World w) {
        float t = b.age / (float) b.dur;
        for (Shard s : b.parts) {
            s.px = s.x; s.py = s.y; s.pz = s.z;
            s.prx = s.rx; s.pry = s.ry; s.prz = s.rz;
            // лёгкая дрожь, усиливается к финалу
            s.rx = (rnd.nextFloat() - 0.5f) * 0.12f * t;
            s.rz = (rnd.nextFloat() - 0.5f) * 0.12f * t;
        }
        int n = Math.round((3 + 4 * t) * quality());
        for (int k = 0; k < n; k++) {
            Shard s = new Shard();
            s.x = s.px = b.ex + (rnd.nextDouble() - 0.5) * b.w * 0.9;
            s.y = s.py = b.ey + rnd.nextDouble() * b.h * 0.9;
            s.z = s.pz = b.ez + (rnd.nextDouble() - 0.5) * b.w * 0.9;
            s.vy = 0.045 + rnd.nextDouble() * 0.04;
            s.vx = (rnd.nextDouble() - 0.5) * 0.02;
            s.vz = (rnd.nextDouble() - 0.5) * 0.02;
            s.hx = s.hy = s.hz = 0.14f + rnd.nextFloat() * 0.12f;
            s.rgb = Batch.mix(0xFF4A00, 0xFFC040, rnd.nextFloat());
            s.glow = true;
            s.gravity = 0f;
            s.maxAge = 12 + rnd.nextInt(10);
            shards.add(s);
        }
    }

    private void tickTele(Body b) {
        float age = b.age;
        float k = MathHelper.clamp(age / 22f, 0f, 1f);
        float ease = k * k * (3f - 2f * k);
        float spin = age * 0.045f * (1f + age / b.dur);
        float spread = 1f + 0.45f * ease;
        double cx0 = b.ex, cy0 = b.ey + b.h * 0.5, cz0 = b.ez;
        double cosT = Math.cos(spin), sinT = Math.sin(spin);
        double cosY = Math.cos(b.yaw), sinY = Math.sin(b.yaw);
        int idx = 0;
        for (Shard s : b.parts) {
            s.px = s.x; s.py = s.y; s.pz = s.z;
            s.prx = s.rx; s.pry = s.ry; s.prz = s.rz;
            double ox = s.lx * cosY - s.lz * sinY, oz = s.lx * sinY + s.lz * cosY;
            double rx = ox * cosT - oz * sinT, rz = ox * sinT + oz * cosT;
            double ry = s.ly - b.h * 0.5;
            double lift = ease * 1.3 + Math.sin(age * 0.16 + idx) * 0.05 * ease;
            s.x = cx0 + rx * spread;
            s.y = cy0 + ry * spread + lift;
            s.z = cz0 + rz * spread;
            s.ry = -(b.yaw + spin);
            s.rx = (float) Math.sin(age * 0.11 + idx * 1.7) * 0.22f * ease;
            s.rz = (float) Math.cos(age * 0.09 + idx * 1.3) * 0.22f * ease;
            idx++;
        }
        // искры по орбите
        int n = Math.round(2 * quality());
        for (int j = 0; j < n; j++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            double r = b.w * (0.8 + rnd.nextDouble() * 0.6);
            Shard s = new Shard();
            s.x = s.px = cx0 + Math.cos(a) * r;
            s.y = s.py = cy0 + (rnd.nextDouble() - 0.5) * b.h + ease * 1.3;
            s.z = s.pz = cz0 + Math.sin(a) * r;
            s.vx = -Math.sin(a) * 0.05;
            s.vz = Math.cos(a) * 0.05;
            s.vy = 0.02 + rnd.nextDouble() * 0.03;
            s.hx = s.hy = s.hz = 0.08f + rnd.nextFloat() * 0.06f;
            s.rgb = Batch.mix(0xB06BFF, 0x5CE1FF, rnd.nextFloat());
            s.glow = true;
            s.gravity = 0f;
            s.maxAge = 14 + rnd.nextInt(10);
            shards.add(s);
        }
    }

    private void finish(Body b) {
        float q = quality();
        switch (b.type) {
            case FREEZE -> {
                for (Shard p : b.parts) {
                    int n = Math.max(2, Math.round(7 * q));
                    for (int i = 0; i < n; i++) {
                        Shard s = new Shard();
                        s.x = s.px = p.x + (rnd.nextDouble() * 2 - 1) * p.hx;
                        s.y = s.py = p.y + (rnd.nextDouble() * 2 - 1) * p.hy;
                        s.z = s.pz = p.z + (rnd.nextDouble() * 2 - 1) * p.hz;
                        double dx = s.x - b.ex, dz = s.z - b.ez;
                        double dl = Math.max(0.05, Math.sqrt(dx * dx + dz * dz));
                        double sp = 0.08 + rnd.nextDouble() * 0.16;
                        s.vx = dx / dl * sp;
                        s.vz = dz / dl * sp;
                        s.vy = 0.08 + rnd.nextDouble() * 0.2;
                        float sz = MathHelper.clamp(Math.min(p.hx, Math.min(p.hy, p.hz)) * (0.35f + rnd.nextFloat() * 0.4f), 0.025f, 0.09f);
                        s.hx = sz; s.hy = sz * (0.6f + rnd.nextFloat() * 0.8f); s.hz = sz;
                        s.avx = (rnd.nextFloat() - 0.5f) * 0.7f;
                        s.avy = (rnd.nextFloat() - 0.5f) * 0.7f;
                        s.avz = (rnd.nextFloat() - 0.5f) * 0.7f;
                        s.rgb = Batch.mix(0xD8F6FF, 0x6FC4F0, rnd.nextFloat());
                        s.alpha = 0.85f;
                        s.ground = true;
                        s.maxAge = 45 + rnd.nextInt(30);
                        shards.add(s);
                    }
                }
                sparks(b.ex, b.ey + b.h * 0.5, b.ez, Math.round(26 * q), 0xFFFFFF, 0x8FDFFF, 0.14, 0.1f);
            }
            case BURN -> {
                int[] ash = {0x2A2A2A, 0x3A3A3A, 0x1C1C1C, 0x555555, 0x6A6A6A};
                for (Shard p : b.parts) {
                    int n = Math.max(3, Math.round(14 * q));
                    for (int i = 0; i < n; i++) {
                        Shard s = new Shard();
                        s.x = s.px = p.x + (rnd.nextDouble() * 2 - 1) * p.hx;
                        s.y = s.py = p.y + (rnd.nextDouble() * 2 - 1) * p.hy;
                        s.z = s.pz = p.z + (rnd.nextDouble() * 2 - 1) * p.hz;
                        s.vx = (rnd.nextDouble() - 0.5) * 0.07;
                        s.vz = (rnd.nextDouble() - 0.5) * 0.07;
                        s.vy = 0.02 + rnd.nextDouble() * 0.07;
                        float sz = 0.02f + rnd.nextFloat() * 0.035f;
                        s.hx = sz; s.hy = sz; s.hz = sz;
                        s.avx = (rnd.nextFloat() - 0.5f) * 0.3f;
                        s.avy = (rnd.nextFloat() - 0.5f) * 0.3f;
                        s.rgb = ash[rnd.nextInt(ash.length)];
                        s.gravity = 0.18f;
                        s.drag = 0.95f;
                        s.ground = true;
                        s.maxAge = 50 + rnd.nextInt(50);
                        shards.add(s);
                    }
                }
                sparks(b.ex, b.ey + b.h * 0.4, b.ez, Math.round(18 * q), 0xFF5A10, 0xFFC040, 0.1, 0.08f);
            }
            default -> {
                for (Shard p : b.parts) {
                    Shard s = new Shard();
                    s.x = s.px = p.x; s.y = s.py = p.y; s.z = s.pz = p.z;
                    s.rx = s.prx = p.rx; s.ry = s.pry = p.ry; s.rz = s.prz = p.rz;
                    s.hx = p.hx; s.hy = p.hy; s.hz = p.hz;
                    s.rgb = p.rgb;
                    double dx = p.x - b.ex, dz = p.z - b.ez;
                    s.vx = b.kx * 0.7 + dx * 0.12 + (rnd.nextDouble() - 0.5) * 0.08;
                    s.vz = b.kz * 0.7 + dz * 0.12 + (rnd.nextDouble() - 0.5) * 0.08;
                    s.vy = 0.12 + rnd.nextDouble() * 0.18;
                    s.avx = (rnd.nextFloat() - 0.5f) * 0.5f;
                    s.avy = (rnd.nextFloat() - 0.5f) * 0.5f;
                    s.avz = (rnd.nextFloat() - 0.5f) * 0.5f;
                    s.ground = true;
                    s.maxAge = 55 + rnd.nextInt(25);
                    shards.add(s);
                }
                sparks(b.ex, b.ey + b.h * 0.5 + 1.3, b.ez, Math.round(30 * q), 0xB06BFF, 0x5CE1FF, 0.18, 0.12f);
            }
        }
    }

    private void sparks(double x, double y, double z, int n, int rgbA, int rgbB, double speed, float size) {
        for (int i = 0; i < n; i++) {
            Shard s = new Shard();
            s.x = s.px = x; s.y = s.py = y; s.z = s.pz = z;
            double a = rnd.nextDouble() * Math.PI * 2;
            double sp = speed * (0.3 + rnd.nextDouble());
            s.vx = Math.cos(a) * sp;
            s.vz = Math.sin(a) * sp;
            s.vy = (rnd.nextDouble() - 0.3) * speed;
            s.hx = s.hy = s.hz = size * (0.6f + rnd.nextFloat() * 0.8f);
            s.rgb = Batch.mix(rgbA, rgbB, rnd.nextFloat());
            s.glow = true;
            s.gravity = 0.15f;
            s.drag = 0.94f;
            s.maxAge = 14 + rnd.nextInt(14);
            shards.add(s);
        }
    }

    // ---------------- физика осколков ----------------

    private static boolean solidAt(World w, double x, double y, double z) {
        BlockPos bp = BlockPos.ofFloored(x, y, z);
        BlockState st = w.getBlockState(bp);
        return !st.getCollisionShape(w, bp).isEmpty();
    }

    private static double surfaceY(World w, double x, double y, double z) {
        BlockPos bp = BlockPos.ofFloored(x, y, z);
        BlockState st = w.getBlockState(bp);
        VoxelShape sh = st.getCollisionShape(w, bp);
        if (sh.isEmpty()) return Double.NaN;
        double top = bp.getY() + sh.getMax(Direction.Axis.Y);
        return y < top ? top : Double.NaN;
    }

    private void tickShard(World w, Shard s) {
        s.px = s.x; s.py = s.y; s.pz = s.z;
        s.prx = s.rx; s.pry = s.ry; s.prz = s.rz;
        s.age++;
        if (s.resting) return;
        s.vy -= 0.065 * s.gravity;
        s.vx *= s.drag;
        s.vz *= s.drag;
        s.vy *= 0.995;
        double nx = s.x + s.vx, ny = s.y + s.vy, nz = s.z + s.vz;
        if (s.ground) {
            if (solidAt(w, nx, s.y, nz)) {
                s.vx *= -0.3;
                s.vz *= -0.3;
                nx = s.x;
                nz = s.z;
            }
            double hy = s.hy;
            double g = surfaceY(w, nx, ny - hy, nz);
            if (!Double.isNaN(g)) {
                ny = g + hy;
                if (s.vy < 0) s.vy = -s.vy > 0.14 ? -s.vy * 0.3 : 0.0;
                s.vx *= 0.7;
                s.vz *= 0.7;
                s.avx *= 0.6f; s.avy *= 0.6f; s.avz *= 0.6f;
                if (Math.abs(s.vx) + Math.abs(s.vz) < 0.01 && Math.abs(s.vy) < 0.01) s.resting = true;
            }
        }
        s.x = nx; s.y = ny; s.z = nz;
        s.rx += s.avx; s.ry += s.avy; s.rz += s.avz;
    }

    // ---------------- рендер ----------------

    void render(RenderContext c) {
        float td = c.tickDelta;

        // 1) непрозрачные части тел (лёд и пламя добавляются отдельными проходами)
        if (!bodies.isEmpty()) {
            Batch.begin(true, false);
            RenderSystem_depthMaskOn();
            Batch t = Batch.triangles();
            for (Body b : bodies) {
                float t01 = b.age / (float) b.dur;
                float light = 0.35f + 0.65f * b.light / 15f;
                for (Shard p : b.parts) {
                    int rgb = p.rgb;
                    if (b.type == BURN) rgb = Batch.mix(rgb, 0x141414, MathHelper.clamp(t01 * 1.4f, 0f, 1f));
                    float scale = b.type == TELE ? 1f : (b.type == BURN ? 1f - 0.18f * t01 * t01 : 1f);
                    box(t, c, p, td, rgb, 1f, light, scale);
                }
            }
            t.flush();
            Batch.end();
        }

        // 2) полупрозрачные: ледяная глыба и осколки
        boolean anyShard = false;
        for (Shard s : shards) if (!s.glow) { anyShard = true; break; }
        boolean anyFreeze = false;
        for (Body b : bodies) if (b.type == FREEZE) { anyFreeze = true; break; }
        if (anyShard || anyFreeze) {
            Batch.begin(true, false);
            Batch t = Batch.triangles();
            for (Body b : bodies) {
                if (b.type != FREEZE) continue;
                float cr = b.age / (float) b.dur;
                float a = 0.30f + 0.12f * (float) Math.sin(b.age * 0.4) + (cr > 0.8f ? (cr - 0.8f) * 1.2f : 0f);
                for (Shard p : b.parts) box(t, c, p, td, 0xCFF3FF, MathHelper.clamp(a, 0f, 0.6f), 1f, 1.12f);
            }
            for (Shard s : shards) {
                if (s.glow) continue;
                float fade = s.maxAge - s.age < 20 ? Math.max(0f, (s.maxAge - s.age) / 20f) : 1f;
                if (fade <= 0f) continue;
                box(t, c, s, td, s.rgb, s.alpha * fade, 1f, 1f);
            }
            t.flush();
            Batch.end();
        }

        // 3) свечение: огненный налёт на частях, пламя, искры
        boolean anyGlow = false;
        for (Shard s : shards) if (s.glow) { anyGlow = true; break; }
        boolean anyBurn = false;
        for (Body b : bodies) if (b.type == BURN) { anyBurn = true; break; }
        if (anyGlow || anyBurn) {
            Batch.begin(true, true);
            Batch t = Batch.triangles();
            for (Body b : bodies) {
                if (b.type != BURN) continue;
                float k = b.age / (float) b.dur;
                float a = 0.18f + 0.22f * (1f - k) + 0.08f * (float) Math.sin(b.age * 0.9);
                for (Shard p : b.parts) box(t, c, p, td, 0xFF6A10, MathHelper.clamp(a, 0f, 0.5f), 1f, 1.07f);
            }
            for (Shard s : shards) {
                if (!s.glow) continue;
                float life = s.age / (float) Math.max(1, s.maxAge);
                float a = MathHelper.clamp(1f - life, 0f, 1f) * 0.9f;
                float sz = s.hx * (1f - 0.55f * life);
                double x = MathHelper.lerp(td, s.px, s.x), y = MathHelper.lerp(td, s.py, s.y), z = MathHelper.lerp(td, s.pz, s.z);
                billboard(t, c, x, y, z, sz, s.rgb, a);
            }
            t.flush();
            Batch.end();
        }
    }

    private static void RenderSystem_depthMaskOn() {
        com.mojang.blaze3d.systems.RenderSystem.depthMask(true);
    }

    /** Мягкий светящийся квадрат: центр яркий, края прозрачные. */
    private static void billboard(Batch t, RenderContext c, double x, double y, double z, float s, int rgb, float a) {
        double rx = c.rx * s, ry = c.ry * s, rz = c.rz * s, ux = c.ux * s, uy = c.uy * s, uz = c.uz * s;
        int in = Batch.argb(rgb, a);
        int out = Batch.argb(rgb, 0f);
        double[][] k = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (int i = 0; i < 4; i++) {
            double[] p = k[i], q = k[(i + 1) % 4];
            t.v(c.m, x, y, z, in);
            t.v(c.m, x + rx * p[0] + ux * p[1], y + ry * p[0] + uy * p[1], z + rz * p[0] + uz * p[1], out);
            t.v(c.m, x + rx * q[0] + ux * q[1], y + ry * q[0] + uy * q[1], z + rz * q[0] + uz * q[1], out);
        }
    }

    private void box(Batch t, RenderContext c, Shard p, float td, int rgb, float alpha, float light, float scale) {
        double x = MathHelper.lerp(td, p.px, p.x), y = MathHelper.lerp(td, p.py, p.y), z = MathHelper.lerp(td, p.pz, p.z);
        float rx = MathHelper.lerp(td, p.prx, p.rx), ry = MathHelper.lerp(td, p.pry, p.ry), rz = MathHelper.lerp(td, p.prz, p.rz);
        MAT.identity().translation((float) x, (float) y, (float) z).rotateYXZ(ry, rx, rz);
        for (int i = 0; i < 8; i++) {
            MAT.transformPosition(((i & 1) == 0 ? -1f : 1f) * p.hx * scale, ((i & 2) == 0 ? -1f : 1f) * p.hy * scale,
                    ((i & 4) == 0 ? -1f : 1f) * p.hz * scale, V);
            cx[i] = V.x; cy[i] = V.y; cz[i] = V.z;
        }
        for (int f = 0; f < 6; f++) {
            MAT.transformDirection(FACE_N[f][0], FACE_N[f][1], FACE_N[f][2], NRM);
            float shade = 0.5f + 0.5f * MathHelper.clamp(NRM.x * 0.25f + NRM.y * 0.85f + NRM.z * 0.45f, 0f, 1f);
            int col = Batch.argb(Batch.mix(0x000000, rgb, Math.min(1f, shade * light)), alpha);
            int[] q = FACE_V[f];
            t.v(c.m, cx[q[0]], cy[q[0]], cz[q[0]], col);
            t.v(c.m, cx[q[1]], cy[q[1]], cz[q[1]], col);
            t.v(c.m, cx[q[2]], cy[q[2]], cz[q[2]], col);
            t.v(c.m, cx[q[0]], cy[q[0]], cz[q[0]], col);
            t.v(c.m, cx[q[2]], cy[q[2]], cz[q[2]], col);
            t.v(c.m, cx[q[3]], cy[q[3]], cz[q[3]], col);
        }
    }
}
