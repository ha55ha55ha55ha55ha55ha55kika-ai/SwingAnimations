package com.kitkatvisuals.mixin;

import com.kitkatvisuals.module.ModuleManager;
import com.kitkatvisuals.module.TotemEffectModule;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TotemEffect. Целевой класс: GameRenderer, метод showFloatingItem(ItemStack). Ваниль зовёт его, когда сработал
 * ваш тотем; вместо ванильной анимации запускаем свою (звук и частицы ваниль проигрывает отдельно).
 */
@Mixin(GameRenderer.class)
public abstract class TotemFloatingItemMixin {
    @Inject(method = "showFloatingItem(Lnet/minecraft/item/ItemStack;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void kitkat$totem(ItemStack stack, CallbackInfo ci) {
        try {
            TotemEffectModule m = ModuleManager.totemEffect;
            if (m != null && stack.isOf(Items.TOTEM_OF_UNDYING) && m.start(stack)) ci.cancel();
        } catch (Throwable ignored) {
            // косметика: при сбое остаётся ванильная анимация
        }
    }
}
