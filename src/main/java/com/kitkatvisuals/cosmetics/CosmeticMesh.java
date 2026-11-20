package com.kitkatvisuals.cosmetics;

import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Arrays;

/** Вывод квадов (по 20 чисел: 4 вершины x, y, z, u, v) в VertexConsumer формата сущностей. */
public final class CosmeticMesh {
    private CosmeticMesh() { }

    /** Центр масс вершин после преобразования m (для сортировки групп по глубине): {x, y, z}. */
    public static float[] center(Matrix4f m, float[] q, float ax, float ay, float az, float s, float ox, float oy, float oz) {
        Vector3f v = new Vector3f();
        double sx = 0, sy = 0, sz = 0;
        int cnt = q.length / 5;
        for (int i = 0; i + 2 < q.length; i += 5) {
            m.transformPosition(ax + (q[i] - ax) * s + ox, ay + (q[i + 1] - ay) * s + oy, az + (q[i + 2] - az) * s + oz, v);
            sx += v.x;
            sy += v.y;
            sz += v.z;
        }
        return cnt == 0 ? new float[3] : new float[]{(float) (sx / cnt), (float) (sy / cnt), (float) (sz / cnt)};
    }

    /**
     * Выводит квады. m: матрица из пространства модели в пространство вершин. ax..az: центр масштабирования,
     * s: масштаб, ox..oz: смещение. shade: подмешать затенение по нормали (для интерфейса, где нет освещения).
     * sortFar: упорядочить квады от дальних к ближним (в интерфейсе большее z = ближе), для слоёв без записи глубины.
     */
    public static void emit(VertexConsumer vc, Matrix4f m, float[] q, float ax, float ay, float az,
                            float s, float ox, float oy, float oz, int light, boolean shade, boolean sortFar) {
        int count = q.length / 20;
        Integer[] order = new Integer[count];
        for (int i = 0; i < count; i++) order[i] = i;
        if (sortFar) {
            float[] depth = new float[count];
            Vector3f v = new Vector3f();
            for (int i = 0; i < count; i++) {
                float z = 0;
                for (int j = 0; j < 4; j++) {
                    int o = i * 20 + j * 5;
                    m.transformPosition(ax + (q[o] - ax) * s + ox, ay + (q[o + 1] - ay) * s + oy, az + (q[o + 2] - az) * s + oz, v);
                    z += v.z;
                }
                depth[i] = z;
            }
            Arrays.sort(order, (a, b) -> Float.compare(depth[a], depth[b]));
        }
        Vector3f n = new Vector3f();
        for (int k = 0; k < count; k++) {
            int i = order[k] * 20;
            float e1x = q[i + 5] - q[i], e1y = q[i + 6] - q[i + 1], e1z = q[i + 7] - q[i + 2];
            float e2x = q[i + 10] - q[i + 5], e2y = q[i + 11] - q[i + 6], e2z = q[i + 12] - q[i + 7];
            float nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-6f) continue;
            nx /= len;
            ny /= len;
            nz /= len;
            int c = 255;
            if (shade) c = Math.max(95, Math.min(255, (int) (255f * (0.72f + 0.28f * ny))));
            m.transformDirection(nx, ny, nz, n).normalize();
            for (int j = 0; j < 4; j++) {
                int o = i + j * 5;
                vc.vertex(m, ax + (q[o] - ax) * s + ox, ay + (q[o + 1] - ay) * s + oy, az + (q[o + 2] - az) * s + oz)
                        .color(c, c, c, 255)
                        .texture(q[o + 3], q[o + 4])
                        .overlay(OverlayTexture.DEFAULT_UV)
                        .light(light)
                        .normal(n.x, n.y, n.z);
            }
        }
    }
}
