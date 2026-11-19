package com.kitkatvisuals.mixin;

import com.kitkatvisuals.render.HitOverlay;
import net.minecraft.client.model.Model;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** HitColor: на броне подменяет пустой оверлей на оверлей сущности (см. HitOverlay). */
@Mixin(Model.class)
public abstract class ModelHitMixin {
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 1, require = 0)
    private int kitkat$armorOverlay(int overlay) {
        return HitOverlay.map(overlay);
    }
}
