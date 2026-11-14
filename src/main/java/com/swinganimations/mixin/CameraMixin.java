package com.swinganimations.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.swinganimations.module.FreeLookModule;
import com.swinganimations.module.ModuleManager;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** FreeLook: подмена углов камеры (первый вызов setRotation в Camera.update). */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @WrapOperation(method = "update(Lnet/minecraft/world/BlockView;Lnet/minecraft/entity/Entity;ZZF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;setRotation(FF)V", ordinal = 0), require = 0)
    private void swa$freeLookRotation(Camera camera, float yaw, float pitch, Operation<Void> original) {
        FreeLookModule m = ModuleManager.freeLook;
        if (m != null && m.isActive()) {
            original.call(camera, m.cameraYaw(), m.cameraPitch());
        } else {
            original.call(camera, yaw, pitch);
        }
    }
}
