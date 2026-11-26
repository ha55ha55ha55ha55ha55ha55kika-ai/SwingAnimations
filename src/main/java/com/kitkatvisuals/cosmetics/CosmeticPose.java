package com.kitkatvisuals.cosmetics;

/**
 * Поза анимированной косметики: иерархия костей (поворот и масштаб по ключевым кадрам, как в Blockbench) -> квады.
 * Чистая математика без зависимостей от Minecraft. Матрицы 4x4 хранятся по строкам: m[строка * 4 + столбец].
 */
public final class CosmeticPose {
    private CosmeticPose() { }

    /** Кость: pivot = origin группы, rot = её поворот в покое (градусы), quads = её квады в «сырых» координатах (по 20 чисел). */
    public static final class Bone {
        public final String name;
        public final int parent;
        public final float[] pivot;
        public final float[] rot;
        public final float[] quads;
        /** Ключевые кадры {время, x, y, z}; null, если канала нет. */
        public final float[][] rotKeys, scaleKeys;
        public final boolean rotCatmull, scaleCatmull;

        public Bone(String name, int parent, float[] pivot, float[] rot, float[] quads,
                    float[][] rotKeys, boolean rotCatmull, float[][] scaleKeys, boolean scaleCatmull) {
            this.name = name;
            this.parent = parent;
            this.pivot = pivot;
            this.rot = rot;
            this.quads = quads;
            this.rotKeys = rotKeys;
            this.rotCatmull = rotCatmull;
            this.scaleKeys = scaleKeys;
            this.scaleCatmull = scaleCatmull;
        }
    }

    public static int totalFloats(Bone[] bones) {
        int n = 0;
        for (Bone b : bones) n += b.quads.length;
        return n;
    }

    /** Значение канала в момент t. def: что вернуть, если ключей нет. Результат пишется в out[0..2]. */
    static void sample(float[][] k, boolean catmull, float t, float len, boolean loop, float[] def, float[] out) {
        if (k == null || k.length == 0) {
            out[0] = def[0]; out[1] = def[1]; out[2] = def[2];
            return;
        }
        int n = k.length;
        if (loop && len > 0f) {
            t = t % len;
            if (t < 0f) t += len;
        }
        if (n == 1 || t <= k[0][0]) { copy(k[0], out); return; }
        if (t >= k[n - 1][0]) { copy(k[n - 1], out); return; }
        int i = 0;
        while (i + 2 < n && k[i + 1][0] <= t) i++;
        float t0 = k[i][0], t1 = k[i + 1][0];
        float s = t1 > t0 ? (t - t0) / (t1 - t0) : 0f;
        float[] p1 = k[i], p2 = k[i + 1];
        if (!catmull) {
            for (int c = 0; c < 3; c++) out[c] = p1[c + 1] + (p2[c + 1] - p1[c + 1]) * s;
            return;
        }
        float[] p0 = i > 0 ? k[i - 1] : (loop && n > 2 ? k[n - 2] : k[0]);
        float[] p3 = i + 2 < n ? k[i + 2] : (loop && n > 2 ? k[1] : k[n - 1]);
        float s2 = s * s, s3 = s2 * s;
        for (int c = 1; c <= 3; c++) {
            out[c - 1] = 0.5f * (2f * p1[c] + (-p0[c] + p2[c]) * s
                    + (2f * p0[c] - 5f * p1[c] + 4f * p2[c] - p3[c]) * s2
                    + (-p0[c] + 3f * p1[c] - 3f * p2[c] + p3[c]) * s3);
        }
    }

    private static void copy(float[] key, float[] out) {
        out[0] = key[1]; out[1] = key[2]; out[2] = key[3];
    }

    // ---- матрицы 4x4 (по строкам)
    private static void identity(float[] m) {
        java.util.Arrays.fill(m, 0f);
        m[0] = m[5] = m[10] = m[15] = 1f;
    }

