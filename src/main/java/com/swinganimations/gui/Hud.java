package com.swinganimations.gui;

import com.swinganimations.module.ThemeModule;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** Общие примитивы HUD: шрифт темы, панели, полосы, цвет здоровья. */
public final class Hud {
    public static final Identifier FONT = Identifier.of("swinganimations", "hud");

    private Hud() {}

    public static Text t(String s) {
        MutableText m = Text.literal(s);
        if (ThemeModule.cleanFont()) m.setStyle(Style.EMPTY.withFont(FONT));
        return m;
    }

    public static Text t(Text base) {
        if (!ThemeModule.cleanFont()) return base;
        return Text.empty().append(base).setStyle(Style.EMPTY.withFont(FONT));
    }

    public static int w(String s) { return Ui.tr().getWidth(t(s)); }

    public static int w(Text x) { return Ui.tr().getWidth(t(x)); }

    public static void text(DrawContext c, String s, int x, int y, int color) {
        c.drawText(Ui.tr(), t(s), x, y, color, false);
    }

    public static void text(DrawContext c, Text s, int x, int y, int color) {
        c.drawText(Ui.tr(), t(s), x, y, color, false);
    }

    public static void textRight(DrawContext c, String s, int right, int y, int color) {
        text(c, s, right - w(s), y, color);
    }

    public static void textRight(DrawContext c, Text s, int right, int y, int color) {
        text(c, s, right - w(s), y, color);
    }

    public static int bg() { return ThemeModule.matte() ? Ui.BG : 0xB40D0D0D; }

    public static void panel(DrawContext c, int x, int y, int w, int h) {
        Ui.rect(c, x, y, w, h, 5, bg());
        Ui.outline(c, x, y, w, h, 5, Ui.OUTLINE);
    }

    /** Простая полоса: тёмная подложка + заливка frac (0..1). */
    public static void bar(DrawContext c, int x, int y, int w, int h, float frac, int argb) {
        frac = MathHelper.clamp(frac, 0f, 1f);
        c.fill(x, y, x + w, y + h, 0x66000000);
        int fw = Math.round(w * frac);
        if (fw > 0) c.fill(x, y, x + fw, y + h, argb);
    }

    /** Зелёный → жёлтый → красный по доле здоровья. */
    public static int hpColor(float f) {
        f = MathHelper.clamp(f, 0f, 1f);
        int r = f < 0.5f ? 255 : (int) (255 * (1f - f) * 2f);
        int g = f > 0.5f ? 255 : (int) (255 * f * 2f);
        return 0xFF000000 | (r << 16) | (g << 8) | 0x30;
    }

    private static final int[] HEART = {0b0110110, 0b1111111, 0b1111111, 0b0111110, 0b0011100, 0b0001000};

    /** Пиксельное сердечко 7×6. */
    public static void heart(DrawContext c, int x, int y, int argb) {
        for (int r = 0; r < HEART.length; r++) {
            for (int col = 0; col < 7; col++) {
                if ((HEART[r] & (1 << (6 - col))) != 0) c.fill(x + col, y + r, x + col + 1, y + r + 1, argb);
            }
        }
    }
}
