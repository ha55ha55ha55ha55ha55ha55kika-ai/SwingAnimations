package com.swinganimations.mixin;

import com.swinganimations.module.ModuleManager;
import com.swinganimations.module.RemovalsModule;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Removals: тени от блоков (ambient occlusion). Действует при следующей перестройке чанков (модуль делает её сам). */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "isAmbientOcclusionEnabled()Z", at = @At("HEAD"), cancellable = true, require = 0)
    private static void swa$noAo(CallbackInfoReturnable<Boolean> cir) {
        RemovalsModule r = ModuleManager.removals;
        if (r != null && r.aoOff()) cir.setReturnValue(false);
    }
}