    /** a = a * b */
    private static void mul(float[] a, float[] b, float[] tmp) {
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                float s = 0f;
                for (int k = 0; k < 4; k++) s += a[r * 4 + k] * b[k * 4 + c];
                tmp[r * 4 + c] = s;
            }
        }
        System.arraycopy(tmp, 0, a, 0, 16);
    }

    private static void translate(float[] m, float x, float y, float z, float[] tmp) {
        float[] t = new float[16];
        identity(t);
        t[3] = x; t[7] = y; t[11] = z;
        mul(m, t, tmp);
    }

    private static void scale(float[] m, float x, float y, float z, float[] tmp) {
        float[] t = new float[16];
        identity(t);
        t[0] = x; t[5] = y; t[10] = z;
        mul(m, t, tmp);
    }

    private static void rotX(float[] m, float deg, float[] tmp) {
        if (deg == 0f) return;
        float c = (float) Math.cos(Math.toRadians(deg)), s = (float) Math.sin(Math.toRadians(deg));
        float[] t = new float[16];
        identity(t);
        t[5] = c; t[6] = -s; t[9] = s; t[10] = c;
        mul(m, t, tmp);
    }

    private static void rotY(float[] m, float deg, float[] tmp) {
        if (deg == 0f) return;
        float c = (float) Math.cos(Math.toRadians(deg)), s = (float) Math.sin(Math.toRadians(deg));
        float[] t = new float[16];
        identity(t);
        t[0] = c; t[2] = s; t[8] = -s; t[10] = c;
        mul(m, t, tmp);
    }

    private static void rotZ(float[] m, float deg, float[] tmp) {
        if (deg == 0f) return;
        float c = (float) Math.cos(Math.toRadians(deg)), s = (float) Math.sin(Math.toRadians(deg));
        float[] t = new float[16];
        identity(t);
        t[0] = c; t[1] = -s; t[4] = s; t[5] = c;
        mul(m, t, tmp);
    }

    /**
     * Считает позу в момент t (секунды) и пишет квады всех костей подряд в out (размер = totalFloats).
     * Кость: M = M_родителя * T(pivot) * Rz * Ry * Rx (поворот покоя + анимация, градусы) * S * T(-pivot).
     */
    public static void pose(Bone[] bones, float len, boolean loop, float t, float[] out) {
        float[][] mats = new float[bones.length][16];
        float[] tmp = new float[16];
        float[] v = new float[3];
        float[] zero = {0f, 0f, 0f}, one = {1f, 1f, 1f};
        int w = 0;
        for (int bi = 0; bi < bones.length; bi++) {
            Bone b = bones[bi];
            float[] m = mats[bi];
            if (b.parent >= 0 && b.parent < bi) System.arraycopy(mats[b.parent], 0, m, 0, 16);
            else identity(m);
            sample(b.rotKeys, b.rotCatmull, t, len, loop, zero, v);
            float rx = b.rot[0] + v[0], ry = b.rot[1] + v[1], rz = b.rot[2] + v[2];
            sample(b.scaleKeys, b.scaleCatmull, t, len, loop, one, v);
            float sx = v[0], sy = v[1], sz = v[2];
            translate(m, b.pivot[0], b.pivot[1], b.pivot[2], tmp);
            rotZ(m, rz, tmp);
            rotY(m, ry, tmp);
            rotX(m, rx, tmp);
            if (sx != 1f || sy != 1f || sz != 1f) scale(m, sx, sy, sz, tmp);
            translate(m, -b.pivot[0], -b.pivot[1], -b.pivot[2], tmp);

            float[] q = b.quads;
            for (int i = 0; i < q.length; i += 5) {
                float x = q[i], y = q[i + 1], z = q[i + 2];
                out[w] = m[0] * x + m[1] * y + m[2] * z + m[3];
                out[w + 1] = m[4] * x + m[5] * y + m[6] * z + m[7];
                out[w + 2] = m[8] * x + m[9] * y + m[10] * z + m[11];
                out[w + 3] = q[i + 3];
                out[w + 4] = q[i + 4];
                w += 5;
            }
        }
    }
}
