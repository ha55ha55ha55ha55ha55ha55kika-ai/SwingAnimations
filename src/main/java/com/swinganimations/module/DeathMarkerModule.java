package com.swinganimations.module;

import com.swinganimations.gui.Hud;
import com.swinganimations.gui.Ui;
import com.swinganimations.render.Batch;
import com.swinganimations.render.Projection;
import com.swinganimations.util.DeathTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * DeathMarker («Метка смерти»): когда вы умираете, в месте смерти ставится метка: световой луч, кольцо на земле
 * и подпись «Смерть» с расстоянием и оставшимся временем. Время жизни настраивается (0 = пока не подойдёте).
 * Метка видна только в том мире и на том сервере, где вы погибли; её же показывает мини-карта.
 * Это отдельная функция: сообщения сервера о смерти больше не превращаются в метки ивентов.
 */
public final class DeathMarkerModule extends Module implements WaypointSource {
    private static final int[] COLORS = {0xFF4040, 0xFF9A1F, 0xFFFFFF, -1};

    private final Setting.Bool navCard = add(new Setting.Bool("nav_card", "Navigation card", true));
    private final Setting.Num lifetime = add(new Setting.Num("lifetime", "Lifetime (min, 0 = until reached)", 10f, 0f, 120f, 1f));
    private final Setting.Num maxMarks = add(new Setting.Num("max_marks", "Keep last N deaths", 3f, 1f, 5f, 1f));
    private final Setting.Bool removeNear = add(new Setting.Bool("remove_near", "Remove when reached", true));
    private final Setting.Bool label = add(new Setting.Bool("label", "Label", true));
    private final Setting.Bool timeLeft = add(new Setting.Bool("time_left", "Show time left", true));
    private final Setting.Bool chatCoords = add(new Setting.Bool("chat_coords", "Print coordinates to chat", true));
    private final Setting.Num height = add(new Setting.Num("height", "Beam height", 96f, 16f, 256f, 16f));
    private final Setting.Mode color = add(new Setting.Mode("color", "Color", "Red", "Orange", "White", "Theme"));
    private final Setting.Bool throughWalls = add(new Setting.Bool("through_walls", "Through walls", false));
    private final Setting.Bool test = add(new Setting.Bool("test", "Test mark (8 blocks ahead)", false));

    private static final class Mark {
        final double x, y, z;
        final String dim, server;
        final long created;

