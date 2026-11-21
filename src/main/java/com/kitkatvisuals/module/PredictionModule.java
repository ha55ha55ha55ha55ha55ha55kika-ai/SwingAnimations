package com.kitkatvisuals.module;

import com.kitkatvisuals.gui.Hud;
import com.kitkatvisuals.gui.Ui;
import com.kitkatvisuals.render.Batch;
import com.kitkatvisuals.render.Projection;
import com.kitkatvisuals.render.Trig;
import com.kitkatvisuals.util.Perf;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ChargedProjectilesComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Prediction: траектория лука, арбалета, трезубца, снежков, яиц, эндер-жемчуга, зелий, пузырьков опыта и заряда ветра.
 * Физика повторяет ваниль (скорость, гравитация, сопротивление воздуха и воды, смещение броска зелий, инерция игрока),
 * попадание считается по блокам и по хитбоксам живых сущностей. В мире рисуется светящийся 3D-луч с бегущей волной
 * и шариком-снарядом, маркер приземления и подсветка цели; на экране: дистанция, время полёта, сила натяжения.
 */
public final class PredictionModule extends Module {
    private record Kind(String name, double speed, double grav, double drag, double water, float roll, boolean bow) {}

    private static final Kind THROWN = new Kind("Throw", 1.5, 0.03, 0.99, 0.8, 0f, false);
    private static final Kind POTION = new Kind("Potion", 0.5, 0.05, 0.99, 0.8, -20f, false);
    private static final Kind XP = new Kind("XP bottle", 0.7, 0.07, 0.99, 0.8, -20f, false);
    private static final Kind WIND = new Kind("Wind charge", 1.5, 0.0, 1.0, 1.0, 0f, false);
    private static final Kind TRIDENT = new Kind("Trident", 2.5, 0.05, 0.99, 0.99, 0f, false);

    private static final int MAXP = 240;
    private static final int SMOOTH_MAX = (MAXP + 3) * 3;

    private final Setting.Bool bow = add(new Setting.Bool("bow", "Bow / Crossbow", true));
    private final Setting.Bool trident = add(new Setting.Bool("trident", "Trident", true));
    private final Setting.Bool throwables = add(new Setting.Bool("throwables", "Throwables", true));
    private final Setting.Num width = add(new Setting.Num("width", "Beam width", 0.05f, 0.02f, 0.2f, 0.01f));
    private final Setting.Num glow = add(new Setting.Num("glow", "Glow", 1f, 0.2f, 2f, 0.1f));
    private final Setting.Bool animated = add(new Setting.Bool("animated", "Animated pulse", true));
    private final Setting.Bool marker = add(new Setting.Bool("marker", "Landing marker", true));
    private final Setting.Bool hitBox = add(new Setting.Bool("hitbox", "Highlight target", true));
    private final Setting.Bool xray = add(new Setting.Bool("xray", "Show through blocks", false));
    private final Setting.Bool hudInfo = add(new Setting.Bool("hud", "Info on screen", true));
    private final Setting.Bool pip = add(new Setting.Bool("pip", "Mini viewport", true));
    private final Setting.Bool pipPearl = add(new Setting.Bool("pip_pearl", "Viewport: only ender pearl", true)
            .<Setting.Bool>visibleWhen(() -> this.pip.value));
    private final Setting.Mode pipView = add(new Setting.Mode("pip_view", "Viewport view", "Top", "Side")
            .<Setting.Mode>visibleWhen(() -> this.pip.value));
    private final Setting.Mode pipCorner = add(new Setting.Mode("pip_corner", "Viewport corner", "Top right", "Top left", "Bottom right", "Bottom left")
            .<Setting.Mode>visibleWhen(() -> this.pip.value));
    private final Setting.Num pipSize = add(new Setting.Num("pip_size", "Viewport size", 120f, 80f, 220f, 10f)
            .<Setting.Num>visibleWhen(() -> this.pip.value));
    private final Setting.Num pipRadius = add(new Setting.Num("pip_radius", "Viewport zoom (blocks)", 12f, 5f, 32f, 1f)
            .<Setting.Num>visibleWhen(() -> this.pip.value && this.pipView.is("Top")));
    private final ColorSettings color = new ColorSettings(this);

    // выбранный предмет
    private Kind kind;
    private boolean pearl;
    private float power;
    private boolean dim;

    // результат симуляции
    private final double[] px = new double[MAXP + 3], py = new double[MAXP + 3], pz = new double[MAXP + 3];
    private int n;
    private int hitType; // 0 нет, 1 блок, 2 сущность
    private double hx, hy, hz, nx, ny, nz;
    private Entity hitEntity;
    private double flightTicks;
    private double hitDist;
    private boolean valid;
    private long lastRender;

