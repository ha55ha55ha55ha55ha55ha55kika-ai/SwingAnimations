package com.kitkatvisuals.mixin;

import com.kitkatvisuals.render.InvisibleAccess;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LivingEntityRenderState.class)
public class LivingEntityRenderStateMixin implements InvisibleAccess {
    @Unique
    private boolean swa$ghost;

    @Override
    public boolean swa$isGhost() { return swa$ghost; }

    @Override
    public void swa$setGhost(boolean v) { swa$ghost = v; }
}
