package com.kitkatvisuals.render;

import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Проекция мировых координат на экран (для HUD-элементов над сущностями). Матрицы берутся из WorldRenderer. */
public final class Projection {
    private static final Matrix4f VIEW = new Matrix4f();
    private static final Matrix4f PROJ = new Matrix4f();
    private static final Vector4f TMP = new Vector4f();
    private static double cx, cy, cz;
    private static boolean valid;

    private Projection() {}

    public static void capture(Matrix4f view, Matrix4f proj, Vec3d cam) {
        VIEW.set(view);
        PROJ.set(proj);
        cx = cam.x;
        cy = cam.y;
        cz = cam.z;
        valid = true;
    }

    public static boolean valid() { return valid; }

    public static double distanceSq(double x, double y, double z) {
        double dx = x - cx, dy = y - cy, dz = z - cz;
        return dx * dx + dy * dy + dz * dz;
    }

    public static double distance(double x, double y, double z) {
        return Math.sqrt(distanceSq(x, y, z));
    }

    /**
     * Как project(), но точки за кадром (в стороне или за спиной) не отбрасываются.
     * Возвращает 1: точка на экране (out[0], out[1]); 2: точка за кадром, out[0], out[1] = место на краю экрана
     * (с отступом margin), out[2] = угол направления на точку (радианы, 0 = вправо, вниз положителен); 0: проекция недоступна.
     * out должен вмещать 3 числа.
     */
    public static int projectOrEdge(double x, double y, double z, float[] out, int sw, int sh, int margin) {
        if (!valid) return 0;
        Vector4f v = TMP.set((float) (x - cx), (float) (y - cy), (float) (z - cz), 1f);
        VIEW.transform(v);
        float vx = v.x, vy = v.y;
        PROJ.transform(v);
        float dx, dy;
        if (v.w > 0.05f) {
            float nx = v.x / v.w;
            float ny = v.y / v.w;
            if (Math.abs(nx) <= 1f && Math.abs(ny) <= 1f) {
                out[0] = (nx * 0.5f + 0.5f) * sw;
                out[1] = (0.5f - ny * 0.5f) * sh;
                return 1;
            }
            dx = nx;
            dy = -ny;
        } else {                    // позади камеры: направление по боковому смещению, чтобы стрелка указывала, куда повернуться
            dx = vx;
            dy = -vy;
        }
        float len = (float) Math.hypot(dx, dy);
        if (len < 1e-4f) {
            dx = 0f;
            dy = 1f;
            len = 1f;
        }
        dx /= len;
        dy /= len;
        float hw = sw / 2f - margin;
        float hh = sh / 2f - margin;
        float tx = Math.abs(dx) < 1e-4f ? Float.MAX_VALUE : hw / Math.abs(dx);
        float ty = Math.abs(dy) < 1e-4f ? Float.MAX_VALUE : hh / Math.abs(dy);
        float t = Math.min(tx, ty);
        out[0] = sw / 2f + dx * t;
        out[1] = sh / 2f + dy * t;
        out[2] = (float) Math.atan2(dy, dx);
        return 2;
    }

    /** out[0], out[1] = экранные координаты (в масштабированных пикселях GUI). false, если точка вне кадра. */
    public static boolean project(double x, double y, double z, float[] out, int scaledW, int scaledH) {
        if (!valid) return false;
        Vector4f v = TMP.set((float) (x - cx), (float) (y - cy), (float) (z - cz), 1f);
        VIEW.transform(v);
        PROJ.transform(v);
        if (v.w <= 0.05f) return false;
        float nx = v.x / v.w;
        float ny = v.y / v.w;
        if (Math.abs(nx) > 1.2f || Math.abs(ny) > 1.2f) return false;
        out[0] = (nx * 0.5f + 0.5f) * scaledW;
        out[1] = (0.5f - ny * 0.5f) * scaledH;
        return true;
    }
}
