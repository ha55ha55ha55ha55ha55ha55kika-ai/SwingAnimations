package com.kitkatvisuals.mixin;

import com.kitkatvisuals.module.ModuleManager;
import com.kitkatvisuals.module.ZoomModule;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Zoom: пока зум удерживается, колёсико меняет кратность и не переключает хотбар. */
@Mixin(Mouse.class)
public abstract class MouseScrollMixin {
    @Inject(method = "onMouseScroll(JDD)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void kitkat$zoomScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        ZoomModule z = ModuleManager.zoom;
        if (z != null && z.onScroll(vertical)) ci.cancel();
    }
}
