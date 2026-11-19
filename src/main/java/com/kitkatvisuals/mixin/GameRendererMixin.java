package com.kitkatvisuals.mixin;

import com.kitkatvisuals.module.ModuleManager;
import com.kitkatvisuals.module.RemovalsModule;
import com.kitkatvisuals.module.CustomFovModule;
import com.kitkatvisuals.module.ZoomModule;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Removals: тряска экрана при получении урона. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "tiltViewWhenHurt(Lnet/minecraft/client/util/math/MatrixStack;F)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void swa$noHurtTilt(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
        RemovalsModule r = ModuleManager.removals;
        if (r != null && r.hurtTiltOff()) ci.cancel();
    }

    /** CustomFov, затем Zoom: один хук на FOV мира (changingFov = false — это FOV руки, его не трогаем). */
    @Inject(method = "getFov(Lnet/minecraft/client/render/Camera;FZ)D", at = @At("RETURN"), cancellable = true, require = 0)
    private void kitkat$fov(Camera camera, float tickDelta, boolean changingFov, CallbackInfoReturnable<Double> cir) {
        if (!changingFov) return;
        double v = cir.getReturnValueD();
        CustomFovModule f = ModuleManager.customFov;
        if (f != null && f.isEnabled()) v = f.apply(v);
        ZoomModule z = ModuleManager.zoom;
        if (z != null) v = z.apply(v);
        cir.setReturnValue(v);
    }
}
