package com.kitkatvisuals.gui;

import com.kitkatvisuals.KitKatVisualsClient;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Обучение для новых игроков. Кто запускает мод впервые (нет ни настроек, ни метки обучения), при первом показе
 * главного меню видит TutorialScreen; его можно пропустить. Старым игрокам обучение само не показывается, но его
 * можно открыть кнопкой «?» в ClickGUI или в настройках.
 *
 * Состояние лежит в файле kitkatvisuals_tutorial.txt: «pending» (ещё не пройдено / не пропущено) или «done».
 * Так обучение не теряется, если игру закрыли посреди него, и не зависит от пользовательских конфигов.
 */
public final class Tutorial {
    private static final String FILE = "kitkatvisuals_tutorial.txt";
    private static boolean pending;
    private static boolean shownThisSession;

    private Tutorial() {}

    private static Path marker() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE);
    }

    /** Вызывать ДО ConfigManager.load(): иначе файл настроек уже создан и новый игрок неотличим от старого. */
    public static void detect() {
        try {
            Path m = marker();
            if (Files.exists(m)) {
                pending = Files.readString(m, StandardCharsets.UTF_8).trim().equals("pending");
                return;
            }
            Path cfgDir = FabricLoader.getInstance().getConfigDir();
            boolean existingUser = Files.exists(cfgDir.resolve("kitkatvisuals.json"));
            write(existingUser ? "done" : "pending");
            pending = !existingUser;
        } catch (IOException | RuntimeException e) {
            pending = false; // обучение не должно ломать запуск
            KitKatVisualsClient.LOGGER.warn("Tutorial: не удалось прочитать состояние: {}", e.toString());
        }
    }

    /** Экран, поверх которого надо открыть обучение (выставляется после его первого кадра, открывается в тике). */
    private static Screen requested;
    private static Screen scheduled;

    /**
     * Подписка на показ главного меню. Обучение открывается только ПОСЛЕ первого отрисованного кадра меню:
     * MenuGuard снимает маркер «меню открывается» именно на этом кадре, иначе закрытие игры посреди обучения
     * заставило бы мод отключить своё главное меню как «зависшее».
     */
    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            try {
                if (!pending || shownThisSession || scheduled == screen) return;
                if (screen.getClass() != TitleScreen.class && !(screen instanceof MainMenuScreen)) return;
                scheduled = screen;
                ScreenEvents.afterRender(screen).register((s, ctx, mx, my, td) -> {
                    if (pending && !shownThisSession && requested == null) requested = s;
                });
            } catch (Throwable t) {
                KitKatVisualsClient.LOGGER.warn("Tutorial: не удалось подготовить показ: {}", t.toString());
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Screen s = requested;
            if (s == null) return;
            requested = null;
            try {
                if (pending && !shownThisSession && client.currentScreen == s) {
                    shownThisSession = true;
                    client.setScreen(new TutorialScreen(s));
                }
            } catch (Throwable t) {
                KitKatVisualsClient.LOGGER.warn("Tutorial: не удалось открыть: {}", t.toString());
            }
        });
    }

    /** Игрок прошёл или пропустил обучение: больше само не появляется. */
    public static void markDone() {
        pending = false;
        try {
            write("done");
        } catch (IOException e) {
            KitKatVisualsClient.LOGGER.warn("Tutorial: не удалось сохранить состояние: {}", e.toString());
        }
    }

    private static void write(String s) throws IOException {
        Path m = marker();
        Files.createDirectories(m.getParent());
        Files.writeString(m, s, StandardCharsets.UTF_8);
    }

    /** Открыть обучение вручную (кнопки «?» и «Открыть обучение»). */
    public static void open(MinecraftClient mc, Screen parent) {
        if (mc != null) mc.setScreen(new TutorialScreen(parent));
    }
}
