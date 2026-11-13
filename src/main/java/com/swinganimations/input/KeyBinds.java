package com.swinganimations.input;

import com.swinganimations.config.ConfigManager;
import com.swinganimations.config.Presets;
import com.swinganimations.config.SwingConfig;
import com.swinganimations.gui.SettingsScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public final class KeyBinds {
    private static final String CATEGORY = "category.swinganimations";
    public static KeyBinding OPEN;
    public static KeyBinding TOGGLE;
    public static KeyBinding NEXT_PRESET;

    private KeyBinds() {}

    public static void register() {
        OPEN = reg("open", GLFW.GLFW_KEY_RIGHT_SHIFT);
        TOGGLE = reg("toggle", GLFW.GLFW_KEY_UNKNOWN);       // по умолчанию не назначена, чтобы не конфликтовать
        NEXT_PRESET = reg("next_preset", GLFW.GLFW_KEY_UNKNOWN);
        ClientTickEvents.END_CLIENT_TICK.register(KeyBinds::tick);
    }

    private static KeyBinding reg(String id, int key) {
        return KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.swinganimations." + id, InputUtil.Type.KEYSYM, key, CATEGORY));
    }

    private static void tick(MinecraftClient mc) {
        while (OPEN.wasPressed()) {
            if (mc.currentScreen == null) mc.setScreen(new SettingsScreen(null));
        }
        SwingConfig cfg = ConfigManager.get();
        while (TOGGLE.wasPressed()) {
            cfg.enabled = !cfg.enabled;
            ConfigManager.save();
            message(mc, Text.translatable("swinganimations.msg.toggle",
                    Text.translatable(cfg.enabled ? "swinganimations.on" : "swinganimations.off")));
        }
        while (NEXT_PRESET.wasPressed()) {
            Presets.next(cfg);
            ConfigManager.save();
            message(mc, Text.translatable("swinganimations.msg.preset", cfg.activePreset));
        }
    }

    private static void message(MinecraftClient mc, Text t) {
        if (mc.inGameHud != null) mc.inGameHud.setOverlayMessage(t, false); // локально, пакетов нет
    }
}
