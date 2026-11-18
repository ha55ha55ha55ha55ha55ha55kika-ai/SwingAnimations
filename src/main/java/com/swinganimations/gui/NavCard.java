package com.swinganimations.gui;

import com.swinganimations.module.WaypointSource;
import com.swinganimations.render.Batch;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

import java.util.List;

/**
 * Навигационная карточка вверху экрана: стрелка в кружке показывает, куда идти к ближайшей метке,
 * справа название, расстояние и «выше / ниже». Используется метками ивентов и смерти (у каждого своя галочка в настройках).
 * Только fill-примитивы; считается лишь одна ближайшая метка источника, поэтому на FPS не влияет.
 */
public final class NavCard {
    private static final int H = 34;
    private static int slot;

    private NavCard() {}

    /** Вызывается раз в кадр перед отрисовкой HUD модулей: карточки разных источников встают друг под другом. */
    public static void nextFrame() { slot = 0; }

    public static void show(DrawContext ctx, List<WaypointSource.Waypoint> list, float td) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (list == null || list.isEmpty() || p == null || mc.world == null || mc.options.hudHidden || slot >= 3) return;
        double px = p.getX(), py = p.getY(), pz = p.getZ();
        WaypointSource.Waypoint best = null;
        double bd = Double.MAX_VALUE;
        for (WaypointSource.Waypoint w : list) {
            double dx = w.x() - px, dz = w.z() - pz;
            double d = dx * dx + dz * dz;
            if (d < bd) {
                bd = d;
                best = w;
            }
        }
        if (best == null) return;
        double dist = Math.sqrt(bd);
        if (dist < 4.0) return; // уже на месте

        // Направление: угол на метку относительно взгляда игрока (стрелка вверх = метка прямо по курсу)
        double bearing = Math.toDegrees(Math.atan2(-(best.x() - px), best.z() - pz));
        float rel = MathHelper.wrapDegrees((float) (bearing - p.getYaw(td)));
        float ang = (float) Math.toRadians(rel);

        double dy = best.y() - py;
        String dText = Text.translatableWithFallback("swinganimations.nav.dist", "%s m", Math.round(dist)).getString();
        if (dy > 4.0) dText += " \u00B7 " + Text.translatableWithFallback("swinganimations.nav.above", "above").getString();
        else if (dy < -4.0) dText += " \u00B7 " + Text.translatableWithFallback("swinganimations.nav.below", "below").getString();
        String name = best.name() == null || best.name().isEmpty()
                ? Text.translatableWithFallback("swinganimations.nav.target", "Waypoint").getString() : best.name();

        int sw = ctx.getScaledWindowWidth();
        int w = Math.min(sw - 16, Math.max(130, 46 + Math.max(Hud.w(name), Hud.w(dText)) + 14));
        int x = (sw - w) / 2;
        int y = 8 + slot * (H + 5);
        slot++;
        int rgb = best.rgb() & 0xFFFFFF;

        Ui.rect(ctx, x, y, w, H, 9, 0xD2101218);
        Ui.outline(ctx, x, y, w, H, 9, Batch.argb(rgb, 0.45f));
        int cx = x + 21, cy = y + H / 2;
        Ui.rect(ctx, cx - 12, cy - 12, 24, 24, 12, Batch.argb(0xFFFFFF, 0.07f));
        Ui.outline(ctx, cx - 12, cy - 12, 24, 24, 12, Batch.argb(0xFFFFFF, 0.28f));

        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(cx, cy, 0f);
        m.multiply(RotationAxis.POSITIVE_Z.rotation(ang));
        for (int yy = -7; yy <= 5; yy++) { // стрелка-«навигатор» с выемкой снизу
            int hw = (yy + 7) * 6 / 12;
            int gap = yy >= 1 ? (yy * 3) / 5 : -1;
            if (gap < 0) {
                ctx.fill(-hw, yy, hw + 1, yy + 1, 0xFFE8EAF0);
            } else {
                ctx.fill(-hw, yy, -gap, yy + 1, 0xFFE8EAF0);
                ctx.fill(gap + 1, yy, hw + 1, yy + 1, 0xFFE8EAF0);
            }
        }
        m.pop();

        Hud.text(ctx, name, x + 42, y + 7, Ui.TEXT);
        Hud.text(ctx, dText, x + 42, y + 19, Ui.TEXT_DIM);
    }
}
