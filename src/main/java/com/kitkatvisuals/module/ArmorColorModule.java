package com.kitkatvisuals.module;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;

/**
 * ArmorColor: перекраска брони в любой цвет, градиент или радугу. Цвет подменяется в Model.render (ModelColorMixin)
 * только внутри «окна» ArmorFeatureRenderer.render (ArmorColorMixin), поэтому тело и предметы в руках не затрагиваются.
 * Градиент плавно «переливается» между двумя цветами; у каждой части брони своя фаза (шлем, нагрудник, поножи, ботинки
 * выглядят как один вертикальный градиент). Рендер идёт в одном потоке, поэтому достаточно статических полей.
 */
public final class ArmorColorModule extends Module {
    private static ArmorColorModule instance;
    private static boolean allowed;
    private static int piece;

    private final Setting.Mode mode = add(new Setting.Mode("mode", "Mode", "Solid", "Gradient", "Rainbow"));
    private final Setting.Num r1 = add(new Setting.Num("r1", "Red", 255f, 0f, 255f, 1f).<Setting.Num>visibleWhen(() -> !mode.is("Rainbow")));
    private final Setting.Num g1 = add(new Setting.Num("g1", "Green", 80f, 0f, 255f, 1f).<Setting.Num>visibleWhen(() -> !mode.is("Rainbow")));
    private final Setting.Num b1 = add(new Setting.Num("b1", "Blue", 80f, 0f, 255f, 1f).<Setting.Num>visibleWhen(() -> !mode.is("Rainbow")));
    private final Setting.Num r2 = add(new Setting.Num("r2", "Red 2", 80f, 0f, 255f, 1f).<Setting.Num>visibleWhen(() -> mode.is("Gradient")));
    private final Setting.Num g2 = add(new Setting.Num("g2", "Green 2", 120f, 0f, 255f, 1f).<Setting.Num>visibleWhen(() -> mode.is("Gradient")));
    private final Setting.Num b2 = add(new Setting.Num("b2", "Blue 2", 255f, 0f, 255f, 1f).<Setting.Num>visibleWhen(() -> mode.is("Gradient")));
    private final Setting.Num speed = add(new Setting.Num("speed", "Speed", 1f, 0.1f, 5f, 0.1f).<Setting.Num>visibleWhen(() -> !mode.is("Solid")));
    private final Setting.Bool self = add(new Setting.Bool("self", "My armor", true));
    private final Setting.Bool others = add(new Setting.Bool("others", "Other players' armor", false));
    private final Setting.Bool mixDye = add(new Setting.Bool("mix_dye", "Mix with original color", false));

    public ArmorColorModule() {
        super("armor_color", "ArmorColor", "render");
        instance = this;
    }

    /** Начало отрисовки брони сущности с этим id (-1: неизвестно). */
    public static void begin(int entityId) {
        piece = 0;
        ArmorColorModule m = instance;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (m == null || !m.isEnabled() || mc.player == null) {
            allowed = false;
            return;
        }
        boolean mine = entityId == mc.player.getId();
        allowed = mine ? m.self.value : m.others.value;
    }

    public static void end() {
        allowed = false;
    }

    /** ARGB из модели -> ARGB после перекраски. Вне окна брони возвращает исходный цвет. */
    public static int tint(int color) {
        if (!allowed) return color;
        ArmorColorModule m = instance;
        if (m == null) return color;
        int rgb = m.rgbAt(System.currentTimeMillis(), piece++ * 0.12f);
        int a = color >>> 24;
        if (a == 0) a = 255;
        if (m.mixDye.value) {
            int r = ((color >> 16) & 0xFF) * ((rgb >> 16) & 0xFF) / 255;
            int g = ((color >> 8) & 0xFF) * ((rgb >> 8) & 0xFF) / 255;
            int b = (color & 0xFF) * (rgb & 0xFF) / 255;
            rgb = (r << 16) | (g << 8) | b;
        }
        return (a << 24) | rgb;
    }

    private int rgbAt(long now, float offset) {
        if (mode.is("Rainbow")) {
            float hue = (float) (((now * 0.001 * speed.value * 0.25) + offset) % 1.0);
            return MathHelper.hsvToRgb(hue, 0.8f, 1f);
        }
        int c1 = ((int) r1.value << 16) | ((int) g1.value << 8) | (int) b1.value;
        if (mode.is("Solid")) return c1;
        int c2 = ((int) r2.value << 16) | ((int) g2.value << 8) | (int) b2.value;
        float t = 0.5f + 0.5f * (float) Math.sin(Math.PI * 2.0 * (now * 0.001 * speed.value * 0.5 + offset));
        return lerp(c1, c2, t);
    }

    private static int lerp(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1f - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1f - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1f - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
