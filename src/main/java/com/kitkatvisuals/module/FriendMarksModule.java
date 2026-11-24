package com.kitkatvisuals.module;

import com.kitkatvisuals.link.FriendLink;
import com.kitkatvisuals.render.Projection;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * «Метки друзей»: метка, поставленная тобой или другом (клавиша или /friend mark), видна всем друзьям,
 * которые сейчас с модом на том же сервере и в том же измерении. Рисуется на экране (сквозь стены) и на мини-карте.
 * Также держит настройку «Делиться координатами» для /pronoob.
 */
public final class FriendMarksModule extends Module implements WaypointSource {
    public final Setting.Bool shareCoords;
    public final Setting.Bool showNames;
    public final Setting.Bool showDistance;
    public final Setting.Bool onMinimap;
    private final float[] scr = new float[4];

    public FriendMarksModule() {
        super("friend_marks", "Метки друзей", "hud");
        shareCoords = add(new Setting.Bool("share_coords", "Делиться координатами (/pronoob)", true));
        showNames = add(new Setting.Bool("show_names", "Ник и подпись", true));
        showDistance = add(new Setting.Bool("show_distance", "Расстояние", true));
        onMinimap = add(new Setting.Bool("minimap", "Показывать на мини-карте", true));
        this.enabled = true;        // включён по умолчанию (сохранённый конфиг это перекроет)
    }

    @Override
    public boolean wantsWorldRender() {
        return isEnabled();         // нужно, чтобы Projection получал матрицы камеры
    }

    @Override
    public void renderHud(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden || !Projection.valid()) return;
        List<FriendLink.Mark> list = FriendLink.marksHere(mc);
        if (list.isEmpty()) return;

        TextRenderer tr = mc.textRenderer;
        int w = ctx.getScaledWindowWidth();
        int h = ctx.getScaledWindowHeight();
        float pulse = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 300.0);
        for (FriendLink.Mark m : list) {
            int mode = Projection.projectOrEdge(m.x(), m.y(), m.z(), scr, w, h, EDGE);
            if (mode == 0) continue;
            int sx = Math.round(scr[0]);
            int sy = Math.round(scr[1]);
            int rgb = 0xFF000000 | FriendLink.colorFor(m.ownerName());
            long dist = Math.round(Projection.distance(m.x(), m.y(), m.z()));

            // название: если задано, оно главное, ник владельца уходит во вторую строку
            String who = m.mine() ? "Я" : m.ownerName();
            boolean named = !m.label().isEmpty();
            String line1 = showNames.value ? (named ? m.label() : who) : "";
            String line2 = (showNames.value && named ? who : "") + (showDistance.value
                    ? ((showNames.value && named ? " · " : "") + dist + " m") : "");

            if (mode == 1) {
                diamond(ctx, sx, sy, rgb, R + (int) (pulse * 1.5f));
                plate(ctx, tr, line1, sx, sy - R - 14, rgb, true);
                plate(ctx, tr, line2, sx, sy + R + 4, 0xFFD6D6DE, false);
            } else {
                float ang = scr[2];
                diamond(ctx, sx, sy, rgb, R - 2);
                arrow(ctx, sx, sy, ang, rgb);
                boolean below = sy < h / 2;                       // подпись уводим внутрь экрана
                int ty = below ? sy + R + 5 : sy - R - 22;
                plate(ctx, tr, line1, clampX(tr, line1, sx, w), ty, rgb, true);
                plate(ctx, tr, line2, clampX(tr, line2, sx, w), ty + 11, 0xFFD6D6DE, false);
            }
        }
    }

    private static final int R = 8;       // радиус ромба (px GUI)
    private static final int EDGE = 26;   // отступ меток-«стрелок» от края экрана

    private static int clampX(TextRenderer tr, String text, int x, int w) {
        int half = tr.getWidth(text) / 2 + 5;
        return Math.max(half, Math.min(w - half, x));
    }

    /** Текст на тёмной плашке: читается на любом фоне (снег, песок, свет). */
    private static void plate(DrawContext ctx, TextRenderer tr, String text, int cx, int y, int color, boolean bold) {
        if (text == null || text.isEmpty()) return;
        int tw = tr.getWidth(text);
        ctx.fill(cx - tw / 2 - 3, y - 2, cx + tw / 2 + 4, y + 10, 0xB0000000);
        if (bold) ctx.fill(cx - tw / 2 - 3, y + 9, cx + tw / 2 + 4, y + 10, color);   // цветная подчёркивающая линия
        ctx.drawCenteredTextWithShadow(tr, Text.literal(text), cx, y, color);
    }

    private static void arrow(DrawContext ctx, int cx, int cy, float angle, int rgb) {
        MatrixStack ms = ctx.getMatrices();
        ms.push();
        ms.translate(cx, cy, 0f);
        ms.multiply(RotationAxis.POSITIVE_Z.rotation(angle));
        for (int i = 0; i < 9; i++) {                             // треугольник, остриём вдоль +x, смещён за ромб
            int half = (8 - i) / 2;
            ctx.fill(R + 3 + i, -half - 1, R + 4 + i, half + 2, 0xFF000000);
            ctx.fill(R + 4 + i, -half, R + 5 + i, half + 1, rgb);
        }
        ms.pop();
    }

    private static void diamond(DrawContext ctx, int cx, int cy, int rgb, int r) {
        for (int dy = -r - 1; dy <= r + 1; dy++) {                // тёмная обводка
            int half = r + 1 - Math.abs(dy);
            ctx.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, 0xFF000000);
        }
        for (int dy = -r; dy <= r; dy++) {                        // заливка цветом владельца
            int half = r - Math.abs(dy);
            ctx.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, rgb);
        }
        ctx.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFFFFFFF);     // белая точка в центре
    }

    /** Источник меток для мини-карты (MinimapModule обходит ModuleManager.MODULES и берёт всё, что реализует WaypointSource). */
    @Override
    public List<WaypointSource.Waypoint> waypoints() {
        if (!isEnabled() || !onMinimap.value) return List.of();
        MinecraftClient mc = MinecraftClient.getInstance();
        List<WaypointSource.Waypoint> out = new ArrayList<>();
        for (FriendLink.Mark m : FriendLink.marksHere(mc)) {
            String who = m.mine() ? "Я" : m.ownerName();
            String name = m.label().isEmpty() ? who : who + ": " + m.label();
            out.add(new WaypointSource.Waypoint(m.x(), m.y(), m.z(), name, FriendLink.colorFor(m.ownerName()), false));
        }
        return out;
    }
}
