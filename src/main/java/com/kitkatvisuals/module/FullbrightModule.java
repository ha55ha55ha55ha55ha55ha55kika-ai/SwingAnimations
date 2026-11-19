package com.kitkatvisuals.module;

/**
 * Fullbright: максимальная яркость без зелий и без изменения настроек яркости игры.
 * Только на вашем экране: карте освещения возвращается максимальная gamma (SimpleOptionMixin),
 * сохранённая в настройках яркость не меняется. Отдельный модуль, чтобы его можно было включать и блокировать независимо.
 */
public final class FullbrightModule extends Module {
    public FullbrightModule() {
        super("fullbright", "Fullbright", "render");
    }
}
