package com.swinganimations.mixin;

import com.swinganimations.module.BlockOutlineModule;
import com.swinganimations.module.ModuleManager;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.ObjectAllocator;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Хук в конец WorldRenderer.render: сюда рисуются мировые визуалы (TargetESP, Trails, JumpCircle).
 * require = 0 и try/catch: при несовпадении сигнатуры игра не падает, просто нет мировых эффектов.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
    @Inject(method = "render(Lnet/minecraft/client/util/ObjectAllocator;Lnet/minecraft/client/render/RenderTickCounter;ZLnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/GameRenderer;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
            at = @At("RETURN"), require = 0)
    private void swa$renderWorld(ObjectAllocator allocator, RenderTickCounter tickCounter, boolean renderBlockOutline,
                                 Camera camera, GameRenderer gameRenderer, Matrix4f positionMatrix,
                                 Matrix4f projectionMatrix, CallbackInfo ci) {
        ModuleManager.renderWorld(positionMatrix, projectionMatrix, camera, tickCounter.getTickDelta(false));
    }

    /** BlockOutline: ванильная обводка блока скрывается через флаг renderBlockOutline (единственный boolean-аргумент). */
    @ModifyVariable(method = "render(Lnet/minecraft/client/util/ObjectAllocator;Lnet/minecraft/client/render/RenderTickCounter;ZLnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/GameRenderer;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private boolean swa$hideVanillaOutline(boolean renderBlockOutline) {
        BlockOutlineModule m = ModuleManager.blockOutline;
        return renderBlockOutline && !(m != null && m.hidesVanilla());
    }
}
