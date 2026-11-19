package com.kitkatvisuals.mixin;

import com.kitkatvisuals.module.BlockWarsBotModule;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** BlockWars-бот: перехватывает title / subtitle / actionbar, пришедшие от сервера. require = 0: при другой сигнатуре просто не сработает. */
@Mixin(InGameHud.class)
public abstract class InGameHudTitleMixin {
    @Inject(method = "setTitle(Lnet/minecraft/text/Text;)V", at = @At("HEAD"), require = 0)
    private void swa$title(Text t, CallbackInfo ci) { BlockWarsBotModule.feed(t); }

    @Inject(method = "setSubtitle(Lnet/minecraft/text/Text;)V", at = @At("HEAD"), require = 0)
    private void swa$subtitle(Text t, CallbackInfo ci) { BlockWarsBotModule.feed(t); }

    @Inject(method = "setOverlayMessage(Lnet/minecraft/text/Text;Z)V", at = @At("HEAD"), require = 0)
    private void swa$overlay(Text t, boolean tinted, CallbackInfo ci) { BlockWarsBotModule.feed(t); }
}
