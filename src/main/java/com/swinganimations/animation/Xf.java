package com.swinganimations.animation;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Arm;

/** Хелперы трансформаций без аллокаций: вращаем матрицы JOML напрямую, без Quaternionf. */
public final class Xf {
    private static final float RAD = (float) (Math.PI / 180.0);

    private Xf() {}

    public static void translate(MatrixStack m, float x, float y, float z) {
        m.translate(x, y, z);
    }

    public static void rotX(MatrixStack m, float deg) {
        if (deg == 0f) return;
        float r = deg * RAD;
        MatrixStack.Entry e = m.peek();
        e.getPositionMatrix().rotateX(r);
        e.getNormalMatrix().rotateX(r);
    }

    public static void rotY(MatrixStack m, float deg) {
        if (deg == 0f) return;
        float r = deg * RAD;
        MatrixStack.Entry e = m.peek();
        e.getPositionMatrix().rotateY(r);
        e.getNormalMatrix().rotateY(r);
    }

    public static void rotZ(MatrixStack m, float deg) {
        if (deg == 0f) return;
        float r = deg * RAD;
        MatrixStack.Entry e = m.peek();
        e.getPositionMatrix().rotateZ(r);
        e.getNormalMatrix().rotateZ(r);
    }

    public static void scale(MatrixStack m, float s) {
        m.scale(s, s, s);
    }

    public static float sign(Arm arm) {
        return arm == Arm.RIGHT ? 1f : -1f;
    }

    public static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** smoothstep 0..1 */
    public static float smooth(float t) {
        t = clamp01(t);
        return t * t * (3f - 2f * t);
    }

    /** Колокол: 0 -> 1 -> 0 на отрезке 0..1 */
    public static float bell(float p) {
        return (float) Math.sin(clamp01(p) * Math.PI);
    }

    public static float easeOutCubic(float t) {
        float u = 1f - clamp01(t);
        return 1f - u * u * u;
    }

    /** Быстрый подъём до пика (30% времени) и плавный возврат. */
    public static float pulse(float p) {
        return p < 0.3f ? smooth(p / 0.3f) : 1f - smooth((p - 0.3f) / 0.7f);
    }
}
