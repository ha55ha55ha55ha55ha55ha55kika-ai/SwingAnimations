package com.kitkatvisuals.mixin;

import com.kitkatvisuals.gui.MainMenuScreen;
import com.kitkatvisuals.gui.ServersScreen;
import com.kitkatvisuals.module.ModuleManager;
import com.kitkatvisuals.module.RemovalsModule;
import com.kitkatvisuals.module.ThemeModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Removals: тени от блоков (ambient occlusion). Действует при следующей перестройке чанков (модуль делает её сам).
 * Главное меню: ванильный TitleScreen заменяется на MainMenuScreen, а MultiplayerScreen на ServersScreen (если включено в Theme). require = 0: при другой сигнатуре
 * просто остаётся ванильное меню.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "isAmbientOcclusionEnabled()Z", at = @At("HEAD"), cancellable = true, require = 0)
    private static void swa$noAo(CallbackInfoReturnable<Boolean> cir) {
        RemovalsModule r = ModuleManager.removals;
        if (r != null && r.aoOff()) cir.setReturnValue(false);
    }

    @ModifyVariable(method = "setScreen(Lnet/minecraft/client/gui/screen/Screen;)V", at = @At("HEAD"), argsOnly = true, require = 0)
    private Screen swa$mainMenu(Screen screen) {
        try {
            if (screen != null && screen.getClass() == TitleScreen.class && !MainMenuScreen.failed && ThemeModule.mainMenu()) {
                return new MainMenuScreen();
            }
        } catch (Throwable t) {
            MainMenuScreen.failed = true;
        }
        try {
            if (screen != null && screen.getClass() == MultiplayerScreen.class && !ServersScreen.failed && ThemeModule.serverMenu()) {
                // «Назад» ведёт на экран, с которого открыли список; если это не меню (например, после отключения) — на заглавное
                Screen cur = MinecraftClient.getInstance().currentScreen;
                Screen back = (cur instanceof TitleScreen || cur instanceof MainMenuScreen) ? cur : new TitleScreen();
                return new ServersScreen(back);
            }
        } catch (Throwable t) {
            ServersScreen.failed = true;
        }
        return screen;
    }
}
