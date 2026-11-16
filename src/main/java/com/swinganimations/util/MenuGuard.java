package com.swinganimations.util;

import com.swinganimations.SwingAnimationsClient;
import com.swinganimations.module.ThemeModule;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Защита от «зависшего» кастомного меню. При открытии меню в config/ пишется маркер, после первого
 * успешно нарисованного кадра он удаляется. Если при следующем запуске маркер остался (игра зависла или
 * упала, пока меню открывалось), это меню автоматически отключается и показывается ванильное, чтобы
 * не получить зависание снова. Вернуть меню можно кнопкой «Swing menu» в ванильном меню или в настройках темы.
 */
public final class MenuGuard {
    private MenuGuard() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("swinganimations_menu_guard.txt");
    }

    /** Меню открывается: ставим маркер. */
    public static void enter(String id) {
        try {
            Files.writeString(file(), id, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) {
            // не критично
        }
    }

    /** Меню успешно нарисовалось: убираем маркер (только свой). */
    public static void ok(String id) {
        try {
            Path f = file();
            if (Files.exists(f) && id.equals(Files.readString(f, StandardCharsets.UTF_8).trim())) Files.delete(f);
        } catch (IOException | RuntimeException ignored) {
            // не критично
        }
    }

    /** Вызывается при запуске клиента: если прошлый запуск не дорисовал меню, отключает его. */
    public static void checkAtStartup() {
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            String id = Files.readString(f, StandardCharsets.UTF_8).trim();
            Files.delete(f);
            if (id.equals("servers")) {
                ThemeModule.setServerMenu(false);
                SwingAnimationsClient.LOGGER.warn("Прошлый запуск завис на меню серверов: кастомное меню серверов отключено. Включить: кнопка «Swing menu» в ванильном меню.");
            } else if (id.equals("main")) {
                ThemeModule.setMainMenu(false);
                SwingAnimationsClient.LOGGER.warn("Прошлый запуск завис на главном меню: кастомное меню отключено. Включить: кнопка «Swing menu» в ванильном меню.");
            }
        } catch (IOException | RuntimeException e) {
            SwingAnimationsClient.LOGGER.debug("MenuGuard: {}", e.toString());
        }
    }
}
