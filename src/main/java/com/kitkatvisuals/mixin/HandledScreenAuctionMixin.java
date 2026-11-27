package com.kitkatvisuals.mixin;

import com.kitkatvisuals.module.HolyAuctionModule;
import com.kitkatvisuals.module.ModuleManager;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** HolyAuction: подкрашивает слоты лотов, яиц дракона и особого компаса до отрисовки предмета. */
@Mixin(HandledScreen.class)
public abstract class HandledScreenAuctionMixin {
    @Inject(method = "drawSlot(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/screen/slot/Slot;)V", at = @At("HEAD"), require = 0)
    private void kk$auctionFill(DrawContext ctx, Slot slot, CallbackInfo ci) {
        try {
            HolyAuctionModule m = ModuleManager.holyAuction;
            if (m != null && m.isEnabled()) m.fillSlot(ctx, slot);
        } catch (Throwable ignored) {
            // подсветка не должна ломать интерфейс
        }
    }
}