    private final double[] sx = new double[SMOOTH_MAX], sy = new double[SMOOTH_MAX], sz = new double[SMOOTH_MAX];
    private final float[] qx = new float[SMOOTH_MAX], qy = new float[SMOOTH_MAX], qz = new float[SMOOTH_MAX];
    private final float[] al = new float[SMOOTH_MAX];
    private final double[] cum = new double[SMOOTH_MAX];

    private final List<Entity> targets = new ArrayList<>();
    private long targetsTick = -1;
    private final float[] projOut = new float[2];

    public PredictionModule() {
        super("prediction", "Prediction", "render");
    }

    @Override
    public void onDisable() {
        valid = false;
        targets.clear();
        targetsTick = -1;
    }

    @Override
    public boolean wantsWorldRender() {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        return p != null && select(p, 0f);
    }

    // ---------------- выбор предмета ----------------

    private static Kind throwKind(Item it) {
        if (it == Items.SNOWBALL || it == Items.EGG || it == Items.ENDER_PEARL) return THROWN;
        if (it == Items.SPLASH_POTION || it == Items.LINGERING_POTION) return POTION;
        if (it == Items.EXPERIENCE_BOTTLE) return XP;
        if (it == Items.WIND_CHARGE) return WIND;
        return null;
    }

    private boolean select(ClientPlayerEntity p, float td) {
        pearl = false;
        Hand[] hands = {Hand.MAIN_HAND, Hand.OFF_HAND};
        for (Hand h : hands) {
            ItemStack st = p.getStackInHand(h);
            if (st.isEmpty()) continue;
            Item it = st.getItem();
            boolean using = p.isUsingItem() && p.getActiveHand() == h;
            if (bow.value && it == Items.BOW) {
                if (!using) continue;
                float t = (p.getItemUseTime() + td) / 20f;
                float f = Math.min(1f, (t * t + t * 2f) / 3f);
                if (f < 0.1f) return false;
                kind = new Kind("Arrow", f * 3.0, 0.05, 0.99, 0.6, 0f, true);
                power = f;
                dim = false;
                return true;
            }
            if (bow.value && it == Items.CROSSBOW) {
                if (!CrossbowItem.isCharged(st)) continue;
                ChargedProjectilesComponent cp = st.get(DataComponentTypes.CHARGED_PROJECTILES);
                if (cp != null && cp.contains(Items.FIREWORK_ROCKET)) continue;
                kind = new Kind("Arrow", 3.15, 0.05, 0.99, 0.6, 0f, true);
                power = 1f;
                dim = false;
                return true;
            }
            if (trident.value && it == Items.TRIDENT) {
                if (!using || p.getItemUseTime() < 10) continue;
                kind = TRIDENT;
                power = 1f;
                dim = false;
                return true;
            }
            if (throwables.value) {
                Kind k = throwKind(it);
                if (k != null) {
                    kind = k;
                    pearl = it == Items.ENDER_PEARL;
                    power = 1f;
                    dim = p.getItemCooldownManager().getCooldownProgress(st, 0f) > 0f;
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------- симуляция ----------------

    private int maxPoints() { return Perf.quality() == 0 ? MAXP : (Perf.quality() == 1 ? 150 : 100); }

    private void refreshTargets(ClientPlayerEntity p, World w) {
        long t = w.getTime();
        if (t == targetsTick) return;
        targetsTick = t;
        targets.clear();
        targets.addAll(w.getOtherEntities(p, p.getBoundingBox().expand(110.0),
                e -> e instanceof LivingEntity && e.canHit() && !e.isSpectator()));
    }

    private void addPoint(double x, double y, double z) {
        if (n >= px.length) return;
        px[n] = x;
        py[n] = y;
        pz[n] = z;
        n++;
    }

    private void simulate(ClientPlayerEntity p, Kind k, float td) {
        World w = p.getWorld();
        Vec3d eye = p.getCameraPosVec(td);
        double x = eye.x, y = eye.y - 0.1, z = eye.z;
        double yaw = Math.toRadians(p.getYaw(td));
        double pitch = Math.toRadians(p.getPitch(td));
        double ang = Math.toRadians(p.getPitch(td) + k.roll());
        double dx = -Math.sin(yaw) * Math.cos(pitch);
        double dy = -Math.sin(ang);
        double dz = Math.cos(yaw) * Math.cos(pitch);
        double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
        dx /= l;
        dy /= l;
        dz /= l;
        double vx = dx * k.speed(), vy = dy * k.speed(), vz = dz * k.speed();
        Vec3d pv = p.getVelocity();
        vx += pv.x;
        vz += pv.z;
        if (!p.isOnGround()) vy += pv.y;

        refreshTargets(p, w);
        n = 0;
        hitType = 0;
        hitEntity = null;
        flightTicks = 0;
        int max = maxPoints();
        for (int i = 0; i < max; i++) {
            addPoint(x, y, z);
            Vec3d from = new Vec3d(x, y, z);
            Vec3d to = new Vec3d(x + vx, y + vy, z + vz);
            double seg = Math.sqrt(vx * vx + vy * vy + vz * vz);

            BlockHitResult bh = w.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, p));
            boolean blockHit = bh.getType() != HitResult.Type.MISS;
            double bd = blockHit ? from.distanceTo(bh.getPos()) : Double.MAX_VALUE;

            Entity he = null;
            Vec3d hep = null;
            double ed = Double.MAX_VALUE;
            for (int e = 0; e < targets.size(); e++) {
                Entity ent = targets.get(e);
                if (ent.isRemoved()) continue;
                Box bb = ent.getBoundingBox().expand(0.3);
                Vec3d r;
                if (bb.contains(from)) {
                    r = from;
                } else {
                    Optional<Vec3d> o = bb.raycast(from, to);
                    if (o.isEmpty()) continue;
                    r = o.get();
                }
                double d = from.distanceTo(r);
                if (d < ed) {
                    ed = d;
                    he = ent;
                    hep = r;
                }
            }
            if (he != null && ed <= bd) {
                addPoint(hep.x, hep.y, hep.z);
                hitType = 2;
                hitEntity = he;
                hx = hep.x; hy = hep.y; hz = hep.z;
                nx = -vx / Math.max(seg, 1e-6);
                ny = -vy / Math.max(seg, 1e-6);
                nz = -vz / Math.max(seg, 1e-6);
                flightTicks = i + ed / Math.max(seg, 1e-6);
                break;
            }
            if (blockHit) {
                Vec3d hp = bh.getPos();
                addPoint(hp.x, hp.y, hp.z);
                hitType = 1;
                hx = hp.x; hy = hp.y; hz = hp.z;
                nx = bh.getSide().getOffsetX();
                ny = bh.getSide().getOffsetY();
                nz = bh.getSide().getOffsetZ();
                flightTicks = i + bd / Math.max(seg, 1e-6);
                break;
            }
            x += vx;
            y += vy;
            z += vz;
            boolean water = w.getFluidState(BlockPos.ofFloored(x, y, z)).isIn(FluidTags.WATER);
            double drag = water ? k.water() : k.drag();
            vx *= drag;
            vy *= drag;
            vz *= drag;
            vy -= k.grav();
            double ex = x - eye.x, ey = y - eye.y, ez = z - eye.z;
            if (y < w.getBottomY() - 8 || ex * ex + ey * ey + ez * ez > 220.0 * 220.0) {
                flightTicks = i + 1;
                break;
            }
            flightTicks = i + 1;
        }
        double ex = (hitType != 0 ? hx : px[Math.max(0, n - 1)]) - eye.x;
        double ey = (hitType != 0 ? hy : py[Math.max(0, n - 1)]) - eye.y;
        double ez = (hitType != 0 ? hz : pz[Math.max(0, n - 1)]) - eye.z;
        hitDist = Math.sqrt(ex * ex + ey * ey + ez * ez);
    }

    /** Catmull-Rom между тиковыми точками: плавная кривая вместо ломаной. */
    private int smooth(int k) {
        int m = 0;
        for (int i = 0; i < n - 1; i++) {
            int i0 = Math.max(0, i - 1), i1 = i, i2 = i + 1, i3 = Math.min(n - 1, i + 2);
            for (int s = 0; s < k; s++) {
                double t = (double) s / k, t2 = t * t, t3 = t2 * t;
                sx[m] = 0.5 * (2 * px[i1] + (-px[i0] + px[i2]) * t + (2 * px[i0] - 5 * px[i1] + 4 * px[i2] - px[i3]) * t2 + (-px[i0] + 3 * px[i1] - 3 * px[i2] + px[i3]) * t3);
                sy[m] = 0.5 * (2 * py[i1] + (-py[i0] + py[i2]) * t + (2 * py[i0] - 5 * py[i1] + 4 * py[i2] - py[i3]) * t2 + (-py[i0] + 3 * py[i1] - 3 * py[i2] + py[i3]) * t3);
                sz[m] = 0.5 * (2 * pz[i1] + (-pz[i0] + pz[i2]) * t + (2 * pz[i0] - 5 * pz[i1] + 4 * pz[i2] - pz[i3]) * t2 + (-pz[i0] + 3 * pz[i1] - 3 * pz[i2] + pz[i3]) * t3);
                m++;
            }
        }
        sx[m] = px[n - 1];
        sy[m] = py[n - 1];
        sz[m] = pz[n - 1];
        return m + 1;
    }

    // ---------------- мировой рендер ----------------

    @Override
    public void render(RenderContext c) {
        ClientPlayerEntity p = c.mc.player;
        valid = false;
        if (p == null || !select(p, c.tickDelta)) return;
        simulate(p, kind, c.tickDelta);
        if (n < 2) return;
        valid = true;
        lastRender = c.now;

        int sub = Perf.quality() == 0 ? 3 : (Perf.quality() == 1 ? 2 : 1);
        int m = smooth(sub);
        Vec3d cam = c.camera.getPos();
        int rgb = color.rgb(c.now, 0f);
        int hitRgb = hitType == 2 ? 0xFF5555 : rgb;
        float dimF = dim ? 0.35f : 1f;
        float hw = width.value;
        float g = glow.value;
        float tsec = (c.now % 100000L) / 1000f;
        boolean anim = animated.value;

        double total = 0;
        for (int i = 0; i < m; i++) {
            int a = Math.max(0, i - 1), b = Math.min(m - 1, i + 1);
            double tx = sx[b] - sx[a], ty = sy[b] - sy[a], tz = sz[b] - sz[a];
            double vx = sx[i] - cam.x, vy = sy[i] - cam.y, vz = sz[i] - cam.z;
            double ox = ty * vz - tz * vy, oy = tz * vx - tx * vz, oz = tx * vy - ty * vx;
            double ol = Math.sqrt(ox * ox + oy * oy + oz * oz);
            if (ol < 1e-9) { ox = 0; oy = 1; oz = 0; ol = 1; }
            qx[i] = (float) (ox / ol);
            qy[i] = (float) (oy / ol);
            qz[i] = (float) (oz / ol);
            if (i > 0) {
                double ddx = sx[i] - sx[i - 1], ddy = sy[i] - sy[i - 1], ddz = sz[i] - sz[i - 1];
                total += Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
            }
            cum[i] = total;
            double camDist = Math.sqrt(vx * vx + vy * vy + vz * vz);
            float fadeIn = MathHelper.clamp((float) (camDist - 1.0) / 3f, 0f, 1f);
            float pulse = anim ? 0.65f + 0.35f * (float) Math.sin(total * 1.8 - tsec * 8.0) : 1f;
            al[i] = fadeIn * pulse * dimF;
        }

        Batch.begin(!xray.value, true);
        // свечение (широкая мягкая лента) и ядро (узкая яркая), каждая в один draw call
        Batch glowStrip = Batch.strip();
        float gw = hw * 3.4f;
        for (int row = 0; row < 2; row++) {
            glowStrip.restart();
            for (int i = 0; i < m; i++) {
                float a = al[i] * 0.3f * g;
                if (row == 0) {
                    glowStrip.v(c.m, sx[i] - qx[i] * gw, sy[i] - qy[i] * gw, sz[i] - qz[i] * gw, Batch.argb(rgb, 0f));
                    glowStrip.v(c.m, sx[i], sy[i], sz[i], Batch.argb(rgb, a));
                } else {
                    glowStrip.v(c.m, sx[i], sy[i], sz[i], Batch.argb(rgb, a));
                    glowStrip.v(c.m, sx[i] + qx[i] * gw, sy[i] + qy[i] * gw, sz[i] + qz[i] * gw, Batch.argb(rgb, 0f));
                }
            }
        }
        glowStrip.flush();

        Batch core = Batch.strip();
        int coreRgb = Batch.mix(rgb, 0xFFFFFF, 0.65f);
        for (int row = 0; row < 2; row++) {
            core.restart();
            for (int i = 0; i < m; i++) {
                float a = al[i];
                if (row == 0) {
                    core.v(c.m, sx[i] - qx[i] * hw, sy[i] - qy[i] * hw, sz[i] - qz[i] * hw, Batch.argb(coreRgb, a * 0.15f));
                    core.v(c.m, sx[i], sy[i], sz[i], Batch.argb(coreRgb, a * 0.95f));
                } else {
                    core.v(c.m, sx[i], sy[i], sz[i], Batch.argb(coreRgb, a * 0.95f));
                    core.v(c.m, sx[i] + qx[i] * hw, sy[i] + qy[i] * hw, sz[i] + qz[i] * hw, Batch.argb(coreRgb, a * 0.15f));
                }
            }
        }
        core.flush();

        Batch t = Batch.triangles();
        // шарик-снаряд бежит по траектории: наглядно показывает движение и скорость
        if (anim && total > 0.5) {
            double u = ((c.now % 1400L) / 1400.0) * total;
            int idx = 0;
            while (idx < m - 1 && cum[idx] < u) idx++;
            billboard(t, c, sx[idx], sy[idx], sz[idx], 0.20f, Batch.argb(rgb, 0.35f * dimF));
            billboard(t, c, sx[idx], sy[idx], sz[idx], 0.09f, Batch.argb(0xFFFFFF, 0.9f * dimF));
        }
        if (marker.value && hitType != 0) drawMarker(t, c, hitRgb, tsec, dimF);
        if (hitBox.value && hitType == 2 && hitEntity != null && !hitEntity.isRemoved()) drawBox(t, c, hitEntity.getBoundingBox().expand(0.03), hitRgb, cam, dimF);
        t.flush();
        Batch.end();
    }

    private static void billboard(Batch t, RenderContext c, double x, double y, double z, float s, int argb) {
        double ax = c.rx * s, ay = c.ry * s, az = c.rz * s;
        double bx = c.ux * s, by = c.uy * s, bz = c.uz * s;
        t.v(c.m, x - ax - bx, y - ay - by, z - az - bz, argb);
        t.v(c.m, x + ax - bx, y + ay - by, z + az - bz, argb);
        t.v(c.m, x + ax + bx, y + ay + by, z + az + bz, argb);
        t.v(c.m, x - ax - bx, y - ay - by, z - az - bz, argb);
        t.v(c.m, x + ax + bx, y + ay + by, z + az + bz, argb);
        t.v(c.m, x - ax + bx, y - ay + by, z - az + bz, argb);
    }

    /** Кольцо и мягкий диск в плоскости поверхности попадания. */
    private void drawMarker(Batch t, RenderContext c, int rgb, float tsec, float dimF) {
        double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl < 1e-6) return;
        double ax = nx / nl, ay = ny / nl, az = nz / nl;
        double rx = Math.abs(ay) < 0.9 ? 0 : 1, ry = Math.abs(ay) < 0.9 ? 1 : 0, rz = 0;
        double ux = ay * rz - az * ry, uy = az * rx - ax * rz, uz = ax * ry - ay * rx;
        double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul; uy /= ul; uz /= ul;
        double vx = ay * uz - az * uy, vy = az * ux - ax * uz, vz = ax * uy - ay * ux;
        double cx = hx + ax * 0.02, cy = hy + ay * 0.02, cz = hz + az * 0.02;
        double rad = 0.38 + 0.05 * Math.sin(tsec * 5.0);
        int segs = Perf.quality() == 0 ? 32 : 18;
        double[] cs = Trig.cos(segs);
        double[] sn = Trig.sin(segs);
        int cc = Batch.argb(rgb, 0.22f * dimF);
        int ce = Batch.argb(rgb, 0.04f * dimF);
        int rEdge = Batch.argb(rgb, 0.15f * dimF);
        int rMid = Batch.argb(rgb, 0.95f * dimF);
        double r0 = rad - 0.05, r1 = rad + 0.05;
        for (int i = 0; i < segs; i++) {
            int j = i + 1;
            double d0x = cs[i] * ux + sn[i] * vx, d0y = cs[i] * uy + sn[i] * vy, d0z = cs[i] * uz + sn[i] * vz;
            double d1x = cs[j] * ux + sn[j] * vx, d1y = cs[j] * uy + sn[j] * vy, d1z = cs[j] * uz + sn[j] * vz;
            // диск
            t.v(c.m, cx, cy, cz, cc);
            t.v(c.m, cx + d0x * rad, cy + d0y * rad, cz + d0z * rad, ce);
            t.v(c.m, cx + d1x * rad, cy + d1y * rad, cz + d1z * rad, ce);
            // кольцо: внутренний край → середина → внешний край
            quad(t, c, cx, cy, cz, d0x, d0y, d0z, d1x, d1y, d1z, r0, rad, rEdge, rMid);
            quad(t, c, cx, cy, cz, d0x, d0y, d0z, d1x, d1y, d1z, rad, r1, rMid, rEdge);
        }
    }

    private static void quad(Batch t, RenderContext c, double cx, double cy, double cz,
                             double d0x, double d0y, double d0z, double d1x, double d1y, double d1z,
                             double ra, double rb, int ca, int cb) {
        t.v(c.m, cx + d0x * ra, cy + d0y * ra, cz + d0z * ra, ca);
        t.v(c.m, cx + d0x * rb, cy + d0y * rb, cz + d0z * rb, cb);
        t.v(c.m, cx + d1x * rb, cy + d1y * rb, cz + d1z * rb, cb);
        t.v(c.m, cx + d0x * ra, cy + d0y * ra, cz + d0z * ra, ca);
        t.v(c.m, cx + d1x * rb, cy + d1y * rb, cz + d1z * rb, cb);
        t.v(c.m, cx + d1x * ra, cy + d1y * ra, cz + d1z * ra, ca);
    }

    /** Полупрозрачные грани и светящиеся рёбра хитбокса цели. */
    private void drawBox(Batch t, RenderContext c, Box b, int rgb, Vec3d cam, float dimF) {
        double x0 = b.minX, y0 = b.minY, z0 = b.minZ, x1 = b.maxX, y1 = b.maxY, z1 = b.maxZ;
        int fill = Batch.argb(rgb, 0.12f * dimF);
        face(t, c, fill, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        face(t, c, fill, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
        face(t, c, fill, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0);
        face(t, c, fill, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        face(t, c, fill, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        face(t, c, fill, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0);
        int edge = Batch.argb(rgb, 0.85f * dimF);
        double w = 0.012;
        for (int i = 0; i < 4; i++) {
            double ex = (i & 1) == 0 ? x0 : x1, ez = (i & 2) == 0 ? z0 : z1;
            ribbon(t, c, cam, ex, y0, ez, ex, y1, ez, w, edge);
        }
        for (int i = 0; i < 4; i++) {
            double ey = (i & 1) == 0 ? y0 : y1, ez = (i & 2) == 0 ? z0 : z1;
            ribbon(t, c, cam, x0, ey, ez, x1, ey, ez, w, edge);
        }
        for (int i = 0; i < 4; i++) {
            double ex = (i & 1) == 0 ? x0 : x1, ey = (i & 2) == 0 ? y0 : y1;
            ribbon(t, c, cam, ex, ey, z0, ex, ey, z1, w, edge);
        }
    }

    private static void face(Batch t, RenderContext c, int col, double ax, double ay, double az, double bx, double by, double bz,
                             double cx, double cy, double cz, double dx, double dy, double dz) {
        t.v(c.m, ax, ay, az, col);
        t.v(c.m, bx, by, bz, col);
        t.v(c.m, cx, cy, cz, col);
        t.v(c.m, ax, ay, az, col);
        t.v(c.m, cx, cy, cz, col);
        t.v(c.m, dx, dy, dz, col);
    }

    private static void ribbon(Batch t, RenderContext c, Vec3d cam, double x0, double y0, double z0,
                               double x1, double y1, double z1, double w, int col) {
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        double mx = (x0 + x1) * 0.5 - cam.x, my = (y0 + y1) * 0.5 - cam.y, mz = (z0 + z1) * 0.5 - cam.z;
        double sx = dy * mz - dz * my, sy = dz * mx - dx * mz, sz = dx * my - dy * mx;
        double l = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (l < 1e-9) return;
        double k = w / l;
        sx *= k; sy *= k; sz *= k;
        t.v(c.m, x0 - sx, y0 - sy, z0 - sz, col);
        t.v(c.m, x0 + sx, y0 + sy, z0 + sz, col);
        t.v(c.m, x1 + sx, y1 + sy, z1 + sz, col);
        t.v(c.m, x0 - sx, y0 - sy, z0 - sz, col);
        t.v(c.m, x1 + sx, y1 + sy, z1 + sz, col);
        t.v(c.m, x1 - sx, y1 - sy, z1 - sz, col);
    }

    // ---------------- экран ----------------

    @Override
    public void renderHud(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!valid || mc.options.hudHidden || System.currentTimeMillis() - lastRender > 150L) return;
        if (pip.value && (!pipPearl.value || pearl)) {
            try {
                drawPip(ctx, mc);
            } catch (RuntimeException ignored) {
                // мини-окно вторично: сбой выборки блоков не должен ронять HUD
            }
        }
        if (!hudInfo.value) return;
        int sw = ctx.getScaledWindowWidth();
        int sh = ctx.getScaledWindowHeight();
        if (kind != null && kind.bow() && power < 1f) {
            int bx = sw / 2 - 18;
            int by = sh / 2 + 14;
            Hud.bar(ctx, bx, by, 36, 3, power, Ui.ACCENT);
            Hud.text(ctx, Math.round(power * 100f) + "%", sw / 2 - Hud.w(Math.round(power * 100f) + "%") / 2, by + 6, Ui.TEXT_DIM);
        }
        if (hitType == 0 || !Projection.project(hx, hy, hz, projOut, sw, sh)) return;
        String line = String.format(Locale.ROOT, "%.1f m  %.1f s", hitDist, flightTicks / 20.0);
        String name = hitType == 2 && hitEntity != null ? hitEntity.getName().getString() : kind != null ? kind.name() : "";
        int w = Math.max(Hud.w(line), Hud.w(name)) + 10;
        int x = MathHelper.clamp(Math.round(projOut[0]) + 10, 4, sw - w - 4);
        int y = MathHelper.clamp(Math.round(projOut[1]) - 16, 4, sh - 28);
        int accent = hitType == 2 ? 0xFFFF6B6B : Ui.ACCENT;
        ctx.fill(x, y, x + w, y + 22, 0xA0000000);
        ctx.fill(x, y, x + 2, y + 22, accent);
        Hud.text(ctx, line, x + 6, y + 3, Ui.TEXT);
        Hud.text(ctx, name, x + 6, y + 12, hitType == 2 ? 0xFFFF9A9A : Ui.TEXT_DIM);
        int mx = Math.round(projOut[0]);
        int my = Math.round(projOut[1]);
        ctx.fill(mx - 1, my - 4, mx + 1, my + 4, accent);
        ctx.fill(mx - 4, my - 1, mx + 4, my + 1, accent);
    }

    // ---------------- мини-окно (PiP) ----------------

    private static final int PIP_CELL = 3;
    private int[] pipCol = new int[0];
    private int pipCols, pipRows;
    private long pipKey = Long.MIN_VALUE, pipBuilt;
    private final BlockPos.Mutable pipPos = new BlockPos.Mutable();

    /**
     * Мини-окно «камеры предикции»: вид сверху (карта блоков вокруг точки падения) или сбоку (разрез мира вдоль полёта).
     * Это 2D-проекция на HUD, а не второй проход рендера мира: никаких FBO и конфликтов с шейдерами,
     * цена в кадре низкая (выборка блоков кэшируется и обновляется ~4 раза в секунду).
     */
    private void drawPip(DrawContext ctx, MinecraftClient mc) {
        World w = mc.world;
        if (w == null || n < 2) return;
        int size = (int) pipSize.value;
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        int m = 8;
        String cn = pipCorner.get();
        int x0 = cn.endsWith("left") ? m : sw - size - m;
        int y0 = cn.startsWith("Top") ? m + 14 : sh - size - m - 40;
        boolean top = pipView.is("Top");
        int rgb = color.rgb(System.currentTimeMillis(), 0f);
        int hitRgb = hitType == 2 ? 0xFF5555 : rgb;

        pipCols = size / PIP_CELL;
        pipRows = size / PIP_CELL;
        if (pipCol.length != pipCols * pipRows) { pipCol = new int[pipCols * pipRows]; pipKey = Long.MIN_VALUE; }

        // параметры проекции
        double ex = px[n - 1], ey = py[n - 1], ez = pz[n - 1];
        double sx0 = px[0], sz0 = pz[0];
        double dirx = ex - sx0, dirz = ez - sz0;
        double hl = Math.sqrt(dirx * dirx + dirz * dirz);
        if (hl < 0.5) { dirx = 1; dirz = 0; hl = 1; } else { dirx /= hl; dirz /= hl; }
        double scale;           // пикселей на блок
        double ymaxV = 0;
        if (top) {
            scale = size / (pipRadius.value * 2.0);
        } else {
            double ymin = Double.MAX_VALUE, ymax = -Double.MAX_VALUE;
            for (int i = 0; i < n; i++) { ymin = Math.min(ymin, py[i]); ymax = Math.max(ymax, py[i]); }
            ymin -= 3; ymax += 2;
            double rangeY = Math.max(8.0, ymax - ymin);
            scale = Math.min((size) / (hl + 6.0), size / rangeY);
            ymaxV = ymax + (size / scale - rangeY) * 0.5;
        }

        long key = top ? (((long) Math.floor(ex) * 31L + (long) Math.floor(ez)) * 31L + (long) Math.floor(ey)) * 7L + size
                : (((long) Math.floor(sx0) * 31L + (long) Math.floor(sz0)) * 31L + (long) Math.floor(ex) * 17L + (long) Math.floor(ez)) * 7L + size
                        + (long) Math.floor(ymaxV) * 131L;
        long now = System.currentTimeMillis();
        if (key != pipKey || now - pipBuilt > 250L) {
            pipKey = key;
            pipBuilt = now;
            for (int r = 0; r < pipRows; r++) {
                for (int c = 0; c < pipCols; c++) {
                    double px0 = (c + 0.5) * PIP_CELL, py0 = (r + 0.5) * PIP_CELL;
                    int col = 0;
                    if (top) {
                        int bx = (int) Math.floor(ex + (px0 - size / 2.0) / scale);
                        int bz = (int) Math.floor(ez + (py0 - size / 2.0) / scale);
                        int by0 = (int) Math.floor(ey) + 6;
                        for (int dy = 0; dy < 20; dy++) {
                            pipPos.set(bx, by0 - dy, bz);
                            BlockState st = w.getBlockState(pipPos);
                            if (st.isAir()) continue;
                            int mc0 = st.getMapColor(w, pipPos).color;
                            if (mc0 == 0) continue;
                            float shade = MathHelper.clamp(1f + (pipPos.getY() - (float) ey) * 0.04f, 0.55f, 1.25f);
                            col = shadeRgb(mc0, shade);
                            break;
                        }
                    } else {
                        double u = -3.0 + px0 / scale;
                        int bx = (int) Math.floor(sx0 + dirx * u);
                        int bz = (int) Math.floor(sz0 + dirz * u);
                        int by = (int) Math.floor(ymaxV - py0 / scale);
                        pipPos.set(bx, by, bz);
                        BlockState st = w.getBlockState(pipPos);
                        if (!st.isAir()) col = st.getMapColor(w, pipPos).color;
                    }
                    pipCol[r * pipCols + c] = col == 0 ? 0 : (0xFF000000 | col);
                }
            }
        }

        // панель
        ctx.fill(x0 - 2, y0 - 2, x0 + size + 2, y0 + size + 2, 0xE0101010);
        int bg = top ? 0xFF1A1A1A : 0xFF6FA0D8;
        ctx.fill(x0, y0, x0 + size, y0 + size, bg);
        for (int r = 0; r < pipRows; r++) {
            for (int c = 0; c < pipCols; c++) {
                int col = pipCol[r * pipCols + c];
                if (col != 0) ctx.fill(x0 + c * PIP_CELL, y0 + r * PIP_CELL, x0 + (c + 1) * PIP_CELL, y0 + (r + 1) * PIP_CELL, col);
            }
        }
        // траектория
        for (int i = 0; i < n; i++) {
            double sxp, syp;
            if (top) {
                sxp = size / 2.0 + (px[i] - ex) * scale;
                syp = size / 2.0 + (pz[i] - ez) * scale;
            } else {
                double u = (px[i] - sx0) * dirx + (pz[i] - sz0) * dirz;
                sxp = (u + 3.0) * scale;
                syp = (ymaxV - py[i]) * scale;
            }
            if (sxp < 1 || syp < 1 || sxp > size - 2 || syp > size - 2) continue;
            int ix = x0 + (int) sxp, iy = y0 + (int) syp;
            ctx.fill(ix - 1, iy - 1, ix + 1, iy + 1, 0xFF000000 | rgb);
        }
        // игрок и точка падения
        double spx, spy, lpx, lpy;
        if (top) {
            spx = size / 2.0 + (sx0 - ex) * scale;
            spy = size / 2.0 + (sz0 - ez) * scale;
            lpx = size / 2.0;
            lpy = size / 2.0;
        } else {
            spx = 3.0 * scale;
            spy = (ymaxV - py[0]) * scale;
            lpx = ((ex - sx0) * dirx + (ez - sz0) * dirz + 3.0) * scale;
            lpy = (ymaxV - ey) * scale;
        }
        if (spx >= 2 && spy >= 2 && spx <= size - 3 && spy <= size - 3) {
            int ix = x0 + (int) spx, iy = y0 + (int) spy;
            ctx.fill(ix - 2, iy - 2, ix + 3, iy + 3, 0xFF000000);
            ctx.fill(ix - 1, iy - 1, ix + 2, iy + 2, 0xFFFFFFFF);
        }
        if (hitType != 0) {
            int ix = x0 + MathHelper.clamp((int) lpx, 4, size - 5), iy = y0 + MathHelper.clamp((int) lpy, 4, size - 5);
            int hc = 0xFF000000 | hitRgb;
            ctx.fill(ix - 5, iy - 1, ix + 6, iy + 2, 0xFF000000);
            ctx.fill(ix - 1, iy - 5, ix + 2, iy + 6, 0xFF000000);
            ctx.fill(ix - 4, iy, ix + 5, iy + 1, hc);
            ctx.fill(ix, iy - 4, ix + 1, iy + 5, hc);
        }
        // подпись
        String label = (pearl ? "Pearl" : kind != null ? kind.name() : "") + (top ? "  top" : "  side");
        Hud.text(ctx, label, x0, y0 - 11, Ui.TEXT_DIM);
        if (hitType != 0) {
            String info = String.format(Locale.ROOT, "%.0f %.0f %.0f", hx, hy, hz);
            Hud.text(ctx, info, x0 + size - Hud.w(info), y0 + size + 4, Ui.TEXT);
        }
    }

    private static int shadeRgb(int rgb, float k) {
        int r = MathHelper.clamp(Math.round(((rgb >> 16) & 255) * k), 0, 255);
        int g = MathHelper.clamp(Math.round(((rgb >> 8) & 255) * k), 0, 255);
        int b = MathHelper.clamp(Math.round((rgb & 255) * k), 0, 255);
        return (r << 16) | (g << 8) | b;
    }
}
