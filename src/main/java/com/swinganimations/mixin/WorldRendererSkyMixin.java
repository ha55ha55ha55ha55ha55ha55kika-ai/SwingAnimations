package com.swinganimations.mixin;

import com.swinganimations.module.ModuleManager;
import com.swinganimations.module.RemovalsModule;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Fog;
import net.minecraft.client.render.FrameGraphBuilder;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Removals: не рисовать небо (солнце, луна, звёзды). Лучше всего держать в отдельном файле: если эта сигнатура
 * не совпадёт с вашим Yarn, файл можно просто удалить вместе со строкой в swinganimations.mixins.json.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererSkyMixin {
    @Inject(method = "renderSky(Lnet/minecraft/client/render/FrameGraphBuilder;Lnet/minecraft/client/render/Camera;FLnet/minecraft/client/render/Fog;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void swa$noSky(FrameGraphBuilder builder, Camera camera, float tickDelta, Fog fog, CallbackInfo ci) {
        RemovalsModule r = ModuleManager.removals;
        if (r != null && r.skyOff()) ci.cancel();
    }
}
