package com.kitkatvisuals.gui;

import com.kitkatvisuals.module.ThemeModule;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Переключатель «новое меню ⇄ ванильное». В ванильном главном меню и в ванильном списке серверов
 * добавляется кнопка «Swing menu» (вернуть наше меню). В наших экранах есть кнопка «Classic menu»
 * (включить ванильное). Выбор сохраняется в настройках темы (Custom main menu / Custom server menu).
 */
public final class MenuSwitch {
    private MenuSwitch() {}

    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            try {
                if (screen.getClass() == TitleScreen.class) {
                    Screens.getButtons(screen).add(ButtonWidget.builder(label(), b -> {
                        ThemeModule.setMainMenu(true);
                        MainMenuScreen.failed = false;
                        MinecraftClient.getInstance().setScreen(new TitleScreen());
                    }).dimensions(5, 5, 90, 20).build());
                } else if (screen.getClass() == MultiplayerScreen.class) {
                    Screens.getButtons(screen).add(ButtonWidget.builder(label(), b -> {
                        ThemeModule.setServerMenu(true);
                        ServersScreen.failed = false;
                        MinecraftClient mc = MinecraftClient.getInstance();
                        mc.setScreen(new MultiplayerScreen(new TitleScreen()));
                    }).dimensions(5, 5, 90, 20).build());
                }
            } catch (Throwable ignored) {
                // кнопка косметическая: без неё всё работает
            }
        });
    }

    private static Text label() {
        return Text.translatableWithFallback("kitkatvisuals.menu.swing_menu", "Swing menu");
    }
}
