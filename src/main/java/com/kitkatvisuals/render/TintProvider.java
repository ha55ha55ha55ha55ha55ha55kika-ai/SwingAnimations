package com.kitkatvisuals.render;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.TexturedRenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;

/**
 * Обёртка над провайдером буферов: всё, что через неё рисуется (например, предмет в руке), выводится полупрозрачным
 * слоем предметов и перекрашивается в заданный цвет. Слои блеска чар отбрасываются, чтобы не дублировать вершины.
 */
public final class TintProvider implements VertexConsumerProvider {
    private final VertexConsumerProvider base;
    private final int tr, tg, tb, ta;

    /** argb: цвет и общая прозрачность, всё как 0..255. */
    public TintProvider(VertexConsumerProvider base, int argb) {
        this.base = base;
        this.ta = (argb >>> 24) & 0xFF;
        this.tr = (argb >> 16) & 0xFF;
        this.tg = (argb >> 8) & 0xFF;
        this.tb = argb & 0xFF;
    }

    @Override
    public VertexConsumer getBuffer(RenderLayer layer) {
        if (layer.toString().toLowerCase(java.util.Locale.ROOT).contains("glint")) return new Tint(null);
        return new Tint(base.getBuffer(TexturedRenderLayers.getItemEntityTranslucentCull()));
    }

    private final class Tint implements VertexConsumer {
        private final VertexConsumer d;

        Tint(VertexConsumer d) { this.d = d; }

        @Override
        public VertexConsumer vertex(float x, float y, float z) {
            if (d != null) d.vertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            if (d != null) d.color(r * tr / 255, g * tg / 255, b * tb / 255, a * ta / 255);
            return this;
        }

        @Override
        public VertexConsumer texture(float u, float v) {
            if (d != null) d.texture(u, v);
            return this;
        }

        @Override
        public VertexConsumer overlay(int u, int v) {
            if (d != null) d.overlay(u, v);
            return this;
        }

        @Override
        public VertexConsumer light(int u, int v) {
            if (d != null) d.light(u, v);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            if (d != null) d.normal(x, y, z);
            return this;
        }
    }
}
