package com.kitkatvisuals.module;

import com.kitkatvisuals.gui.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.util.Random;

/**
 * TotemEffect: свои анимации при срабатывании тотема бессмертия: Phoenix, Joker, Pulse, Spin (или Vanilla).
 * Запускается из TotemFloatingItemMixin вместо ванильного «летающего» предмета; рисуется в renderHud.
 * Картинки феникса и джокера лежат в assets/kitkatvisuals/textures/totem (64x64), остальное процедурное.
 */
public final class TotemEffectModule extends Module {
    private static final Identifier PHOENIX = Identifier.of("kitkatvisuals", "textures/totem/phoenix.png");
    private static final Identifier JOKER = Identifier.of("kitkatvisuals", "textures/totem/joker.png");
    private static final int[] CONFETTI = {0xFF8A3BD2, 0xFF2BBE6E, 0xFFFFCD3C, 0xFFE62846, 0xFF4DA3FF};
    private static final int N = 36;

    private final Setting.Mode style = add(new Setting.Mode("style", "Style", "Phoenix", "Joker", "Pulse", "Spin", "Vanilla"));
    private final Setting.Num duration = add(new Setting.Num("duration", "Duration (ms)", 1400f, 600f, 3000f, 100f));
    private final Setting.Num size = add(new Setting.Num("size", "Size (%)", 100f, 50f, 200f, 10f));
    private final Setting.Bool showItem = add(new Setting.Bool("show_item", "Show totem item", true));
    private final ColorSettings colors = new ColorSettings(this);

    private boolean active;
    private long t0;
    private ItemStack stack = ItemStack.EMPTY;
    private final float[] px = new float[N];
    private final float[] pd = new float[N];
    private final float[] ps = new float[N];

    public TotemEffectModule() {
        super("totem_effect", "TotemEffect", "render");
    }

    @Override
    public void onDisable() {
        active = false;
    }

    /** true: наша анимация запущена, ванильную нужно отменить. */
    public boolean start(ItemStack totem) {
        if (!isEnabled() || style.is("Vanilla")) return false;
        stack = totem.copy();
        t0 = System.currentTimeMillis();
        Random r = new Random(t0);
        for (int i = 0; i < N; i++) {
            px[i] = r.nextFloat() * 2f - 1f;
            pd[i] = r.nextFloat() * 0.5f;
            ps[i] = 0.6f + r.nextFloat() * 0.8f;
        }
        active = true;
        return true;
    }

    private static float easeOutCubic(float t) { float u = 1f - t; return 1f - u * u * u; }

