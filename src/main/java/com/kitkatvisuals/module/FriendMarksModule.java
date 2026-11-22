package com.kitkatvisuals.module;

import com.kitkatvisuals.link.FriendLink;
import com.kitkatvisuals.render.Projection;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
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
        for (FriendLink.Mark m : list) {
            if (!Projection.project(m.x(), m.y(), m.z(), scr, w, h)) continue;
            int sx = Math.round(scr[0]);
            int sy = Math.round(scr[1]);
            if (sx < 0 || sx > w || sy < 0 || sy > h) continue;

            int rgb = 0xFF000000 | FriendLink.colorFor(m.ownerName());
            diamond(ctx, sx, sy, rgb);
            if (showNames.value) {
                String who = m.mine() ? "Я" : m.ownerName();
                String text = m.label().isEmpty() ? who : who + " — " + m.label();
                ctx.drawCenteredTextWithShadow(tr, Text.literal(text), sx, sy - 20, rgb);
            }
            if (showDistance.value) {
                long dist = Math.round(Projection.distance(m.x(), m.y(), m.z()));
                ctx.drawCenteredTextWithShadow(tr, Text.literal(dist + " m"), sx, sy + 9, 0xFFC8C8D0);
            }
        }
    }

    private static void diamond(DrawContext ctx, int cx, int cy, int rgb) {
        for (int dy = -6; dy <= 6; dy++) {                        // тёмная обводка
            int half = 6 - Math.abs(dy);
            ctx.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, 0xFF000000);
        }
        for (int dy = -5; dy <= 5; dy++) {                        // заливка цветом владельца
            int half = 5 - Math.abs(dy);
            ctx.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, rgb);
        }
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
