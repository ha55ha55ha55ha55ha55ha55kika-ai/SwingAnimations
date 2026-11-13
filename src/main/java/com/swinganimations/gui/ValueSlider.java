package com.swinganimations.gui;

import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.Locale;

public final class ValueSlider extends SliderWidget {
    public interface Setter { void set(float v); }

    private final String key;
    private final String fmt;
    private final float min;
    private final float max;
    private final Setter setter;

    public ValueSlider(int x, int y, int w, int h, String key, float min, float max,
                       float current, String fmt, Setter setter) {
        super(x, y, w, h, Text.empty(), (current - min) / (max - min));
        this.key = key;
        this.fmt = fmt;
        this.min = min;
        this.max = max;
        this.setter = setter;
        updateMessage();
    }

    private float real() {
        float v = (float) (min + (max - min) * this.value);
        return Math.round(v * 100f) / 100f;
    }

    @Override
    protected void updateMessage() {
        setMessage(Text.translatable(key, String.format(Locale.ROOT, fmt, real())));
    }

    @Override
    protected void applyValue() {
        setter.set(real());
    }
}
