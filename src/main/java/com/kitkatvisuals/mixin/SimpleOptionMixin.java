package com.kitkatvisuals.mixin;

import com.kitkatvisuals.module.FullbrightModule;
import com.kitkatvisuals.module.ModuleManager;
import com.kitkatvisuals.module.RemovalsModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.SimpleOption;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fullbright (отдельный модуль). Карта освещения читает яркость (gamma) через SimpleOption.getValue,
 * поэтому пока fullbright включён, для неё возвращается максимум. Сохранённая в настройках яркость не меняется.
 */
@Mixin(SimpleOption.class)
public abstract class SimpleOptionMixin {
    @Inject(method = "getValue", at = @At("RETURN"), cancellable = true, require = 0)
    private void swa$fullbright(CallbackInfoReturnable<Object> cir) {
        FullbrightModule m = ModuleManager.fullbright;
        if (m == null || !m.isEnabled()) return;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.options != null && (Object) this == mc.options.getGamma()) cir.setReturnValue(15.0);
        } catch (Throwable ignored) {
            // косметика
        }
    }

    /**
     * Removals: тени сущностей и искажение (тошнота, портал) читаются через соответствующие опции.
     * Пока модуль включён, игра видит «выключено», а сохранённые в настройках значения не меняются.
     */
    @Inject(method = "getValue", at = @At("RETURN"), cancellable = true, require = 0)
    private void swa$removalsOptions(CallbackInfoReturnable<Object> cir) {
        RemovalsModule r = ModuleManager.removals;
        if (r == null || !r.isEnabled()) return;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.options == null) return;
            Object self = this;
            if (r.shadowsOff() && self == mc.options.getEntityShadows()) cir.setReturnValue(Boolean.FALSE);
            else if (r.nauseaOff() && self == mc.options.getDistortionEffectScale()) cir.setReturnValue(0.0);
        } catch (Throwable ignored) {
            // косметика
        }
    }
}
