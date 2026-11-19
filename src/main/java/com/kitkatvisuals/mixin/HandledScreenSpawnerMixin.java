package com.kitkatvisuals.mixin;

import com.kitkatvisuals.gui.SpawnerPanel;
import com.kitkatvisuals.input.KeyBinds;
import com.kitkatvisuals.module.ModuleManager;
import com.kitkatvisuals.module.SpawnerSearchModule;
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
    @Shadow protected int x;
    @Shadow protected int y;
    @Shadow protected int backgroundWidth;
    @Shadow protected int backgroundHeight;

    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;IIF)V", at = @At("TAIL"), require = 0)
    private void swa$trackHover(DrawContext ctx, int mx, int my, float delta, CallbackInfo ci) {
        SpawnerSearchModule.hovered = focusedSlot;
        try {
            SpawnerSearchModule m = ModuleManager.spawnerSearch;
            if (m != null && m.isEnabled()) {
                m.renderOverlay((HandledScreen<?>) (Object) this, ctx, mx, my, focusedSlot, x, y, backgroundWidth, backgroundHeight);
            }
        } catch (Throwable ignored) {
            // панель не должна ломать интерфейс
        }
    }

    /** Клики по панели SpawnerSearch не доходят до слотов контейнера. */
    @Inject(method = "mouseClicked(DDI)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private void swa$panelClick(double mx, double my, int button, CallbackInfoReturnable<Boolean> cir) {
        try {
            SpawnerSearchModule m = ModuleManager.spawnerSearch;
            if (m != null && m.isEnabled() && SpawnerPanel.click(mx, my, button)) cir.setReturnValue(true);
        } catch (Throwable ignored) {
            // не критично
        }
    }

    @Inject(method = "mouseReleased(DDI)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private void swa$panelRelease(double mx, double my, int button, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (SpawnerPanel.release()) cir.setReturnValue(true);
        } catch (Throwable ignored) {
            // не критично
        }
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

    @Inject(method = "drawSlot(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/screen/slot/Slot;)V", at = @At("TAIL"), require = 0)
    private void swa$anarchyOverlay(DrawContext ctx, Slot slot, CallbackInfo ci) {
        try {
            com.kitkatvisuals.module.AnarchyFinderModule m = ModuleManager.anarchyFinder;
            if (m != null && m.isEnabled()) m.drawSlot(ctx, slot);
        } catch (Throwable ignored) {
            // не критично
        }
    }

    /** AnarchyFinder: клавиша поиска работает и в открытом меню анархий. */
    @Inject(method = "keyPressed(III)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private void swa$anarchyFind(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        try {
            com.kitkatvisuals.module.AnarchyFinderModule m = ModuleManager.anarchyFinder;
            if (m != null && m.isEnabled() && KeyBinds.ANARCHY_FIND != null && KeyBinds.ANARCHY_FIND.matchesKey(keyCode, scanCode)) {
                m.run(net.minecraft.client.MinecraftClient.getInstance());
                cir.setReturnValue(true);
            }
        } catch (Throwable ignored) {
            // не критично
        }
    }
}
