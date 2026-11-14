package com.swinganimations.module;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

public abstract class Module {
    public final String id;
    public final String fallbackName;
    public final String category;
    public final List<Setting> settings = new ArrayList<>();
    /** Код клавиши или кнопки мыши; -1 = не назначено. */
    public int bindCode = -1;
    public boolean bindMouse;
    boolean bindDown;
    protected boolean enabled;

    protected Module(String id, String fallbackName, String category) {
        this.id = id;
        this.fallbackName = fallbackName;
        this.category = category;
    }

    public <S extends Setting> S add(S s) {
        settings.add(s);
        return s;
    }

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean v) {
        if (isEnabled() == v) return;
        enabled = v;
        if (v) ModuleManager.clearRenderErrors(id);
        if (v) onEnable(); else onDisable();
        com.swinganimations.event.EventBus.post(new com.swinganimations.event.ModuleToggleEvent(this, v));
    }

    public void toggle() { setEnabled(!isEnabled()); }

    /** Показывать в панели кнопку «Настройки анимаций…» (только для SwingAnimations). */
    public boolean hasExtraButton() { return false; }

    public void onEnable() { }

    public void onDisable() { }

    public void tick(MinecraftClient mc) { }

    public void render(RenderContext c) { }

    /** Отрисовка поверх HUD (экранные координаты). Вызывается только для включённых модулей. */
    public void renderHud(DrawContext ctx, float tickDelta) { }
}
