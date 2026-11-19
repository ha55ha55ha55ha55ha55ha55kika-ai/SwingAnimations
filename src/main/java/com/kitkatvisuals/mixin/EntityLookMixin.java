package com.kitkatvisuals.mixin;

import com.kitkatvisuals.module.FreeLookModule;
import com.kitkatvisuals.module.ModuleManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** FreeLook: пока камера свободна, движение мыши крутит камеру, а не игрока. require = 0: при другой сигнатуре просто не работает. */
@Mixin(Entity.class)
public abstract class EntityLookMixin {
    @Inject(method = "changeLookDirection(DD)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void swa$freeLook(double dx, double dy, CallbackInfo ci) {
        try {
            FreeLookModule m = ModuleManager.freeLook;
            if (m != null && m.isActive() && (Object) this == MinecraftClient.getInstance().player) {
                m.rotate(dx, dy);
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // косметика
        }
    }
}