        Mark(double x, double y, double z, String dim, String server, long created) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dim = dim;
            this.server = server;
            this.created = created;
        }
    }

    private final List<Mark> marks = new ArrayList<>();
    private int seenSeq;
    private final float[] scr = new float[2];

    public DeathMarkerModule() {
        super("death_marker", "DeathMarker", "hud");
    }

    @Override
    public void onEnable() {
        // Смерти, случившиеся, пока модуль был выключен, не восстанавливаем.
        DeathTracker.Death d = DeathTracker.last();
        seenSeq = d == null ? 0 : d.seq();
    }

    @Override
    public void onDisable() {
        marks.clear();
    }

    private int rgb() {
        int c = COLORS[Math.min(color.index, COLORS.length - 1)];
        return c < 0 ? Ui.ACCENT & 0xFFFFFF : c;
    }

    private boolean here(Mark k, MinecraftClient mc) {
        return k.dim.equals(DeathTracker.dimKey(mc)) && k.server.equals(DeathTracker.serverKey(mc));
    }

    private long ttlMs() {
        return lifetime.value <= 0f ? Long.MAX_VALUE : (long) (lifetime.value * 60_000L);
    }

    @Override
    public void tick(MinecraftClient mc) {
        if (test.value) {
            test.value = false;
            if (mc.player != null) {
                var look = mc.player.getRotationVec(1f);
                add(mc, mc.player.getX() + look.x * 8, mc.player.getY(), mc.player.getZ() + look.z * 8);
            }
        }
        DeathTracker.Death d = DeathTracker.last();
        if (d != null && d.seq() != seenSeq) {
            seenSeq = d.seq();
            addMark(new Mark(d.x(), d.y(), d.z(), d.dim(), d.server(), d.at()));
            if (chatCoords.value) {
                mc.inGameHud.getChatHud().addMessage(Text.literal("[DeathMarker] ").formatted(Formatting.RED)
                        .append(Text.translatableWithFallback("swinganimations.death.chat", "You died at: %s %s %s",
                                Math.round(d.x()), Math.round(d.y()), Math.round(d.z())).formatted(Formatting.GRAY)));
            }
        }
        long now = System.currentTimeMillis();
        long ttl = ttlMs();
        boolean alive = mc.player != null && mc.player.getHealth() > 0f;
        marks.removeIf(k -> {
            if (now - k.created > ttl) return true;
            return removeNear.value && alive && here(k, mc) && mc.player.squaredDistanceTo(k.x, k.y, k.z) < 3.5 * 3.5
                    && now - k.created > 4000L;
        });
    }

    private void add(MinecraftClient mc, double x, double y, double z) {
        addMark(new Mark(x, y, z, DeathTracker.dimKey(mc), DeathTracker.serverKey(mc), System.currentTimeMillis()));
    }

    private void addMark(Mark m) {
        marks.add(m);
        while (marks.size() > (int) maxMarks.value) marks.remove(0);
    }

    @Override
    public List<Waypoint> waypoints() {
        MinecraftClient mc = MinecraftClient.getInstance();
        List<Waypoint> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        long ttl = ttlMs();
        for (Mark k : marks) {
            if (now - k.created > ttl || !here(k, mc)) continue;
            out.add(new Waypoint(k.x, k.y, k.z, Text.translatableWithFallback("swinganimations.death.name", "Death").getString(), rgb(), true));
        }
        return out;
    }

    // ---------------- мир ----------------

    @Override
    public boolean wantsWorldRender() { return !marks.isEmpty(); }

    @Override
    public void render(RenderContext c) {
        long now = c.now;
        long ttl = ttlMs();
        int rgb = rgb();
        for (Mark k : marks) {
            if (now - k.created > ttl || !here(k, c.mc)) continue;
            drawBeacon(c, k, rgb, now);
        }
    }

    /** Луч вверх (снизу ярче, к верху гаснет) и вращающееся кольцо на земле. */
    private void drawBeacon(RenderContext c, Mark k, int rgb, long now) {
        float pulse = 0.8f + 0.2f * (float) Math.sin(now / 320.0);
        double h = height.value;
        double r = 0.24;
        Matrix4f m = c.m;
        Batch.begin(!throughWalls.value, true);
        Batch b = Batch.triangles();
        for (int i = 0; i < 4; i++) {
            double a0 = i * Math.PI / 2, a1 = (i + 1) * Math.PI / 2;
            double x0 = k.x + Math.cos(a0) * r, z0 = k.z + Math.sin(a0) * r;
            double x1 = k.x + Math.cos(a1) * r, z1 = k.z + Math.sin(a1) * r;
            int lo = Batch.argb(rgb, 0.6f * pulse);
            int hi = Batch.argb(rgb, 0f);
            b.v(m, x0, k.y, z0, lo); b.v(m, x1, k.y, z1, lo); b.v(m, x1, k.y + h, z1, hi);
            b.v(m, x0, k.y, z0, lo); b.v(m, x1, k.y + h, z1, hi); b.v(m, x0, k.y + h, z0, hi);
        }
        b.flush();
        Batch s = Batch.strip();
        int seg = 40;
        double spin = now / 900.0;
        double ringR = 0.9 + 0.08 * Math.sin(now / 400.0);
        for (int i = 0; i <= seg; i++) {
            double a = i * 2 * Math.PI / seg + spin;
            double fade = 0.5 + 0.5 * Math.sin(a * 2.0 - spin * 3.0);
            int col = Batch.argb(rgb, (float) (0.25 + 0.55 * fade) * pulse);
            s.v(m, k.x + Math.cos(a) * (ringR - 0.07), k.y + 0.05, k.z + Math.sin(a) * (ringR - 0.07), col);
            s.v(m, k.x + Math.cos(a) * (ringR + 0.07), k.y + 0.05, k.z + Math.sin(a) * (ringR + 0.07), col);
        }
        s.flush();
        Batch.end();
    }

    // ---------------- подпись ----------------

    @Override
    public void renderHud(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (navCard.value) com.swinganimations.gui.NavCard.show(ctx, waypoints(), tickDelta);
        if (marks.isEmpty() || mc.options.hudHidden || !label.value || !Projection.valid()) return;
        int sw = ctx.getScaledWindowWidth();
        int sh = ctx.getScaledWindowHeight();
        long now = System.currentTimeMillis();
        long ttl = ttlMs();
        for (Mark k : marks) {
            if (now - k.created > ttl || !here(k, mc)) continue;
            if (!Projection.project(k.x, k.y + 2, k.z, scr, sw, sh)) continue;
            int px = Math.round(scr[0]);
            int py = Math.round(scr[1]);
            int d = Math.round((float) Projection.distance(k.x, k.y, k.z));
            String text = Text.translatableWithFallback("swinganimations.death.label", "Death  %s m", d).getString();
            if (timeLeft.value && ttl != Long.MAX_VALUE) {
                long left = Math.max(0L, (ttl - (now - k.created)) / 1000L);
                text += "  " + (left / 60L) + ":" + (left % 60L < 10L ? "0" : "") + (left % 60L);
            }
            int tw = Hud.w(text) + 16;
            Hud.chip(ctx, px - tw / 2, py - 22, tw, 16);
            Hud.text(ctx, text, px - tw / 2 + 8, py - 18, 0xFF000000 | rgb());
            ctx.fill(px - 1, py - 4, px + 1, py, 0xFF000000 | rgb());
        }
    }
}
