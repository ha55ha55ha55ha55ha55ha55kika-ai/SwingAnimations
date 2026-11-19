package com.kitkatvisuals.module;

import com.kitkatvisuals.input.KeyBinds;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;

/**
 * Zoom: приближение, пока удерживается клавиша (по умолчанию C, меняется в «Управлении»). FOV делится на кратность,
 * колёсико мыши меняет кратность, чувствительность на время зума уменьшается пропорционально.
 */
public final class ZoomModule extends Module {
    private final Setting.Num factor = add(new Setting.Num("factor", "Zoom factor", 4f, 1.5f, 10f, 0.1f));
    private final Setting.Bool smooth = add(new Setting.Bool("smooth", "Smooth zoom", true));
    private final Setting.Num smoothSpeed = add(new Setting.Num("smooth_speed", "Smooth speed", 12f, 1f, 30f, 1f).<Setting.Num>visibleWhen(() -> smooth.value));
    private final Setting.Bool scroll = add(new Setting.Bool("scroll", "Scroll changes factor", true));
    private final Setting.Bool sensitivityScale = add(new Setting.Bool("sensitivity_scale", "Scale sensitivity", true));

    private float current = 1f;
    private long lastNs;

    public ZoomModule() {
        super("zoom", "Zoom", "render");
    }

    @Override
    public void onDisable() {
        current = 1f;
    }

    private boolean held() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return KeyBinds.ZOOM != null && KeyBinds.ZOOM.isPressed() && mc.currentScreen == null;
    }

    /** Вызывается на каждый кадр из хука getFov: подтягивает текущую кратность к цели и делит FOV. */
    public double apply(double fov) {
        if (!isEnabled()) {
            current = 1f;
            return fov;
        }
        long now = System.nanoTime();
        float dt = lastNs == 0L ? 0.016f : MathHelper.clamp((now - lastNs) / 1_000_000_000f, 0f, 0.1f);
        lastNs = now;
        float target = held() ? factor.value : 1f;
        if (!smooth.value) {
            current = target;
        } else {
            // current += (target - current) * speed * dt, в стабильной экспоненциальной форме (не «перелетает» при низком FPS)
            current += (target - current) * (1f - (float) Math.exp(-smoothSpeed.value * dt));
        }
        return current > 1.001f ? fov / current : fov;
    }

    /** Колёсико при зуме меняет кратность. true: событие поглощено (хотбар не переключается). */
    public boolean onScroll(double vertical) {
        if (!isEnabled() || !scroll.value || !held()) return false;
        factor.value = MathHelper.clamp(factor.value + (float) vertical * 0.5f, 1.5f, 10f);
        return true;
    }

    /**
     * Значение настройки чувствительности мыши на время зума. Реальная чувствительность игры растёт как куб от
     * (значение * 0.6 + 0.2), поэтому для эффективного множителя 1/кратность берём кубический корень.
     */
    public double scaleSensitivity(double s) {
        if (!isEnabled() || !sensitivityScale.value || current <= 1.001f) return s;
        double k = Math.cbrt(current);
        return Math.max(0.0, ((s * 0.6 + 0.2) / k - 0.2) / 0.6);
    }
}
