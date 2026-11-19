package com.kitkatvisuals.module;

import net.minecraft.client.MinecraftClient;

/** CustomFov: своё поле зрения вместо настройки игры. Опционально без прыжков FOV от спринта, эффектов и лука. */
public final class CustomFovModule extends Module {
    private final Setting.Num fov = add(new Setting.Num("fov", "FOV", 90f, 30f, 140f, 1f));
    private final Setting.Bool ignoreEffects = add(new Setting.Bool("ignore_effects", "Ignore sprint/effects/bow", true));

    public CustomFovModule() {
        super("custom_fov", "CustomFov", "render");
    }

    /** vanilla — FOV, который посчитала игра (с учётом спринта, эффектов и лука). */
    public double apply(double vanilla) {
        if (ignoreEffects.value) return fov.value;
        MinecraftClient mc = MinecraftClient.getInstance();
        double base = mc != null && mc.options != null ? mc.options.getFov().getValue() : 0;
        // сохраняем множитель эффектов, подменяем только базовое значение
        return base > 0 ? fov.value * (vanilla / base) : fov.value;
    }
}
