package com.swinganimations.module;

import com.swinganimations.gui.Hud;
import com.swinganimations.gui.Ui;
import com.swinganimations.render.Batch;
import com.swinganimations.render.Projection;
import com.swinganimations.util.Spring;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * После вашего удара над головой цели вылетают сердечки (5 стилей), а на экране появляется
 * «тайтл» с нанесённым уроном: «Удар 19», «Удар 17.4». Урон считается как падение здоровья цели
 * в ближайшие 0.7 с после удара. Только клиент: ни пакетов, ни геймплея это не меняет.
 */
public final class HeartShowModule extends Module {
    private static final int N = 40;
    private static final float[] HX = new float[N];
    private static final float[] HY = new float[N];

    static {
        for (int i = 0; i < N; i++) {
            double t = Math.PI * 2.0 * i / N;
            double s = Math.sin(t);
            HX[i] = (float) (16.0 * s * s * s / 17.0);
            HY[i] = (float) ((13.0 * Math.cos(t) - 5.0 * Math.cos(2 * t) - 2.0 * Math.cos(3 * t) - Math.cos(4 * t)) / 17.0);
        }
    }

    private final Setting.Mode style = add(new Setting.Mode("style", "Style", "Red", "Gold", "Ice", "Purple", "Rainbow"));
    private final Setting.Bool showHearts = add(new Setting.Bool("hearts", "Show hearts", true));
    private final Setting.Num count = add(new Setting.Num("count", "Hearts per hit", 6f, 1f, 16f, 1f));
    private final Setting.Num size = add(new Setting.Num("size", "Size", 0.5f, 0.2f, 1f, 0.05f));
    private final Setting.Num life = add(new Setting.Num("life", "Lifetime (ms)", 1100f, 500f, 2500f, 100f));
    private final Setting.Bool showTitle = add(new Setting.Bool("title", "Damage title", true));
    private final Setting.Bool overhead = add(new Setting.Bool("overhead", "HP above head", true));
    private final Setting.Bool overheadMobs = add(new Setting.Bool("overhead_mobs", "HP of mobs too", false).<Setting.Bool>visibleWhen(() -> this.overhead.value));
    private final Setting.Bool overheadBar = add(new Setting.Bool("overhead_bar", "HP bar", true).<Setting.Bool>visibleWhen(() -> this.overhead.value));
    private final Setting.Num overheadRange = add(new Setting.Num("overhead_range", "HP range", 32f, 8f, 64f, 4f).<Setting.Num>visibleWhen(() -> this.overhead.value));
    private final Setting.Num overheadScale = add(new Setting.Num("overhead_scale", "HP scale", 1f, 0.6f, 1.6f, 0.1f).<Setting.Num>visibleWhen(() -> this.overhead.value));
    private final Map<Integer, Spring> hpSprings = new HashMap<>();

    private static final class Pending {
        final LivingEntity e;
        float last;
        long t0;

        Pending(LivingEntity e, float last, long t0) { this.e = e; this.last = last; this.t0 = t0; }
    }

    private static final class Heart {
        final double x, y, z;
        final float vx, vz, size;
        final long t0;
        final int idx;

        Heart(double x, double y, double z, float vx, float vz, float size, long t0, int idx) {
            this.x = x; this.y = y; this.z = z; this.vx = vx; this.vz = vz; this.size = size; this.t0 = t0; this.idx = idx;
        }
    }

    private static final class Pop {
        final Text text;
        final long t0;
        final int rgb;

        Pop(Text text, long t0, int rgb) { this.text = text; this.t0 = t0; this.rgb = rgb; }
    }

    private static final long TRACK_MS = 700L;
    private static final long TITLE_MS = 1300L;

    private final List<Pending> pending = new ArrayList<>();
    private final List<Heart> hearts = new ArrayList<>();
    private final List<Pop> titles = new ArrayList<>();

    public HeartShowModule() {
        super("heart_show", "HeartShow", "render");
        com.swinganimations.event.EventBus.on(com.swinganimations.event.AttackEvent.class, e -> onAttack(e.target()));
    }

    @Override
    public void onDisable() {
        hpSprings.clear();
        pending.clear();
        hearts.clear();
        titles.clear();
    }

    private static float total(LivingEntity e) {
        return e.getHealth() + e.getAbsorptionAmount();
    }

    /** Из AttackEntityCallback: запоминаем цель и её здоровье на момент удара. */
    public void onAttack(Entity e) {
        if (!isEnabled() || !(e instanceof LivingEntity le) || !le.isAlive()) return;
        if (e == MinecraftClient.getInstance().player) return;
        long now = System.currentTimeMillis();
        for (Pending p : pending) {
            if (p.e == le) {
                p.t0 = now;
                return;
            }
        }
        pending.add(new Pending(le, total(le), now));
        while (pending.size() > 16) pending.remove(0);
    }

