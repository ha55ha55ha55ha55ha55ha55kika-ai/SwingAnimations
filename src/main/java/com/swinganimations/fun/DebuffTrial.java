package com.swinganimations.fun;

import com.swinganimations.SwingAnimationsClient;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.Random;

/**
 * «Испытание»: на 30 секунд включается случайный безобидный визуальный эффект поверх игры,
 * только на клиенте того, кто его запустил. Никак не влияет на других игроков и на сервер.
 */
public final class DebuffTrial {
    public enum Effect { TINT, VIGNETTE, JITTER }

    private static final long DURATION_MS = 30_000L;
    private static final Random RNG = new Random();

    public static boolean active;
    public static Effect effect;
    private static long startedAt;
    private static long endAt;

    private DebuffTrial() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            try {
                render(ctx);
            } catch (Throwable t) {
                SwingAnimationsClient.LOGGER.warn("Fun debuff: ошибка рендера {}", t.toString());
                active = false;
            }
        });
    }

    public static boolean start() {
        if (active) return false;
        Effect[] vals = Effect.values();
        effect = vals[RNG.nextInt(vals.length)];
        startedAt = System.currentTimeMillis();
        endAt = startedAt + DURATION_MS;
        active = true;
        FunStats.recordDebuff();
        return true;
    }

    public static void stop() {
        active = false;
    }

    /** 1 в начале, 0 в конце. */
    public static float remaining() {
        if (!active) return 0f;
        return Math.max(0f, (endAt - System.currentTimeMillis()) / (float) DURATION_MS);
    }

    public static int remainingSeconds() {
        return (int) Math.ceil(remaining() * DURATION_MS / 1000f);
    }

    private static void tick() {
        if (active && System.currentTimeMillis() >= endAt) active = false;
    }

    // ---------------- отрисовка ----------------

    private static void render(DrawContext ctx) {
        if (!active) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        int w = mc.getWindow().getScaledWidth();
        int h = mc.getWindow().getScaledHeight();
        float t = (System.currentTimeMillis() - startedAt) / 1000f;
        float left = remaining();

        switch (effect) {
            case TINT -> {
                float pulse = 0.10f + 0.06f * (float) Math.sin(t * 2.4f);
                int rgb = hueColor(t * 40f);
                ctx.fill(0, 0, w, h, (clampAlpha(pulse) << 24) | (rgb & 0xFFFFFF));
            }
            case VIGNETTE -> {
                float pulse = 0.35f + 0.20f * (float) Math.sin(t * 3.0f);
                int a = clampAlpha(pulse);
                int band = Math.max(16, w / 7);
                for (int i = 0; i < band; i++) {
                    int ai = a * (band - i) / band;
                    int col = (ai << 24);
                    ctx.fill(i, 0, i + 1, h, col);
                    ctx.fill(w - i - 1, 0, w - i, h, col);
                }
                int bandH = Math.max(12, h / 7);
                for (int i = 0; i < bandH; i++) {
                    int ai = a * (bandH - i) / bandH;
                    int col = (ai << 24);
                    ctx.fill(0, i, w, i + 1, col);
                    ctx.fill(0, h - i - 1, w, h - i, col);
                }
            }
            case JITTER -> {
                if (RNG.nextFloat() < 0.5f) {
                    int lines = 1 + RNG.nextInt(3);
                    for (int i = 0; i < lines; i++) {
                        int y = RNG.nextInt(Math.max(1, h));
                        int lh = 1 + RNG.nextInt(2);
                        int alpha = 40 + RNG.nextInt(60);
                        ctx.fill(0, y, w, Math.min(h, y + lh), (alpha << 24) | 0xFFFFFF);
                    }
                }
            }
        }

        // общий счётчик сверху, виден при любом эффекте
        String label = label();
        int tw = mc.textRenderer.getWidth(label);
        int bx = (w - tw - 16) / 2;
        int by = 6;
        ctx.fill(bx, by, bx + tw + 16, by + 14, 0x99000000);
        int fillW = Math.round((tw + 12) * left);
        ctx.fill(bx + 2, by + 2, bx + 2 + fillW, by + 12, 0x66FFFFFF);
        ctx.drawText(mc.textRenderer, label, bx + 8, by + 3, 0xFFFFFFFF, true);
    }

    private static String label() {
        boolean ru = Messages.ru();
        return (ru ? "Испытание: " : "Trial: ") + remainingSeconds() + "s";
    }

    private static int clampAlpha(float f) {
        return Math.max(0, Math.min(255, Math.round(f * 255f)));
    }

    /** Простой обход по цветовому кругу без Color.HSBtoRGB (чтобы не тянуть java.awt). */
    private static int hueColor(float deg) {
        float h = ((deg % 360f) + 360f) % 360f / 60f;
        float x = 1f - Math.abs(h % 2f - 1f);
        float r, g, b;
        if (h < 1) { r = 1; g = x; b = 0; }
        else if (h < 2) { r = x; g = 1; b = 0; }
        else if (h < 3) { r = 0; g = 1; b = x; }
        else if (h < 4) { r = 0; g = x; b = 1; }
        else if (h < 5) { r = x; g = 0; b = 1; }
        else { r = 1; g = 0; b = x; }
        return ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }
}
