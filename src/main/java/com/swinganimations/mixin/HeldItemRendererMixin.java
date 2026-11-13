package com.swinganimations.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.swinganimations.animation.SwingLogic;
import com.swinganimations.animation.SwingMode;
import com.swinganimations.config.HandProfile;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {

    @Unique private HandProfile swa$profile;
    @Unique private float swa$progress;
    @Unique private float swa$time;
    @Unique private float swa$walk;
    @Unique private boolean swa$crit;
    @Unique private boolean swa$applied;

    @WrapMethod(method = "renderFirstPersonItem")
    private void swa$renderFirstPersonItem(AbstractClientPlayerEntity player, float tickDelta, float pitch,
                                           Hand hand, float swingProgress, ItemStack item, float equipProgress,
                                           MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light,
                                           Operation<Void> original) {
        float passed = swingProgress;
        swa$profile = null;
        swa$applied = false;
        try {
            HandProfile profile = SwingLogic.select(player, hand, item);
            if (profile != null) {
                float raw = SwingLogic.rawProgress(hand, swingProgress);
                float shaped = profile.shape(raw);
                swa$progress = shaped;
                swa$time = player.age + tickDelta;
                swa$walk = profile.walkSway ? SwingLogic.walkFactor(player) : 0f;
                swa$crit = profile.critAnimation && SwingLogic.isCrit(player);
                passed = profile.mode == SwingMode.VANILLA ? shaped : 0f;
                swa$profile = profile;
            }
        } catch (Throwable t) {
            SwingLogic.fail(t);
            swa$profile = null;
            passed = swingProgress;
        }

        HandProfile active = swa$profile;
        matrices.push();
        try {
            original.call(player, tickDelta, pitch, hand, passed, item, equipProgress,
                    matrices, vertexConsumers, light);
        } finally {
            matrices.pop();
            swa$profile = null;
        }

        if (active != null && !swa$applied) {
            SwingLogic.fail(new IllegalStateException(
                    "applySwingOffset hook did not fire in this Minecraft version; mod disabled"));
        }
    }

    @WrapOperation(
            method = "/.+/",
            at = @org.spongepowered.asm.mixin.injection.At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/item/HeldItemRenderer;applySwingOffset(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/util/Arm;F)V"),
            require = 0)
    private void swa$applySwingOffset(HeldItemRenderer instance, MatrixStack matrices, Arm arm,
                                      float progress, Operation<Void> original) {
        HandProfile p = swa$profile;
        if (p == null) {
            original.call(instance, matrices, arm, progress);
            return;
        }
        swa$applied = true;
        try {
            SwingLogic.applyAll(matrices, arm, p, swa$progress, swa$time, swa$walk, swa$crit);
        } catch (Throwable t) {
            SwingLogic.fail(t);
        }
        if (p.mode == SwingMode.VANILLA) {
            original.call(instance, matrices, arm, progress);
        }
    }
}