    @Override
    public void tick(MinecraftClient mc) {
        long now = System.currentTimeMillis();
        for (int i = pending.size() - 1; i >= 0; i--) {
            Pending p = pending.get(i);
            float cur = total(p.e);
            if (cur < p.last - 0.01f) {
                burst(p.e, p.last - cur, now);
            }
            p.last = cur;
            if (p.e.isRemoved() || now - p.t0 > TRACK_MS) pending.remove(i);
        }
        float lifeMs = life.value;
        hearts.removeIf(h -> now - h.t0 > lifeMs);
    }

    private void burst(LivingEntity e, float dmg, long now) {
        if (showHearts.value) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            double top = e.getY() + e.getHeight() + 0.2;
            int n = (int) count.value;
            for (int i = 0; i < n; i++) {
                hearts.add(new Heart(
                        e.getX() + (r.nextDouble() - 0.5) * e.getWidth() * 1.3,
                        top + r.nextDouble() * 0.25,
                        e.getZ() + (r.nextDouble() - 0.5) * e.getWidth() * 1.3,
                        (r.nextFloat() - 0.5f) * 0.8f, (r.nextFloat() - 0.5f) * 0.8f,
                        size.value * (0.7f + 0.6f * r.nextFloat()), now + i * 35L, i));
            }
            while (hearts.size() > 96) hearts.remove(0);
        }
        if (showTitle.value) {
            String v = Math.abs(dmg - Math.round(dmg)) < 0.05f
                    ? String.format(Locale.ROOT, "%d", Math.round(dmg))
                    : String.format(Locale.ROOT, "%.1f", dmg);
            titles.add(0, new Pop(Text.translatableWithFallback("swinganimations.heartshow.hit", "Hit %s", v),
                    now, base(0, now)));
            while (titles.size() > 3) titles.remove(titles.size() - 1);
        }
    }

    private int base(int idx, long now) {
        return switch (style.get()) {
            case "Gold" -> 0xFFC93C;
            case "Ice" -> 0x4FB4FF;
            case "Purple" -> 0xB65CFF;
            case "Rainbow" -> MathHelper.hsvToRgb((((now / 20L) + idx * 60L) % 360L) / 360f, 0.6f, 1f);
            default -> 0xFF3A4F;
        };
    }

    // ---------------- сердечки в мире ----------------

    @Override
    public void render(RenderContext c) {
        if (hearts.isEmpty()) return;
        float lifeMs = life.value;
        Batch.begin(true, false);
        for (Heart h : hearts) {
            long age = c.now - h.t0;
            if (age < 0) continue;
            float f = age / lifeMs;
            if (f >= 1f) continue;
            float rise = 1f - (1f - f) * (1f - f);
            double x = h.x + h.vx * f + Math.sin(f * 7.0 + h.idx) * 0.04;
            double y = h.y + 0.9 * rise;
            double z = h.z + h.vz * f;
            float alpha = f < 0.7f ? 1f : (1f - f) / 0.3f;
            float s = h.size * 0.5f * (0.5f + 0.5f * Math.min(1f, f * 6f));
            int rgb = base(h.idx, c.now);
            heart(c, x, y, z, s * 1.2f, Batch.mix(rgb, 0x000000, 0.6f), Batch.mix(rgb, 0x000000, 0.6f), alpha * 0.9f);
            heart(c, x, y, z, s, Batch.mix(rgb, 0xFFFFFF, 0.35f), rgb, alpha);
        }
        Batch.end();
    }

    private static void heart(RenderContext c, double x, double y, double z, float s, int centerRgb, int rimRgb, float alpha) {
        Batch t = Batch.triangles();
        int cc = Batch.argb(centerRgb, alpha);
        int rc = Batch.argb(rimRgb, alpha);
        for (int i = 0; i < N; i++) {
            int j = (i + 1) % N;
            t.v(c.m, x, y, z, cc);
            pt(t, c, x, y, z, HX[i] * s, HY[i] * s, rc);
            pt(t, c, x, y, z, HX[j] * s, HY[j] * s, rc);
        }
        t.flush();
    }

    private static void pt(Batch t, RenderContext c, double x, double y, double z, float lx, float ly, int col) {
        t.v(c.m, x + c.rx * lx + c.ux * ly, y + c.ry * lx + c.uy * ly, z + c.rz * lx + c.uz * ly, col);
    }

    // ---------------- тайтл с уроном ----------------

    @Override
    public void renderHud(DrawContext ctx, float tickDelta) {
        overhead(ctx, tickDelta);
        if (titles.isEmpty()) return;
        long now = System.currentTimeMillis();
        titles.removeIf(p -> now - p.t0 > TITLE_MS);
        MinecraftClient mc = MinecraftClient.getInstance();
        int w = ctx.getScaledWindowWidth();
        int h = ctx.getScaledWindowHeight();
        for (int i = 0; i < titles.size(); i++) {
            Pop p = titles.get(i);
            long age = now - p.t0;
            float a = age < 120 ? age / 120f : (age > 1000 ? 1f - (age - 1000) / 300f : 1f);
            if (i > 0) a *= 0.7f;
            float pop = age < 150 ? 1.35f - 0.35f * (age / 150f) : 1f;
            int alpha = MathHelper.clamp((int) (a * 255f), 5, 255);
            float scale = (i == 0 ? 2.2f : 1.6f) * pop;
            int tw = mc.textRenderer.getWidth(p.text);
            MatrixStack m = ctx.getMatrices();
            m.push();
            m.translate(w / 2f, h * 0.34f + i * 22f, 0f);
            m.scale(scale, scale, 1f);
            ctx.drawText(mc.textRenderer, p.text, -tw / 2, -4, (alpha << 24) | (p.rgb & 0xFFFFFF), true);
            m.pop();
        }
    }

    // ---------------- HP над головой ----------------

    private static String fmt(float v) {
        return Math.abs(v - Math.round(v)) < 0.05f ? String.format(Locale.ROOT, "%d", Math.round(v)) : String.format(Locale.ROOT, "%.1f", v);
    }

    /**
     * Здоровье над головой игроков (и мобов по желанию): сердечко, число, плавная полоса.
     * Показывается только для тех, кого вы реально видите (без просмотра сквозь стены), невидимые скрыты.
     */
    private void overhead(DrawContext ctx, float td) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!overhead.value || mc.world == null || mc.player == null || mc.options.hudHidden || !Projection.valid()) return;
        int sw = ctx.getScaledWindowWidth();
        int sh = ctx.getScaledWindowHeight();
        double r2 = (double) overheadRange.value * overheadRange.value;
        Set<Integer> used = new HashSet<>();
        float[] out = new float[2];
        MatrixStack m = ctx.getMatrices();
        for (Entity en : mc.world.getEntities()) {
            if (!(en instanceof LivingEntity le) || le == mc.player || !le.isAlive() || le.isInvisible()) continue;
            if (le instanceof ArmorStandEntity) continue;
            if (!(le instanceof PlayerEntity) && !overheadMobs.value) continue;
            if (mc.player.squaredDistanceTo(le) > r2 || !mc.player.canSee(le)) continue;
            double x = MathHelper.lerp(td, le.lastRenderX, le.getX());
            double y = MathHelper.lerp(td, le.lastRenderY, le.getY()) + le.getHeight() + 0.85;
            double z = MathHelper.lerp(td, le.lastRenderZ, le.getZ());
            if (!Projection.project(x, y, z, out, sw, sh)) continue;

            float cur = le.getHealth();
            float max = Math.max(1f, le.getMaxHealth());
            float abs = le.getAbsorptionAmount();
            used.add(le.getId());
            Spring sp = hpSprings.computeIfAbsent(le.getId(), k -> new Spring(MathHelper.clamp(cur / max, 0f, 1f), 170f, 22f));
            sp.target = MathHelper.clamp(cur / max, 0f, 1f);
            float f = sp.update();
            int color = Hud.hpColor(f);

            String hp = fmt(cur);
            String ab = abs > 0.05f ? "+" + fmt(abs) : "";
            int content = 10 + Hud.w(hp) + (ab.isEmpty() ? 0 : 3 + Hud.w(ab));
            int pw = Math.max(content, 36) + 10;
            int ph = overheadBar.value ? 21 : 13;
            float dist = (float) Projection.distance(x, y, z);
            float scale = MathHelper.clamp(3.2f / (float) Math.sqrt(Math.max(dist, 1.0)), 0.55f, 1.15f) * overheadScale.value;

            m.push();
            m.translate(out[0], out[1], 0f);
            m.scale(scale, scale, 1f);
            int top = -ph;
            Ui.rect(ctx, -pw / 2, top, pw, ph, 4, 0xA0000000);
            int tx = -content / 2;
            Hud.heart(ctx, tx, top + 3, color);
            Hud.text(ctx, hp, tx + 10, top + 3, color);
            if (!ab.isEmpty()) Hud.text(ctx, ab, tx + 13 + Hud.w(hp), top + 3, 0xFFFFD24A);
            if (overheadBar.value) {
                int bw = pw - 8;
                Hud.bar(ctx, -pw / 2 + 4, top + 14, bw, 3, f, color);
                if (abs > 0.05f) ctx.fill(-pw / 2 + 4, top + 17, -pw / 2 + 4 + Math.round(bw * MathHelper.clamp(abs / max, 0f, 1f)), top + 18, 0xFFFFD24A);
            }
            m.pop();
        }
        hpSprings.keySet().retainAll(used);
    }
}
