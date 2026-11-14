package com.swinganimations.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat.DrawMode;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;

/**
 * Тонкая обёртка над BufferBuilder: считает вершины, чтобы не отправлять пустой буфер.
 * Для TRIANGLE_STRIP есть restart(): несколько лент склеиваются в один draw call вырожденными треугольниками.
 */
public final class Batch {
    private final BufferBuilder b;
    private int n;
    private boolean restart;
    private Matrix4f lm;
    private float lx, ly, lz;
    private int lc;

    private Batch(DrawMode mode) {
        this.b = Tessellator.getInstance().begin(mode, VertexFormats.POSITION_COLOR);
    }

    public static Batch strip() { return new Batch(DrawMode.TRIANGLE_STRIP); }

    public static Batch triangles() { return new Batch(DrawMode.TRIANGLES); }

    /** Следующая вершина начинает новую ленту (только для strip). */
    public void restart() { restart = true; }

    private void put(Matrix4f m, float x, float y, float z, int argb) {
        b.vertex(m, x, y, z).color((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, (argb >>> 24) & 255);
        n++;
    }

    public void v(Matrix4f m, double x, double y, double z, int argb) {
        float fx = (float) x, fy = (float) y, fz = (float) z;
        if (restart) {
            restart = false;
            if (n > 0) {
                put(lm, lx, ly, lz, lc);
                put(m, fx, fy, fz, argb);
            }
        }
        put(m, fx, fy, fz, argb);
        lm = m;
        lx = fx;
        ly = fy;
        lz = fz;
        lc = argb;
    }

    public void flush() {
        BuiltBuffer built = b.endNullable();
        if (built == null) return;
        if (n >= 3) BufferRenderer.drawWithGlobalProgram(built); else built.close();
    }

    // ---- состояние рендера ----

    public static void begin(boolean depthTest, boolean additive) {
        RenderSystem.enableBlend();
        if (additive) RenderSystem.blendFunc(770, 1); else RenderSystem.defaultBlendFunc();
        if (depthTest) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);
    }

    public static void end() {
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    // ---- цвет ----

    public static int argb(int rgb, float alpha) {
        return ((int) (MathHelper.clamp(alpha, 0f, 1f) * 255f) << 24) | (rgb & 0xFFFFFF);
    }

    public static int mix(int rgbA, int rgbB, float t) {
        t = MathHelper.clamp(t, 0f, 1f);
        int r = (int) MathHelper.lerp(t, (rgbA >> 16) & 255, (rgbB >> 16) & 255);
        int g = (int) MathHelper.lerp(t, (rgbA >> 8) & 255, (rgbB >> 8) & 255);
        int bl = (int) MathHelper.lerp(t, rgbA & 255, rgbB & 255);
        return (r << 16) | (g << 8) | bl;
    }
}
