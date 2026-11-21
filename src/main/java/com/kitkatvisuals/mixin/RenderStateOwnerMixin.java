package com.kitkatvisuals.mixin;

import com.kitkatvisuals.render.ArmorOwnerAccess;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Целевой класс: LivingEntityRenderState. Хранит id сущности, чтобы при рендере брони понять, чья она. */
@Mixin(LivingEntityRenderState.class)
public class RenderStateOwnerMixin implements ArmorOwnerAccess {
    @Unique
    private int kitkat$ownerId = -1;

    @Override
    public int kitkat$getOwnerId() { return kitkat$ownerId; }

    @Override
    public void kitkat$setOwnerId(int id) { kitkat$ownerId = id; }
}
