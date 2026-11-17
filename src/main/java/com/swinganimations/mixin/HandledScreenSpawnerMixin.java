package com.swinganimations.mixin;

import com.swinganimations.input.KeyBinds;
import com.swinganimations.module.ModuleManager;
import com.swinganimations.module.SpawnerSearchModule;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** SpawnerSearch: запоминает слот под курсором, рисует подпись/подсветку на слотах и ловит клавишу дампа. Все хуки с require = 0. */
@Mixin(HandledScreen.class)
public abstract class HandledScreenSpawnerMixin {
    @Shadow protected Slot focusedSlot;

    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;IIF)V", at = @At("TAIL"), require = 0)
    private void swa$trackHover(DrawContext ctx, int mx, int my, float delta, CallbackInfo ci) {
        SpawnerSearchModule.hovered = focusedSlot;
    }

    @Inject(method = "drawSlot(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/screen/slot/Slot;)V", at = @At("TAIL"), require = 0)
    private void swa$spawnerOverlay(DrawContext ctx, Slot slot, CallbackInfo ci) {
        try {
            SpawnerSearchModule m = ModuleManager.spawnerSearch;
            if (m != null && m.isEnabled()) m.drawSlot(ctx, slot);
        } catch (Throwable ignored) {
            // не критично
        }
    }

    @Inject(method = "keyPressed(III)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private void swa$spawnerDump(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        try {
            SpawnerSearchModule m = ModuleManager.spawnerSearch;
            if (m != null && m.isEnabled() && KeyBinds.SPAWNER_DUMP != null && KeyBinds.SPAWNER_DUMP.matchesKey(keyCode, scanCode)) {
                m.dump(focusedSlot);
                cir.setReturnValue(true);
            }
        } catch (Throwable ignored) {
            // не критично
        }
    }
}
