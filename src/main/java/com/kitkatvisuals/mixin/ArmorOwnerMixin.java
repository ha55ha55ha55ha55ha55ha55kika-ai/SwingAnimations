package com.kitkatvisuals.mixin;

import com.kitkatvisuals.render.ArmorOwnerAccess;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Целевой класс: LivingEntityRenderer, метод updateRenderState: запоминаем id сущности в render state. */
@Mixin(LivingEntityRenderer.class)
public abstract class ArmorOwnerMixin<T extends LivingEntity, S extends LivingEntityRenderState, M extends EntityModel<? super S>> {
    @Inject(method = "updateRenderState(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;F)V",
            at = @At("TAIL"), require = 0)
    private void kitkat$owner(T entity, S state, float tickDelta, CallbackInfo ci) {
        if (state instanceof ArmorOwnerAccess o) o.kitkat$setOwnerId(entity.getId());
    }
}
