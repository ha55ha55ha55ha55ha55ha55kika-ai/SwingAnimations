package com.kitkatvisuals.mixin;

import com.kitkatvisuals.link.ClientLink;
import com.kitkatvisuals.module.ClientLinkModule;
import com.kitkatvisuals.module.ModuleManager;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** ClientLink: значок логотипа в списке игроков (TAB). */
@Mixin(PlayerListHud.class)
public abstract class PlayerListHudMixin {
    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true, require = 0)
    private void swa$badge(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
        try {
            ClientLinkModule m = ModuleManager.clientLink;
            if (m == null || !m.isEnabled() || !m.badgeTab()) return;
            if (ClientLink.isLinked(entry.getProfile().getId())) cir.setReturnValue(ClientLink.withBadge(cir.getReturnValue()));
        } catch (Throwable ignored) {
            // косметика
        }
    }
}
