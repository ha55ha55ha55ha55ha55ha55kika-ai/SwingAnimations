package com.kitkatvisuals.mixin;

import com.kitkatvisuals.module.ModuleManager;
import com.kitkatvisuals.module.RecordModule;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Record. Целевой класс: GameRenderer, метод render(RenderTickCounter, boolean), точка TAIL: кадр (с HUD) уже в главном
 * фреймбуфере, ещё до вывода на экран. Если модуль выключен, вызов сводится к одной проверке.
 */
@Mixin(GameRenderer.class)
public abstract class RecordFrameMixin {
    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V", at = @At("TAIL"), require = 0)
    private void kitkat$recordFrame(RenderTickCounter counter, boolean tick, CallbackInfo ci) {
        RecordModule m = ModuleManager.recorder;
        if (m != null) m.onFrameEnd();
    }
}
