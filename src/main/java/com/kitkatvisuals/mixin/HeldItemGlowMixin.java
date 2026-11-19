package com.kitkatvisuals.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.kitkatvisuals.module.GlowHandModule;
import com.kitkatvisuals.module.ModuleManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * GlowHand: после ванильной отрисовки руки (внутри renderArmHoldingItem, где матрица уже стоит в позе руки)
 * модуль дорисовывает свои слои. require = 0: если сигнатура в версии другая, вызов не находится и просто ничего не происходит.
 */
@Mixin(HeldItemRenderer.class)
public abstract class HeldItemGlowMixin {
    @WrapOperation(method = "renderArmHoldingItem",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/render/entity/PlayerEntityRenderer;renderRightArm(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;ILnet/minecraft/util/Identifier;Z)V"),
            require = 0)
    private void swa$glowRight(PlayerEntityRenderer r, MatrixStack m, VertexConsumerProvider v, int light,
                               Identifier skin, boolean sleeve, Operation<Void> original) {
        original.call(r, m, v, light, skin, sleeve);
        GlowHandModule g = ModuleManager.glowHand;
        if (g != null && g.isEnabled()) g.drawSafe(r, m, v, skin, true);
    }

    @WrapOperation(method = "renderArmHoldingItem",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/render/entity/PlayerEntityRenderer;renderLeftArm(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;ILnet/minecraft/util/Identifier;Z)V"),
            require = 0)
    private void swa$glowLeft(PlayerEntityRenderer r, MatrixStack m, VertexConsumerProvider v, int light,
                              Identifier skin, boolean sleeve, Operation<Void> original) {
        original.call(r, m, v, light, skin, sleeve);
        GlowHandModule g = ModuleManager.glowHand;
        if (g != null && g.isEnabled()) g.drawSafe(r, m, v, skin, false);
    }

    /** Предмет в руке (меч, инструмент): после ванильной отрисовки добавляем слои свечения. */
    @WrapOperation(method = "renderItem",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/render/item/ItemRenderState;render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;II)V"),
            require = 0)
    private void swa$glowItem(ItemRenderState state, MatrixStack m, VertexConsumerProvider v, int light, int overlay,
                              Operation<Void> original) {
        original.call(state, m, v, light, overlay);
        GlowHandModule g = ModuleManager.glowHand;
        if (g != null && g.isEnabled()) g.drawItemSafe(state, m, v, overlay);
    }
}
