package com.kitkatvisuals;

import com.kitkatvisuals.config.ConfigManager;
import com.kitkatvisuals.input.KeyBinds;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public final class KitKatVisualsClient implements ClientModInitializer {
    public static final String MOD_ID = "kitkatvisuals";
    public static final Logger LOGGER = LoggerFactory.getLogger("KitKatVisuals");

    @Override
    public void onInitializeClient() {
        migrateOldConfig();
        com.kitkatvisuals.gui.Tutorial.detect(); // до ConfigManager.load(): иначе файл настроек уже создан
        ConfigManager.load();
        com.kitkatvisuals.module.ModuleManager.init();
        KeyBinds.register();
        com.kitkatvisuals.util.MenuGuard.checkAtStartup(); // если прошлый запуск завис на кастомном меню, оно отключается
        com.kitkatvisuals.gui.MenuSwitch.init();
        com.kitkatvisuals.gui.Tutorial.init();
        com.kitkatvisuals.fun.FunGame.init();
        com.kitkatvisuals.fun.DebuffTrial.init();
        com.kitkatvisuals.fun.ItemDump.init();
        LOGGER.info("KitKatVisuals initialized (client-only, visual-only).");
    }

    /**
     * Мод раньше назывался SwingAnimations: если новых файлов настроек ещё нет, копирует старые (настройки, модули, HUD,
     * защита меню, папку с пользовательскими конфигами и метками), чтобы после переименования ничего не сбросилось.
     */
    private static void migrateOldConfig() {
        final String oldName = "swing" + "animations";
        try {
            Path dir = FabricLoader.getInstance().getConfigDir();
            for (String suffix : new String[]{".json", "_modules.json", "_hud.json", "_menu_guard.txt"}) {
                Path from = dir.resolve(oldName + suffix);
                Path to = dir.resolve(MOD_ID + suffix);
                if (Files.isRegularFile(from) && !Files.exists(to)) Files.copy(from, to);
            }
            Path fromDir = dir.resolve(oldName);
            Path toDir = dir.resolve(MOD_ID);
            if (Files.isDirectory(fromDir) && !Files.exists(toDir)) {
                try (Stream<Path> walk = Files.walk(fromDir)) {
                    for (Path src : (Iterable<Path>) walk::iterator) {
                        Path dst = toDir.resolve(fromDir.relativize(src).toString());
                        if (Files.isDirectory(src)) Files.createDirectories(dst);
                        else Files.copy(src, dst);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Не удалось перенести старые настройки: {}", e.toString());
        }
    }
}
