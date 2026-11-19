package com.kitkatvisuals.mixin;

import com.kitkatvisuals.link.ClientLink;
import com.kitkatvisuals.module.ClientLinkModule;
import com.kitkatvisuals.module.ModuleManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** ClientLink: значок логотипа перед ником над головой игрока с нашим клиентом. */
@Mixin(PlayerEntity.class)
public abstract class PlayerDisplayNameMixin {
    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true, require = 0)
    private void swa$badge(CallbackInfoReturnable<Text> cir) {
        try {
            PlayerEntity self = (PlayerEntity) (Object) this;
            if (!self.getWorld().isClient) return;
            ClientLinkModule m = ModuleManager.clientLink;
            if (m == null || !m.isEnabled() || !m.badgeName()) return;
            if (ClientLink.isLinked(self.getUuid())) cir.setReturnValue(ClientLink.withBadge(cir.getReturnValue()));
        } catch (Throwable ignored) {
            // косметика: при сбое остаётся обычный ник
        }
    }
}
