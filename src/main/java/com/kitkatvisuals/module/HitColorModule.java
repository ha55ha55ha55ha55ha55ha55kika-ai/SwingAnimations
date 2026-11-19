package com.kitkatvisuals.module;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;

/**
 * HitColor: меняет цвет вспышки при уроне. Ваниль берёт её из OverlayTexture (16x16): верхние 8 строк — «hurt»-оверлей
 * (сейчас красный, альфа 178). Здесь эти строки перекрашиваются в свой цвет с заданной прозрачностью.
 * Меняет только вспышку урона: остальные оверлеи (вспышка крипера и т.п.) остаются как были.
 */
public final class HitColorModule extends Module {
    private static final int VANILLA_RGB = 0xFF0000;
    private static final int VANILLA_ALPHA = 178;

    private static volatile NativeImageBackedTexture texture;
    private static HitColorModule instance;

    private final ColorSettings color = new ColorSettings(this);
    private final Setting.Num opacity = add(new Setting.Num("opacity", "Opacity", 70f, 0f, 100f, 1f));
    private final Setting.Bool onArmor = add(new Setting.Bool("armor", "Show on armor", true));

    private long lastKey = Long.MIN_VALUE;

    public HitColorModule() {
        super("hit_color", "HitColor", "render");
        instance = this;
    }

    /** true: модуль включён и вспышка должна быть видна на броне. */
    public static boolean armorActive() {
        HitColorModule m = instance;
        return m != null && m.isEnabled() && m.onArmor.value;
    }

    /** Вызывается миксином OverlayTexture: получает текстуру оверлея и сразу красит её текущими настройками. */
    public static void attach(NativeImageBackedTexture t) {
        texture = t;
        HitColorModule m = instance;
        if (m != null && m.isEnabled()) {
            m.lastKey = Long.MIN_VALUE;
            m.sync(System.currentTimeMillis());
        }
    }

    @Override
    public void onEnable() {
        lastKey = Long.MIN_VALUE;
    }

    @Override
    public void onDisable() {
        lastKey = Long.MIN_VALUE;
        paint(VANILLA_RGB, VANILLA_ALPHA); // вернуть ванильный красный
    }

    @Override
    public void tick(MinecraftClient mc) {
        sync(System.currentTimeMillis());
    }

    private void sync(long now) {
        if (texture == null) return;
        int rgb = color.rgb(now, 0f) & 0xFFFFFF;
        int alpha = Math.round(opacity.value * 255f / 100f);
        alpha = Math.max(0, Math.min(255, alpha));
        long key = ((long) rgb << 8) | alpha;
        if (key == lastKey) return;
        lastKey = key;
        paint(rgb, alpha);
    }

    /** Пишет ABGR в верхние 8 строк текстуры (как в ванили: альфа в старшем байте, R в младшем). */
    private static void paint(int rgb, int alpha) {
        NativeImageBackedTexture t = texture;
        if (t == null) return;
        NativeImage img = t.getImage();
        if (img == null) return;
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int abgr = ((alpha & 0xFF) << 24) | (b << 16) | (g << 8) | r;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 16; x++) img.setColor(x, y, abgr);
        }
        t.upload();
    }
}
