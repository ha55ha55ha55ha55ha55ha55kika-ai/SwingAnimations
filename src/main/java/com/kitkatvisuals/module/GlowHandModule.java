package com.kitkatvisuals.module;

import com.kitkatvisuals.KitKatVisualsClient;
import com.kitkatvisuals.render.Batch;
import com.kitkatvisuals.render.TintProvider;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/**
 * GlowHand: свечение вокруг руки от первого лица. Режим Glow: несколько слегка увеличенных полупрозрачных копий руки цвета темы
 * (контур и мягкое сияние). Режим Fill: анимированная заливка внутри руки с настройкой прозрачности (при 100% рука
 * полностью заменяется цветом). Без GLSL-шейдеров. Хук стоит на вызове отрисовки руки в HeldItemRenderer (см. HeldItemGlowMixin).
 */
public final class GlowHandModule extends Module {
    private static final int FULL_LIGHT = 15728880;
    /** Сплошной белый квадрат: цвет слоя получается чистым, без рисунка скина. */
    private static final Identifier WHITE = Identifier.of("kitkatvisuals", "textures/misc/white.png");

    private final Setting.Mode mode = add(new Setting.Mode("mode", "Mode", "Glow", "Fill"));
    private final Setting.Bool hand = add(new Setting.Bool("hand", "Empty hand", true));
    private final Setting.Bool items = add(new Setting.Bool("items", "Held items (sword etc.)", true));
    private final Setting.Num intensity = add(new Setting.Num("intensity", "Glow intensity", 0.8f, 0.2f, 1.5f, 0.05f)
            .<Setting.Num>visibleWhen(() -> mode.is("Glow")));
    private final Setting.Num spread = add(new Setting.Num("spread", "Glow size", 0.05f, 0.02f, 0.12f, 0.01f)
            .<Setting.Num>visibleWhen(() -> mode.is("Glow")));
    private final Setting.Num opacity = add(new Setting.Num("opacity", "Fill opacity", 0.6f, 0.1f, 1f, 0.05f)
            .<Setting.Num>visibleWhen(() -> mode.is("Fill")));
    private final Setting.Num speed = add(new Setting.Num("speed", "Animation speed", 1f, 0.2f, 3f, 0.1f));
    private final ColorSettings color = new ColorSettings(this);

    public GlowHandModule() {
        super("glow_hand", "GlowHand", "render");
    }

    /** Вызывается из миксина сразу после ванильной отрисовки руки; ошибка отключает модуль, а не игру. */
    public void drawSafe(PlayerEntityRenderer r, MatrixStack m, VertexConsumerProvider v, Identifier skin, boolean right) {
        try {
            draw(r, m, v, skin, right);
        } catch (Throwable t) {
            KitKatVisualsClient.LOGGER.warn("GlowHand отключён из-за ошибки: {}", t.toString());
            setEnabled(false);
        }
    }

    private void draw(PlayerEntityRenderer r, MatrixStack m, VertexConsumerProvider v, Identifier skin, boolean right) {
        if (!hand.value) return;
        ModelPart part = right ? r.getModel().rightArm : r.getModel().leftArm;
        long now = System.currentTimeMillis();
        float t = now / 1000f * speed.value;
        VertexConsumer vc = v.getBuffer(RenderLayer.getEntityTranslucent(WHITE));
        int base = color.rgb(now, 0f);
        if (mode.is("Fill")) {
            float k = 0.5f + 0.5f * (float) Math.sin(t * 2.2f);
            int c0 = Batch.mix(base, Batch.mix(base, 0xFFFFFF, 0.55f), k);
            float op = opacity.value;
            layer(m, vc, part, right, 1.025f, Batch.argb(c0, op));
            float shimmer = 0.5f + 0.5f * (float) Math.sin(t * 3.1f + 1.7f);
            int c1 = Batch.mix(color.rgb(now, 0.12f), 0xFFFFFF, 0.35f);
            layer(m, vc, part, right, 1.05f, Batch.argb(c1, op * 0.45f * shimmer));
        } else {
            float pulse = 0.85f + 0.15f * (float) Math.sin(t * 2.4f);
            float in = intensity.value * pulse;
            for (int k = 1; k <= 4; k++) {
                float s = 1f + spread.value * k * 0.6f;
                float a = Math.min(1f, in * 0.30f / (1f + (k - 1) * 0.9f));
                layer(m, vc, part, right, s, Batch.argb(color.rgb(now, k * 0.05f), a));
            }
        }
    }

    /** Вызывается из миксина после ванильной отрисовки предмета в руке (меч, инструмент, блок). */
    public void drawItemSafe(ItemRenderState state, MatrixStack m, VertexConsumerProvider v, int overlay) {
        if (!items.value) return;
        try {
            long now = System.currentTimeMillis();
            float t = now / 1000f * speed.value;
            if (mode.is("Fill")) {
                float k = 0.5f + 0.5f * (float) Math.sin(t * 2.2f);
                int base = color.rgb(now, 0f);
                int c0 = Batch.mix(base, Batch.mix(base, 0xFFFFFF, 0.55f), k);
                itemLayer(state, m, v, overlay, 1.03f, Batch.argb(c0, opacity.value));
                float shimmer = 0.5f + 0.5f * (float) Math.sin(t * 3.1f + 1.7f);
                itemLayer(state, m, v, overlay, 1.07f, Batch.argb(color.rgb(now, 0.12f), opacity.value * 0.45f * shimmer));
            } else {
                float pulse = 0.85f + 0.15f * (float) Math.sin(t * 2.4f);
                float in = intensity.value * pulse;
                for (int k = 1; k <= 4; k++) {
                    float s = 1f + spread.value * k * 1.1f;
                    float a = Math.min(1f, in * 0.34f / (1f + (k - 1) * 0.8f));
                    itemLayer(state, m, v, overlay, s, Batch.argb(color.rgb(now, k * 0.05f), a));
                }
            }
        } catch (Throwable t) {
            KitKatVisualsClient.LOGGER.warn("GlowHand (предметы) отключён из-за ошибки: {}", t.toString());
            setEnabled(false);
        }
    }

    private static void itemLayer(ItemRenderState state, MatrixStack m, VertexConsumerProvider v, int overlay, float scale, int argb) {
        m.push();
        m.scale(scale, scale, scale);
        state.render(m, new TintProvider(v, argb), FULL_LIGHT, overlay);
        m.pop();
    }

    /** Рисует руку ещё раз, увеличенной относительно центра её кубоида (ModelPart сам применяет свой поворот и позицию). */
    private static void layer(MatrixStack m, VertexConsumer vc, ModelPart part, boolean right, float scale, int argb) {
        float px = (part.pivotX + (right ? -1f : 1f)) / 16f;
        float py = (part.pivotY + 4f) / 16f;
        float pz = part.pivotZ / 16f;
        m.push();
        m.translate(px, py, pz);
        m.scale(scale, scale, scale);
        m.translate(-px, -py, -pz);
        part.render(m, vc, FULL_LIGHT, OverlayTexture.DEFAULT_UV, argb);
        m.pop();
    }
}
