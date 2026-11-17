package com.swinganimations;

import com.swinganimations.config.ConfigManager;
import com.swinganimations.input.KeyBinds;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SwingAnimationsClient implements ClientModInitializer {
    public static final String MOD_ID = "swinganimations";
    public static final Logger LOGGER = LoggerFactory.getLogger("SwingAnimations");

    @Override
    public void onInitializeClient() {
        ConfigManager.load();
        com.swinganimations.module.ModuleManager.init();
        KeyBinds.register();
        com.swinganimations.util.MenuGuard.checkAtStartup(); // если прошлый запуск завис на кастомном меню, оно отключается
        com.swinganimations.gui.MenuSwitch.init();
        registerFontPack();
        LOGGER.info("SwingAnimations initialized (client-only, visual-only).");
    }

    /**
     * Шрифт Poppins вынесен в необязательный встроенный ресурспак: TTF-шрифт грузится через FreeType (нативная библиотека),
     * а на некоторых устройствах (например PojavLauncher на Android) это ломает запуск на этапе загрузки ресурсов.
     * Включается вручную: Настройки → Ресурспаки → «SwingAnimations: Poppins font».
     */
    private static void registerFontPack() {
        try {
            FabricLoader.getInstance().getModContainer(MOD_ID).ifPresent(c ->
                    ResourceManagerHelper.registerBuiltinResourcePack(Identifier.of(MOD_ID, "poppins"), c,
                            Text.literal("SwingAnimations: Poppins font"), ResourcePackActivationType.NORMAL));
        } catch (Throwable t) {
            LOGGER.debug("Poppins pack not registered: {}", t.toString());
        }
    }
}
