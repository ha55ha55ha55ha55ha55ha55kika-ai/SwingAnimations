package com.swinganimations.module;

import com.swinganimations.gui.Hud;
import com.swinganimations.gui.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Минималистичный HUD: ватермарка с логотипом, список биндов, эффекты и координаты.
 * Перенесён только внешний вид (тёмные скруглённые панели), без функций из чит-клиента.
 */
public final class InterfaceModule extends Module {
    private static final Identifier LOGO = Identifier.of("swinganimations", "textures/gui/logo_small.png");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final Setting.Bool watermark = add(new Setting.Bool("watermark", "Watermark", true));
    private final Setting.Bool keybinds = add(new Setting.Bool("keybinds", "Keybinds", true));
    private final Setting.Bool effects = add(new Setting.Bool("effects", "Effects", true));
    private final Setting.Bool coords = add(new Setting.Bool("coords", "Coordinates", true));

    public InterfaceModule() {
        super("interface", "Interface", "hud");
    }

    @Override
    public void renderHud(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.options.hudHidden) return;
        int bg = Hud.bg();
        int x = 8;
        int y = 8;
        if (watermark.value) y = watermark(ctx, mc, x, y, bg) + 4;
        if (keybinds.value) y = keybindsPanel(ctx, x, y, bg);
        if (effects.value) y = effectsPanel(ctx, p, x, y, bg);
        if (coords.value) coordsPanel(ctx, p, x, y, bg);
    }

    private static void box(DrawContext ctx, int x, int y, int w, int h, int bg) {
        Hud.panel(ctx, x, y, w, h);
    }

    private int watermark(DrawContext ctx, MinecraftClient mc, int x, int y, int bg) {
        String name = mc.getSession().getUsername();
        String fps = mc.getCurrentFps() + " fps";
        String time = LocalTime.now().format(TIME);
        int pad = 6, lw = 17, lh = 12, gap = 8, h = 18;
        int w = pad + lw + 5 + Hud.w(name) + gap + Hud.w(fps) + gap + Hud.w(time) + pad;
        box(ctx, x, y, w, h, bg);
        ctx.drawTexture(RenderLayer::getGuiTextured, LOGO, x + pad, y + (h - lh) / 2, 0f, 0f, lw, lh, 128, 92, 128, 92);
        int tx = x + pad + lw + 5;
        int ty = y + (h - 8) / 2;
        Hud.text(ctx, name, tx, ty, Ui.TEXT);
        tx += Hud.w(name) + gap;
        Hud.text(ctx, Text.literal(fps), tx, ty, Ui.TEXT_DIM);
        tx += Hud.w(fps) + gap;
        Hud.text(ctx, Text.literal(time), tx, ty, Ui.TEXT);
        return y + h;
    }

    private int keybindsPanel(DrawContext ctx, int x, int y, int bg) {
        List<Text> left = new ArrayList<>();
        List<Text> right = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        for (Module m : ModuleManager.MODULES) {
            if (m.bindCode < 0) continue;
            left.add(Text.translatableWithFallback("swinganimations.module." + m.id, m.fallbackName));
            right.add(keyText(m));
            colors.add(m.isEnabled() ? Ui.TEXT : Ui.TEXT_DIM);
        }
        if (left.isEmpty()) return y;
        return list(ctx, x, y, Text.translatableWithFallback("swinganimations.hud.keybinds", "Keybinds"), left, right, colors, bg);
    }

    private int effectsPanel(DrawContext ctx, ClientPlayerEntity p, int x, int y, int bg) {
        List<Text> left = new ArrayList<>();
        List<Text> right = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        for (StatusEffectInstance e : p.getStatusEffects()) {
            Text name = e.getEffectType().value().getName();
            Text n = e.getAmplifier() > 0 ? Text.empty().append(name).append(" " + (e.getAmplifier() + 1)) : name;
            left.add(n);
            right.add(Text.literal(duration(e)));
            colors.add(e.getEffectType().value().isBeneficial() ? Ui.TEXT : 0xFFFF8A8A);
        }
        if (left.isEmpty()) return y;
        return list(ctx, x, y, Text.translatableWithFallback("swinganimations.hud.effects", "Effects"), left, right, colors, bg);
    }

    private int coordsPanel(DrawContext ctx, ClientPlayerEntity p, int x, int y, int bg) {
        String s = String.format(Locale.ROOT, "XYZ  %.0f  %.0f  %.0f", p.getX(), p.getY(), p.getZ());
        int w = Hud.w(s) + 12;
        box(ctx, x, y, w, 16, bg);
        Hud.text(ctx, Text.literal(s), x + 6, y + 4, Ui.TEXT);
        return y + 20;
    }

    /** Панель «заголовок + строки слева/справа». Возвращает y следующей панели. */
    private int list(DrawContext ctx, int x, int y, Text title, List<Text> left, List<Text> right, List<Integer> colors, int bg) {
        int w = Hud.w(title) + 12;
        for (int i = 0; i < left.size(); i++) {
            w = Math.max(w, Hud.w(left.get(i)) + 14 + Hud.w(right.get(i)) + 12);
        }
        w = Math.max(w, 84);
        int h = 18 + left.size() * 11 + 3;
        box(ctx, x, y, w, h, bg);
        Hud.text(ctx, title, x + 6, y + 5, Ui.ACCENT);
        ctx.fill(x + 5, y + 15, x + w - 5, y + 16, Ui.SEP);
        for (int i = 0; i < left.size(); i++) {
            int ry = y + 19 + i * 11;
            Hud.text(ctx, left.get(i), x + 6, ry, colors.get(i));
            Hud.textRight(ctx, right.get(i), x + w - 6, ry, Ui.TEXT_DIM);
        }
        return y + h + 4;
    }

    private static Text keyText(Module m) {
        if (m.bindMouse) return Text.literal("M" + (m.bindCode + 1));
        return InputUtil.fromKeyCode(m.bindCode, -1).getLocalizedText();
    }

    private static String duration(StatusEffectInstance e) {
        if (e.isInfinite()) return "**:**";
        int sec = Math.max(0, e.getDuration() / 20);
        return String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60);
    }
}
