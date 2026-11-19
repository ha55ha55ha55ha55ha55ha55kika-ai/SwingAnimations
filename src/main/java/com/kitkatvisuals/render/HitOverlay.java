package com.kitkatvisuals.render;

import com.kitkatvisuals.module.HitColorModule;
import net.minecraft.client.render.OverlayTexture;

/**
 * Состояние для HitColor на броне. Ваниль рисует броню с «пустым» оверлеем (DEFAULT_UV), поэтому вспышка урона на ней
 * не видна. Мы запоминаем оверлей, посчитанный для самой сущности, и подставляем его в модели брони.
 * Рендер идёт в одном потоке, поэтому достаточно простых статических полей.
 */
public final class HitOverlay {
    /** Оверлей текущей сущности (с вспышкой урона, если она есть). */
    private static int last = OverlayTexture.DEFAULT_UV;
    /** true, пока рисуется слой брони. */
    private static boolean armor;

    private HitOverlay() { }

    /** Начало отрисовки сущности: сбрасываем состояние предыдущей. */
    public static void reset() {
        last = OverlayTexture.DEFAULT_UV;
        armor = false;
    }

    public static void setLast(int overlay) { last = overlay; }

    public static void setArmor(boolean v) { armor = v; }

    /** Оверлей для модели: на броне при ударе берём оверлей сущности, иначе оставляем как есть. */
    public static int map(int overlay) {
        if (!armor || overlay != OverlayTexture.DEFAULT_UV || last == OverlayTexture.DEFAULT_UV) return overlay;
        return HitColorModule.armorActive() ? last : overlay;
    }
}