    private static float easeOutBack(float t) {
        float c1 = 1.70158f, c3 = c1 + 1f, u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    @Override
    public void renderHud(DrawContext ctx, float tickDelta) {
        if (!active) return;
        long now = System.currentTimeMillis();
        float p = (now - t0) / duration.value;
        if (p >= 1f) {
            active = false;
            return;
        }
        float alpha = Math.min(1f, p / 0.1f) * (p > 0.7f ? 1f - (p - 0.7f) / 0.3f : 1f);
        float k = size.value / 100f;
        int sw = ctx.getScaledWindowWidth();
        int sh = ctx.getScaledWindowHeight();
        int cx = sw / 2;
        int cy = sh / 2;
        int rgb = colors.rgb(now, 0f);

        switch (style.get()) {
            case "Phoenix" -> phoenix(ctx, now, p, alpha, k, cx, cy, sh, rgb);
            case "Joker" -> joker(ctx, p, alpha, k, cx, cy, sw, sh);
            case "Pulse" -> pulse(ctx, p, alpha, k, cx, cy, sw, sh, rgb);
            default -> spin(ctx, p, alpha, k, cx, cy, rgb);
        }
    }

    private void item(DrawContext ctx, int x, int y, float scale, float sx) {
        if (!showItem.value || stack.isEmpty() || scale <= 0.05f) return;
        ctx.getMatrices().push();
        ctx.getMatrices().translate(x, y, 0f);
        ctx.getMatrices().scale(scale * sx, scale, 1f);
        ctx.drawItem(stack, -8, -8);
        ctx.getMatrices().pop();
    }

    private static void art(DrawContext ctx, Identifier tex, int x, int y, float scale, float sx, float deg, float alpha) {
        int color = ((int) (MathHelper.clamp(alpha, 0f, 1f) * 255f) << 24) | 0xFFFFFF;
        ctx.getMatrices().push();
        ctx.getMatrices().translate(x, y, 0f);
        ctx.getMatrices().multiply(new org.joml.Quaternionf().rotateZ((float) Math.toRadians(deg)));
        ctx.getMatrices().scale(scale * sx, scale, 1f);
        ctx.drawTexture(RenderLayer::getGuiTextured, tex, -32, -32, 0f, 0f, 64, 64, 64, 64, 64, 64, color);
        ctx.getMatrices().pop();
    }

    private void phoenix(DrawContext ctx, long now, float p, float alpha, float k, int cx, int cy, int sh, int rgb) {
        // частицы-угольки поднимаются снизу вверх
        for (int i = 0; i < N; i++) {
            float lt = MathHelper.clamp((p - pd[i] * 0.4f) / 0.6f, 0f, 1f);
            if (lt <= 0f || lt >= 1f) continue;
            int x = cx + Math.round(px[i] * 110f * k + (float) Math.sin(lt * 9f + i) * 6f);
            int y = cy + Math.round(70f * k - lt * 190f * k * ps[i]);
            int a = (int) (Math.min(1f, (1f - lt) * 1.6f) * 255f);
            int s = lt < 0.5f ? 3 : 2;
            ctx.fill(x, y, x + s, y + s, (a << 24) | (i % 3 == 0 ? 0xFFD54A : rgb));
        }
        float rise = easeOutCubic(Math.min(1f, p / 0.45f));
        int y = Math.round(cy + (1f - rise) * sh * 0.3f - (p > 0.45f ? (p - 0.45f) * 40f : 0f) - 10f);
        float flap = 1f + 0.10f * (float) Math.sin(p * 46f);
        art(ctx, PHOENIX, cx, y, 1.5f * k, flap, 0f, alpha);
        item(ctx, cx, cy + Math.round(62f * k), 3.2f * k * alpha, 1f);
    }

    private void joker(DrawContext ctx, float p, float alpha, float k, int cx, int cy, int sw, int sh) {
        // конфетти падает сверху
        for (int i = 0; i < N; i++) {
            float lt = MathHelper.clamp((p * 1.15f - pd[i] * 0.5f), 0f, 1f);
            if (lt <= 0f || lt >= 1f) continue;
            int x = Math.round(cx + px[i] * sw * 0.32f + (float) Math.sin(lt * 12f + i) * 8f);
            int y = Math.round(-6f + lt * (sh + 12f) * (0.6f + 0.4f * ps[i]));
            int a = (int) (Math.min(1f, (1f - lt) * 3f) * 255f);
            ctx.fill(x, y, x + 3, y + 3, (a << 24) | (CONFETTI[i % CONFETTI.length] & 0xFFFFFF));
        }
        float drop = easeOutBack(Math.min(1f, p / 0.4f));
        int y = Math.round(cy - 20f - (1f - drop) * sh * 0.5f);
        float flip = p < 0.25f ? (float) Math.cos((1f - p / 0.25f) * Math.PI / 2.0) : 1f; // «переворот» карты
        float wobble = (float) Math.sin(p * 16f) * 9f * (1f - p);
        art(ctx, JOKER, cx, y, 1.5f * k, Math.max(0.02f, flip), wobble, alpha);
        item(ctx, cx, cy + Math.round(62f * k), 3.2f * k * alpha, 1f);
    }

    private void pulse(DrawContext ctx, float p, float alpha, float k, int cx, int cy, int sw, int sh, int rgb) {
        int flash = (int) (Math.max(0f, 1f - p * 4f) * 0x30);
        if (flash > 0) ctx.fill(0, 0, sw, sh, (flash << 24) | rgb);
        for (int r = 0; r < 3; r++) {
            float lt = MathHelper.clamp(p * 1.4f - r * 0.18f, 0f, 1f);
            if (lt <= 0f || lt >= 1f) continue;
            int rad = Math.max(2, Math.round(easeOutCubic(lt) * 150f * k));
            int a = (int) ((1f - lt) * 0.85f * 255f);
            Ui.outline(ctx, cx - rad, cy - rad, rad * 2, rad * 2, rad, (a << 24) | rgb);
        }
        float pop = 0.55f + 0.45f * easeOutBack(Math.min(1f, p / 0.3f));
        item(ctx, cx, cy, 6f * k * pop * alpha, 1f);
    }

    private void spin(DrawContext ctx, float p, float alpha, float k, int cx, int cy, int rgb) {
        float grow = 0.3f + 0.7f * easeOutBack(Math.min(1f, p / 0.35f));
        float turn = (float) Math.cos(p * Math.PI * 6.0 * (1.0 - p * 0.5)); // вращение вокруг вертикальной оси
        int rad = Math.round(56f * k * Math.min(1f, p / 0.3f) * alpha);
        if (rad > 2) Ui.outline(ctx, cx - rad, cy - rad, rad * 2, rad * 2, rad, (((int) (alpha * 150f)) << 24) | rgb);
        item(ctx, cx, cy, 6f * k * grow * alpha, Math.abs(turn) < 0.04f ? 0.04f : turn);
    }
}
