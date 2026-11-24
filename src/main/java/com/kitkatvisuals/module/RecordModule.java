package com.kitkatvisuals.module;

import com.kitkatvisuals.KitKatVisualsClient;
import com.kitkatvisuals.gui.Hud;
import com.kitkatvisuals.gui.HudLayout;
import com.kitkatvisuals.gui.Ui;
import com.kitkatvisuals.record.GlCapture;
import com.kitkatvisuals.record.RecordEncoder;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Record: запись игры в видео прямо из мода (AVI, Motion JPEG, без звука) почти без потери FPS.
 * Включение модуля = начало записи, выключение = конец (клавиша «Record» просто переключает модуль).
 * Как это дёшево: кадр уменьшается на видеокарте, читается асинхронно через PBO (GlCapture), а JPEG-кодирование и запись
 * на диск идут в отдельном потоке с низким приоритетом (RecordEncoder). Если кодировщик не успевает, кадры отбрасываются,
 * а не тормозят игру. Захват делает RecordFrameMixin в конце GameRenderer.render (в кадре уже есть HUD).
 */
public final class RecordModule extends Module {
    private final Setting.Mode fps = add(new Setting.Mode("fps", "FPS", "30", "24", "15", "60"));
    private final Setting.Num scale = add(new Setting.Num("scale", "Resolution (%)", 50f, 25f, 100f, 5f));
    private final Setting.Num quality = add(new Setting.Num("quality", "JPEG quality", 70f, 30f, 95f, 5f));
    private final Setting.Num maxMinutes = add(new Setting.Num("max_minutes", "Max length (min)", 10f, 1f, 60f, 1f));
    private final Setting.Bool indicator = add(new Setting.Bool("indicator", "Show REC indicator", true));

    private final HudLayout.Win win = HudLayout.win("record");

    private GlCapture cap;
    private RecordEncoder enc;
    private boolean started;
    private long t0;
    private long nextDue;
    private long intervalNs;

