package com.kitkatvisuals.cosmetics;

/** Настройки одного предмета в гардеробе: надет ли, размер и смещение (в пикселях модели: X вправо, Y вверх, Z назад). */
public final class CosmeticState {
    public static final float SCALE_MIN = 0.3f, SCALE_MAX = 3.0f;
    public static final float POS_MIN = -20f, POS_MAX = 20f;

    public boolean equipped;
    public float scale = 1f;
    public float ox, oy, oz;

    public static CosmeticState defaults(CosmeticModel m) {
        CosmeticState s = new CosmeticState();
        s.reset(m);
        return s;
    }

    public void reset(CosmeticModel m) {
        scale = m.defScale;
        ox = m.defX;
        oy = m.defY;
        oz = m.defZ;
    }
}
