package com.kitkatvisuals.mixin;

import com.kitkatvisuals.module.ModuleManager;
import com.kitkatvisuals.module.RemovalsModule;
import net.minecraft.client.gui.hud.InGameOverlayRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Removals: огонь на экране от первого лица. */
@Mixin(InGameOverlayRenderer.class)
public abstract class InGameOverlayRendererMixin {
    @Inject(method = "renderFireOverlay(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void swa$noFire(MatrixStack matrices, VertexConsumerProvider vertexConsumers, CallbackInfo ci) {
        RemovalsModule r = ModuleManager.removals;
        if (r != null && r.fireOff()) ci.cancel();
    }

    /** ClearLiquids: синяя пелена воды. */
    @Inject(method = "renderUnderwaterOverlay", at = @At("HEAD"), cancellable = true, require = 0)
    private static void kitkat$noWaterOverlay(CallbackInfo ci) {
        com.kitkatvisuals.module.ClearLiquidsModule m = ModuleManager.clearLiquids;
        if (m != null && m.waterOverlayOff()) ci.cancel();
    }

    /** ClearLiquids: огонь на экране (в том числе от лавы). */
    @Inject(method = "renderFireOverlay", at = @At("HEAD"), cancellable = true, require = 0)
    private static void kitkat$noFireOverlay(CallbackInfo ci) {
        com.kitkatvisuals.module.ClearLiquidsModule m = ModuleManager.clearLiquids;
        if (m != null && m.fireOverlayOff()) ci.cancel();
    }
}
