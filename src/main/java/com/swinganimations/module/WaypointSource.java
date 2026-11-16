package com.swinganimations.module;

import java.util.List;

/** Модуль, который ставит метки в мире (ивенты, смерть). Мини-карта показывает их на карте. */
public interface WaypointSource {
    /** rgb без альфы; death: рисовать значком смерти (крестик), иначе ромбом. */
    record Waypoint(double x, double y, double z, String name, int rgb, boolean death) {}

    /** Актуальные метки (просроченные не возвращать). Вызывается из потока рендера. */
    List<Waypoint> waypoints();
}
