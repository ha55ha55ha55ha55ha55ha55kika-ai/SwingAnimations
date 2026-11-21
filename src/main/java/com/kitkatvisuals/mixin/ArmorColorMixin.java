package com.kitkatvisuals.mixin;

import com.kitkatvisuals.module.ArmorColorModule;
import com.kitkatvisuals.render.ArmorOwnerAccess;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.state.BipedEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ArmorColor, часть 1. Целевой класс: ArmorFeatureRenderer, метод render(...). Открываем и закрываем «окно»
 * перекраски для конкретной сущности.
 * Сам цвет подменяет ModelColorMixin.
 */
@Mixin(ArmorFeatureRenderer.class)
public abstract class ArmorColorMixin {
    @Inject(method = "render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;ILnet/minecraft/client/render/entity/state/BipedEntityRenderState;FF)V",
            at = @At("HEAD"), require = 0)
    private void kitkat$armorColorBegin(MatrixStack m, VertexConsumerProvider p, int light, BipedEntityRenderState state,
                                        float a, float b, CallbackInfo ci) {
        int id = state instanceof ArmorOwnerAccess o ? o.kitkat$getOwnerId() : -1;
        ArmorColorModule.begin(id);
    }

    @Inject(method = "render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;ILnet/minecraft/client/render/entity/state/BipedEntityRenderState;FF)V",
            at = @At("RETURN"), require = 0)
    private void kitkat$armorColorEnd(CallbackInfo ci) {
        ArmorColorModule.end();
    }
}
