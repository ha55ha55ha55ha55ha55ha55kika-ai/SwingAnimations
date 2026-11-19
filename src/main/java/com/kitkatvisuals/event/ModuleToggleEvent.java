package com.kitkatvisuals.event;

import com.kitkatvisuals.module.Module;

/** Модуль включён или выключен. */
public record ModuleToggleEvent(Module module, boolean enabled) {}
