package com.kitkatvisuals.mixin;

import com.kitkatvisuals.module.ArmorColorModule;
import net.minecraft.client.model.Model;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * ArmorColor, часть 2. Целевой класс: Model, метод render(MatrixStack, VertexConsumer, int light, int overlay, int color).
 * Третий int-аргумент (ordinal 2) — цвет ARGB; внутри «окна» брони он перекрашивается. Оверлей (ordinal 1) трогает
 * ModelHitMixin, поэтому ординалы не пересекаются.
 */
@Mixin(Model.class)
public abstract class ModelColorMixin {
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 2, require = 0)
    private int kitkat$armorColor(int color) {
        return ArmorColorModule.tint(color);
    }
}
