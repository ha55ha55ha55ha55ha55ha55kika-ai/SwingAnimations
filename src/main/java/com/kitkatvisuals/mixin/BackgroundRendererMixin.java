package com.kitkatvisuals.mixin;

import com.kitkatvisuals.module.ClearLiquidsModule;
import com.kitkatvisuals.module.ModuleManager;
import com.kitkatvisuals.module.NoFogModule;
import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Fog;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Туман: NoFog убирает туман на расстоянии в мире, Незере и Энде;
 * ClearLiquids сдвигает или убирает туман под водой и в лаве.
 */
@Mixin(BackgroundRenderer.class)
public abstract class BackgroundRendererMixin {
    @Inject(method = "applyFog(Lnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/BackgroundRenderer$FogType;Lorg/joml/Vector4f;FZF)Lnet/minecraft/client/render/Fog;",
            at = @At("RETURN"), cancellable = true, require = 0)
    private static void kitkat$clearFog(Camera camera, BackgroundRenderer.FogType fogType, Vector4f color, float viewDistance,
                                        boolean thickenFog, float tickDelta, CallbackInfoReturnable<Fog> cir) {
        Fog f = cir.getReturnValue();
        if (f == null) return;

        // Туман на расстоянии: только на суше (под водой/лавой/в снегу решает ClearLiquids) и без слепоты/тьмы.
        NoFogModule nf = ModuleManager.noFog;
        if (nf != null && nf.fogOff() && camera.getSubmersionType().name().equals("NONE")
                && !(camera.getFocusedEntity() instanceof LivingEntity le
                     && (le.hasStatusEffect(StatusEffects.BLINDNESS) || le.hasStatusEffect(StatusEffects.DARKNESS)))) {
            cir.setReturnValue(new Fog(Float.MAX_VALUE, Float.MAX_VALUE, f.shape(), f.red(), f.green(), f.blue(), f.alpha()));
            return;
        }

        ClearLiquidsModule m = ModuleManager.clearLiquids;
        if (m == null || !m.isEnabled()) return;
        float[] r = m.fogRange(camera.getSubmersionType(), f.start(), f.end(), viewDistance);
        if (r == null) return;
        cir.setReturnValue(new Fog(r[0], r[1], f.shape(), f.red(), f.green(), f.blue(), f.alpha()));
    }
}
