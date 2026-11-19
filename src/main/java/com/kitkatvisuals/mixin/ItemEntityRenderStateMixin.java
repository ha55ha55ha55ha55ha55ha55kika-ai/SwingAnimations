package com.kitkatvisuals.mixin;

import com.kitkatvisuals.module.ItemPhysicsModule;
import com.kitkatvisuals.render.ItemPhysicsHolder;
import net.minecraft.client.render.entity.state.ItemEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ItemEntityRenderState.class)
public class ItemEntityRenderStateMixin implements ItemPhysicsHolder {
    @Unique
    private ItemPhysicsModule.Anim swa$anim;

    @Override
    public ItemPhysicsModule.Anim swa$anim() { return swa$anim; }

    @Override
    public void swa$setAnim(ItemPhysicsModule.Anim a) { swa$anim = a; }
}
