package com.swinganimations.config;

import com.swinganimations.animation.Curve;
import com.swinganimations.animation.SwingMode;

/** Настройки одной руки. Это же «Settings» из сигнатуры apply(...). */
public final class HandProfile {
    public SwingMode mode = SwingMode.VANILLA;
    public float speed = 1f;            // 0.3..3.0
    public Curve curve = Curve.LINEAR;
    public float curveStrength = 1f;    // 0..1
    public float x, y, z;               // -1..1
    public float pitch, yaw, roll;      // -180..180
    public float scale = 1f;            // 0.3..1.5
    public boolean walkSway = false;
    public boolean critAnimation = false;

    public HandProfile() {}

    public HandProfile(SwingMode mode, float speed, Curve curve, float strength,
                       float x, float y, float z, float scale, boolean walk, boolean crit) {
        this.mode = mode; this.speed = speed; this.curve = curve; this.curveStrength = strength;
        this.x = x; this.y = y; this.z = z; this.scale = scale;
        this.walkSway = walk; this.critAnimation = crit;
    }

    /**
     * Прогресс 0..1 -> форма. Скорость: time-warp p^(1/speed), концы 0 и 1 остаются на месте,
     * поэтому реальная длительность свинга (и кулдаун) не меняются.
     */
    public float shape(float raw) {
        float p = raw < 0f ? 0f : (raw > 1f ? 1f : raw);
        if (speed != 1f && p > 0f && p < 1f) p = (float) Math.pow(p, 1.0 / speed);
        return curve.blend(p, curveStrength);
    }

    public HandProfile copy() {
        HandProfile h = new HandProfile();
        h.copyFrom(this);
        return h;
    }

    public void copyFrom(HandProfile o) {
        mode = o.mode; speed = o.speed; curve = o.curve; curveStrength = o.curveStrength;
        x = o.x; y = o.y; z = o.z; pitch = o.pitch; yaw = o.yaw; roll = o.roll;
        scale = o.scale; walkSway = o.walkSway; critAnimation = o.critAnimation;
    }

    public void sanitize() {
        if (mode == null) mode = SwingMode.VANILLA;
        if (curve == null) curve = Curve.LINEAR;
        speed = c(speed, 0.3f, 3f, 1f);
        curveStrength = c(curveStrength, 0f, 1f, 1f);
        x = c(x, -1f, 1f, 0f); y = c(y, -1f, 1f, 0f); z = c(z, -1f, 1f, 0f);
        pitch = c(pitch, -180f, 180f, 0f); yaw = c(yaw, -180f, 180f, 0f); roll = c(roll, -180f, 180f, 0f);
        scale = c(scale, 0.3f, 1.5f, 1f);
    }

    private static float c(float v, float lo, float hi, float def) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return def;
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
