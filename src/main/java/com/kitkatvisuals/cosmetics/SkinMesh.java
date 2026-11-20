package com.kitkatvisuals.cosmetics;

import java.util.ArrayList;
import java.util.List;

/**
 * Модель игрока (скин 64x64) из кубов в тех же координатах, что и косметика: пиксели, Y вверх, перед = -Z, +X = правая сторона.
 * Нужна для превью в гардеробе. База и внешний слой (шапка, куртка, рукава, штаны) возвращаются отдельно.
 */
public final class SkinMesh {
    private static final float[][] CACHE = new float[4][];

    private SkinMesh() { }

    public static float[] base(boolean slim) { return build(false, slim); }

    public static float[] overlay(boolean slim) { return build(true, slim); }

    private static synchronized float[] build(boolean outer, boolean slim) {
        int idx = (outer ? 2 : 0) + (slim ? 1 : 0);
        if (CACHE[idx] != null) return CACHE[idx];
        List<Float> out = new ArrayList<>();
        float g = outer ? 0.25f : 0f;
        int aw = slim ? 3 : 4;
        int dv = outer ? 16 : 0; // внешний слой лежит на 16 пикселей ниже по V (кроме шапки и левых конечностей)
        // голова / шапка
        cube(out, 4, 24, 4, 12, 32, 12, outer ? 0.5f : 0f, outer ? 32 : 0, 0, 8, 8, 8);
        // тело / куртка
        cube(out, 4, 12, 6, 12, 24, 10, g, 16, 16 + dv, 8, 12, 4);
        // правая рука (игрок смотрит -Z, его правая сторона = +X) / рукав
        cube(out, 12, 12, 6, 12 + aw, 24, 10, g, 40, 16 + dv, aw, 12, 4);
        // левая рука / рукав
        cube(out, 4 - aw, 12, 6, 4, 24, 10, g, outer ? 48 : 32, 48, aw, 12, 4);
        // правая нога / штаны
        cube(out, 8, 0, 6, 12, 12, 10, g, 0, 16 + dv, 4, 12, 4);
        // левая нога / штаны
        cube(out, 4, 0, 6, 8, 12, 10, g, outer ? 0 : 16, 48, 4, 12, 4);
        float[] r = new float[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        CACHE[idx] = r;
        return r;
    }

    private static void quad(List<Float> out, float[][] v, float u1, float v1, float u2, float v2) {
        float[][] uv = {{u1, v1}, {u1, v2}, {u2, v2}, {u2, v1}};
        for (int i = 0; i < 4; i++) {
            out.add(v[i][0]);
            out.add(v[i][1]);
            out.add(v[i][2]);
            out.add(uv[i][0] / 64f);
            out.add(uv[i][1] / 64f);
        }
    }

    /** Куб: границы x1..x2 и т.д. (+ grow), текстура (tu, tv), размеры раскладки w, h, d. Вершины и UV как у блочных моделей. */
    private static void cube(List<Float> out, float x1, float y1, float z1, float x2, float y2, float z2,
                             float grow, int tu, int tv, int w, int h, int d) {
        x1 -= grow; y1 -= grow; z1 -= grow;
        x2 += grow; y2 += grow; z2 += grow;
        // перед (север, -Z)
        quad(out, new float[][]{{x2, y2, z1}, {x2, y1, z1}, {x1, y1, z1}, {x1, y2, z1}}, tu + d, tv + d, tu + d + w, tv + d + h);
        // зад (юг, +Z)
        quad(out, new float[][]{{x1, y2, z2}, {x1, y1, z2}, {x2, y1, z2}, {x2, y2, z2}}, tu + 2 * d + w, tv + d, tu + 2 * d + 2 * w, tv + d + h);
        // правая сторона игрока (+X)
        quad(out, new float[][]{{x2, y2, z2}, {x2, y1, z2}, {x2, y1, z1}, {x2, y2, z1}}, tu, tv + d, tu + d, tv + d + h);
        // левая сторона (-X)
        quad(out, new float[][]{{x1, y2, z1}, {x1, y1, z1}, {x1, y1, z2}, {x1, y2, z2}}, tu + d + w, tv + d, tu + 2 * d + w, tv + d + h);
        // верх
        quad(out, new float[][]{{x1, y2, z1}, {x1, y2, z2}, {x2, y2, z2}, {x2, y2, z1}}, tu + d + w, tv + d, tu + d, tv);
        // низ
        quad(out, new float[][]{{x1, y1, z2}, {x1, y1, z1}, {x2, y1, z1}, {x2, y1, z2}}, tu + d + w, tv + d, tu + d + 2 * w, tv);
    }
}
