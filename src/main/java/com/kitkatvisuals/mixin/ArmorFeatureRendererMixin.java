package com.kitkatvisuals.mixin;

import com.kitkatvisuals.render.HitOverlay;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Помечает отрезок отрисовки брони, чтобы HitColor менял оверлей только на ней. */
@Mixin(ArmorFeatureRenderer.class)
public abstract class ArmorFeatureRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void kitkat$armorStart(CallbackInfo ci) {
        HitOverlay.setArmor(true);
    }

    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private void kitkat$armorEnd(CallbackInfo ci) {
        HitOverlay.setArmor(false);
    }
}
