package com.kitkatvisuals.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/**
 * Маленькая отдельная программа: запускается из временной копии jar уже после закрытия игры (на Windows jar мода
 * заблокирован, пока Minecraft работает), ждёт выхода игры и подменяет старый jar новым. Ничего из Minecraft не использует.
 * Аргументы: pid игры, новый файл (.new), целевой jar.
 */
public final class SwapHelper {
    private SwapHelper() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 3) return;
        long pid = Long.parseLong(args[0]);
        Path src = Path.of(args[1]);
        Path dst = Path.of(args[2]);
        ProcessHandle.of(pid).ifPresent(h -> {
            try {
                h.onExit().get(180, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // дальше всё равно пробуем подменить
            }
        });
        for (int i = 0; i < 60; i++) {
            if (!Files.isRegularFile(src)) return; // уже установлено
            try {
                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException e) {
                Thread.sleep(500);
            }
        }
    }
}
