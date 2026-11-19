package com.kitkatvisuals.module;

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
        com.kitkatvisuals.event.EventBus.post(new com.kitkatvisuals.event.ModuleToggleEvent(this, v));
    }

    public void toggle() { setEnabled(!isEnabled()); }

    /** Показывать в панели кнопку «Настройки анимаций…» (только для SwingAnimations). */
    public boolean hasExtraButton() { return false; }

    /** true: модуль активен только пока зажат бинд (включается при нажатии, выключается при отпускании). */
    public boolean holdBind() { return false; }

    /** false: сохранённое «включено» не восстанавливается при запуске игры (например FreeLook). */
    public boolean restoreOnLoad() { return true; }

    /** true: модуль скрыт в меню, пока не введён секретный код в поиске (см. ClickGuiScreen). */
    public boolean secret() { return false; }

    /** true: модуль всегда включён и его нельзя выключить (ни кнопкой, ни клавишей, ни конфигом). */
    public boolean lockedOn() { return false; }

    /**
     * true: модуль выключен, но ещё что-то показывает (например, метки до конца срока).
     * Тогда ModuleManager продолжает звать render() и renderHudIdle(), пока здесь не вернётся false.
     */
    public boolean keepsRunning() { return false; }

    /** HUD, который рисуется, пока модуль выключен, но keepsRunning() == true (обычно только метки). */
    public void renderHudIdle(DrawContext ctx, float tickDelta) { }

    public void onEnable() { }

    public void onDisable() { }

    public void tick(MinecraftClient mc) { }

    public void render(RenderContext c) { }

    /** Нужен ли сейчас мировой рендер. false: кадр не тратит время на подготовку контекста для этого модуля. */
    public boolean wantsWorldRender() { return false; }

    /** Отрисовка поверх HUD (экранные координаты). Вызывается только для включённых модулей. */
    public void renderHud(DrawContext ctx, float tickDelta) { }
}
