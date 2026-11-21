package com.kitkatvisuals.mixin;

import com.kitkatvisuals.link.DiaryLog;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ловит момент отправки команды (всё, что игрок написал в чат после "/") и, если это наша команда
 * дневника "/l <текст>", передаёт копию текста в DiaryLog (лог на хостинге). Команда всё равно уходит
 * на сервер как обычно — этот инжект только наблюдает, ничего не отменяет.
 * require = 0: имя метода сверено с yarn_mappings 1.21.4+build.8 (см. gradle.properties), но если в другой
 * версии Minecraft сигнатура изменится, мод просто не поймает дневник вместо падения при запуске.
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ChatCommandMixin {
    @Inject(method = "sendChatCommand(Ljava/lang/String;)V", at = @At("HEAD"), require = 0)
    private void kitkat$onChatCommand(String command, CallbackInfo ci) {
        try {
            DiaryLog.maybeLog(command);
        } catch (Throwable ignored) {
            // лог дневника не должен ломать отправку команды игроком
        }
    }
}
