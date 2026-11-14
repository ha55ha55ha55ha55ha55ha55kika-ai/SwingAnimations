package com.swinganimations.module;

import com.swinganimations.render.Batch;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

/** Расходящееся светящееся кольцо под ногами при прыжке (процедурное, без текстур). */
public final class JumpCircleModule extends Module {
    private static final class Circle {
        final double x, y, z;
        final long t0;

        Circle(double x, double y, double z, long t0) { this.x = x; this.y = y; this.z = z; this.t0 = t0; }
    }

    private final Setting.Num radius = add(new Setting.Num("radius", "Radius", 1.6f, 0.5f, 4f, 0.1f));
    private final Setting.Num speed = add(new Setting.Num("speed", "Speed", 1.2f, 0.5f, 4f, 0.1f));
    private final Setting.Num fade = add(new Setting.Num("fade", "Fade speed", 1.5f, 1f, 5f, 0.5f));
    private final Setting.Num thickness = add(new Setting.Num("thickness", "Thickness", 0.35f, 0.1f, 0.8f, 0.05f));
    private final ColorSettings color = new ColorSettings(this);

    private final List<Circle> circles = new ArrayList<>();
    private boolean wasOnGround = true;

    public JumpCircleModule() {
        super("jump_circle", "JumpCircle", "render");
    }

    @Override
    public void onEnable() {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        wasOnGround = p == null || p.isOnGround();
    }

    @Override
    public void onDisable() {
        circles.clear();
    }

    private float lifeMs() {
        return 1850f / Math.max(0.25f, speed.value);
    }

    @Override
    public void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null) return;
        boolean g = p.isOnGround();
        if (wasOnGround && !g && p.getVelocity().y > 0.05) {
            circles.add(new Circle(p.getX(), p.getY() + 0.01, p.getZ(), System.currentTimeMillis()));
            while (circles.size() > 8) circles.remove(0);
        }
        wasOnGround = g;
        long now = System.currentTimeMillis();
        float life = lifeMs();
        circles.removeIf(c -> now - c.t0 > life);
    }

    @Override
    public void render(RenderContext c) {
        if (circles.isEmpty()) return;
        float life = lifeMs();
        Batch.begin(true, true);
        for (Circle ci : circles) {
            float f = (c.now - ci.t0) / life;
            if (f >= 1f) continue;
            float alpha = MathHelper.clamp(1f - f * fade.value, 0f, 1f);
            if (alpha <= 0.01f) continue;
            float e = 1f - (1f - f) * (1f - f) * (1f - f);
            float rOut = radius.value * e;
            float rIn = rOut * (1f - thickness.value);
            int rgb = color.rgb(c.now, f);
            float phase = (c.now - ci.t0) / 1000f * speed.value * 3f;

            int segs = 64;

            // Tessellator общий: два открытых буфера одновременно нельзя. Строим и рисуем по очереди.
            Batch glow = Batch.strip();
            for (int i = 0; i <= segs; i++) {
                double ang = Math.PI * 2.0 * i / segs;
                float dash = 0.65f + 0.35f * (float) Math.sin(ang * 6.0 + phase);
                double cx = Math.cos(ang), cz = Math.sin(ang);
                glow.v(c.m, ci.x + cx * rIn, ci.y, ci.z + cz * rIn, Batch.argb(rgb, 0f));
                glow.v(c.m, ci.x + cx * rOut, ci.y, ci.z + cz * rOut, Batch.argb(rgb, alpha * dash));
            }
            glow.flush();

            Batch edge = Batch.strip();
            for (int i = 0; i <= segs; i++) {
                double ang = Math.PI * 2.0 * i / segs;
                double cx = Math.cos(ang), cz = Math.sin(ang);
                edge.v(c.m, ci.x + cx * rOut, ci.y, ci.z + cz * rOut, Batch.argb(rgb, alpha));
                edge.v(c.m, ci.x + cx * (rOut + 0.045), ci.y, ci.z + cz * (rOut + 0.045), Batch.argb(rgb, alpha * 0.7f));
            }
            edge.flush();
        }
        Batch.end();
    }
}
