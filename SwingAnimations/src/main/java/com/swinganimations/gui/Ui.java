package com.swinganimations.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/** Палитра и примитивы в стиле тёмного ClickGUI: полупрозрачные скруглённые панели, акцент голубой. */
public final class Ui {
    public static final int ACCENT = 0xFF8BB9FF;
    public static final int ACCENT_BG = 0x2D8BB9FF;
    public static final int ACCENT_LINE = 0x918BB9FF;
    public static final int KNOB = 0xFFAEDCFF;
    public static final int BG = 0xE60D0D0D;
    public static final int FIELD = 0xD21C1C1C;
    public static final int FIELD_HOVER = 0xD2282828;
    public static final int OUTLINE = 0x12FFFFFF;
    public static final int SEP = 0x20FFFFFF;
    public static final int TEXT = 0xFFFFFFFF;
    public static final int TEXT_DIM = 0xB4BEC3CD;
    public static final int SWITCH_OFF = 0xFF3A3A3A;

    private Ui() {}

    public static TextRenderer tr() {
        return MinecraftClient.getInstance().textRenderer;
    }

    /** Скруглённый прямоугольник из горизонтальных полос (без шейдеров, радиус до ~6). */
    public static void rect(DrawContext c, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) return;
        r = Math.min(r, Math.min(w, h) / 2);
        if (r <= 0) {
            c.fill(x, y, x + w, y + h, color);
            return;
        }
        c.fill(x, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = r - (int) Math.round(Math.sqrt(r * r - dy * dy));
            c.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            c.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
        }
    }

    /** Тонкая скруглённая обводка. */
    public static void outline(DrawContext c, int x, int y, int w, int h, int r, int color) {
        r = Math.min(r, Math.min(w, h) / 2);
        c.fill(x + r, y, x + w - r, y + 1, color);
        c.fill(x + r, y + h - 1, x + w - r, y + h, color);
        c.fill(x, y + r, x + 1, y + h - r, color);
        c.fill(x + w - 1, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = r - (int) Math.round(Math.sqrt(r * r - dy * dy));
            c.fill(x + inset, y + i, x + inset + 1, y + i + 1, color);
            c.fill(x + w - inset - 1, y + i, x + w - inset, y + i + 1, color);
            c.fill(x + inset, y + h - 1 - i, x + inset + 1, y + h - i, color);
            c.fill(x + w - inset - 1, y + h - 1 - i, x + w - inset, y + h - i, color);
        }
    }

    public static void text(DrawContext c, Text t, int x, int y, int color) {
        c.drawText(tr(), t, x, y, color, false);
    }

    public static void textRight(DrawContext c, Text t, int right, int y, int color) {
        c.drawText(tr(), t, right - tr().getWidth(t), y, color, false);
    }
}