    public RecordModule() {
        super("record", "Record", "hud");
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> {
            RecordEncoder e = enc;
            if (e != null) {
                finish();
                e.join(5000); // дописать файл до выхода из игры
            }
        });
    }

    @Override
    public void onEnable() {
        started = false; // GL-ресурсы создаются в первом кадре (onFrameEnd), когда контекст точно текущий
    }

    @Override
    public void onDisable() {
        finish();
    }

    private void finish() {
        RecordEncoder e = enc;
        GlCapture c = cap;
        enc = null;
        cap = null;
        started = false;
        if (c != null) {
            try {
                c.destroy();
            } catch (Throwable ignored) {
                // контекст мог закрыться при выходе
            }
        }
        if (e != null) e.stop();
    }

    /** Вызывается RecordFrameMixin после отрисовки каждого кадра (поток рендера). */
    public void onFrameEnd() {
        if (!isEnabled()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        try {
            if (!started && !begin(mc)) return;
            long now = System.nanoTime();
            if (now < nextDue) return;
            nextDue += intervalNs;
            if (now > nextDue + intervalNs * 3) nextDue = now + intervalNs; // после подвисания не догоняем пачкой
            Framebuffer fb = mc.getFramebuffer();
            if (fb == null || fb.textureWidth <= 0 || fb.textureHeight <= 0 || cap == null || enc == null) return;
            cap.capture(fb.fbo, fb.textureWidth, fb.textureHeight, now - t0, enc);
            if (now - t0 > (long) (maxMinutes.value * 60_000_000_000L)) mc.execute(() -> setEnabled(false));
        } catch (Throwable t) {
            KitKatVisualsClient.LOGGER.warn("Record: {}", t.toString());
            fail(mc, t.getMessage() == null ? t.toString() : t.getMessage());
        }
    }

    private boolean begin(MinecraftClient mc) {
        int sw = mc.getWindow().getFramebufferWidth();
        int sh = mc.getWindow().getFramebufferHeight();
        if (sw <= 0 || sh <= 0) return false; // окно свёрнуто
        try {
            int ow = Math.max(64, (Math.round(sw * scale.value / 100f)) & ~1);
            int oh = Math.max(64, (Math.round(sh * scale.value / 100f)) & ~1);
            int f = Integer.parseInt(fps.get());
            Path dir = FabricLoader.getInstance().getGameDir().resolve("kitkat_recordings");
            Files.createDirectories(dir);
            if (Files.getFileStore(dir).getUsableSpace() < 300L * 1024 * 1024) {
                fail(mc, "меньше 300 МБ свободного места");
                return false;
            }
            Path file = dir.resolve("rec_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".avi");
            cap = new GlCapture(ow, oh);
            enc = new RecordEncoder(file, ow, oh, f, (int) quality.value, 100L * 1024 * 1024, this::onEncoderDone);
            intervalNs = 1_000_000_000L / f;
            t0 = System.nanoTime();
            nextDue = t0;
            started = true;
            if (mc.player != null) {
                mc.player.sendMessage(Text.translatableWithFallback("kitkatvisuals.msg.record_start",
                        "[Record] recording: %1$s (%2$sx%3$s, %4$s fps)", file.getFileName().toString(), ow, oh, f), false);
            }
            return true;
        } catch (Throwable t) {
            KitKatVisualsClient.LOGGER.warn("Record: не удалось начать: {}", t.toString());
            finish();
            fail(mc, t.getMessage() == null ? t.toString() : t.getMessage());
            return false;
        }
    }

    private void fail(MinecraftClient mc, String why) {
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendMessage(Text.translatableWithFallback("kitkatvisuals.msg.record_fail",
                        "[Record] error: %1$s", why), false);
            }
            if (isEnabled()) setEnabled(false);
        });
    }

    /** Поток кодировщика: файл закрыт, сообщаем итог в чат. */
    private void onEncoderDone(RecordEncoder.Result r) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            if (mc.player == null) return;
            if (r.error() != null) {
                mc.player.sendMessage(Text.translatableWithFallback("kitkatvisuals.msg.record_fail",
                        "[Record] error: %1$s", r.error()), false);
                if (isEnabled()) setEnabled(false);
            }
            if (r.frames() > 0) {
                mc.player.sendMessage(Text.translatableWithFallback("kitkatvisuals.msg.record_done",
                        "[Record] saved: kitkat_recordings/%1$s (%2$s s, %3$s MB)", r.file().getFileName().toString(),
                        String.format(Locale.ROOT, "%.1f", r.seconds()),
                        String.format(Locale.ROOT, "%.1f", r.bytes() / 1048576.0)), false);
            }
        });
    }

    @Override
    public void renderHud(DrawContext ctx, float tickDelta) {
        if (!indicator.value || MinecraftClient.getInstance().options.hudHidden) return;
        boolean editing = HudLayout.editing();
        if (!started && !editing) return;
        // кадр, который сейчас попадёт в видео, рисуем без индикатора: в записи его не будет
        if (started && !editing && System.nanoTime() >= nextDue) return;
        long sec = started ? (System.nanoTime() - t0) / 1_000_000_000L : 0L;
        RecordEncoder e = enc;
        int drops = e == null ? 0 : e.drops();
        String s = String.format(Locale.ROOT, "REC %02d:%02d", sec / 60, sec % 60) + (drops > 0 ? "  drop " + drops : "");
        int w = Hud.w(s) + 22;
        int h = 18;
        int x = ctx.getScaledWindowWidth() / 2 - w / 2;
        int y = 6;
        HudLayout.push(ctx, win);
        Hud.panel(ctx, x, y, w, h);
        boolean on = (System.currentTimeMillis() / 500) % 2 == 0;
        ctx.fill(x + 7, y + 6, x + 13, y + 12, on ? 0xFFFF3B3B : 0xFF7A1E1E);
        Hud.text(ctx, s, x + 18, y + 5, drops > 0 ? 0xFFFFB347 : Ui.TEXT);
        HudLayout.pop(ctx, win, x, y, w, h);
    }
}
