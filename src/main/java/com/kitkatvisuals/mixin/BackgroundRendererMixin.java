package com.kitkatvisuals.mixin;

import com.kitkatvisuals.module.ClearLiquidsModule;
import com.kitkatvisuals.module.ModuleManager;
import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Fog;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** ClearLiquids: туман под водой и в лаве. Берём ванильный туман и сдвигаем его дальность (или убираем совсем). */
@Mixin(BackgroundRenderer.class)
public abstract class BackgroundRendererMixin {
    @Inject(method = "applyFog(Lnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/BackgroundRenderer$FogType;Lorg/joml/Vector4f;FZF)Lnet/minecraft/client/render/Fog;",
            at = @At("RETURN"), cancellable = true, require = 0)
    private static void kitkat$clearFog(Camera camera, BackgroundRenderer.FogType fogType, Vector4f color, float viewDistance,
                                        boolean thickenFog, float tickDelta, CallbackInfoReturnable<Fog> cir) {
        ClearLiquidsModule m = ModuleManager.clearLiquids;
        if (m == null || !m.isEnabled()) return;
        Fog f = cir.getReturnValue();
        if (f == null) return;
        float[] r = m.fogRange(camera.getSubmersionType(), f.start(), f.end(), viewDistance);
        if (r == null) return;
        cir.setReturnValue(new Fog(r[0], r[1], f.shape(), f.red(), f.green(), f.blue(), f.alpha()));
    }
}
