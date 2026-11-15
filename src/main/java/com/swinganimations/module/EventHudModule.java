package com.swinganimations.module;

import com.swinganimations.SwingAnimationsClient;
import com.swinganimations.api.HolyWorldApi;
import com.swinganimations.gui.Hud;
import com.swinganimations.gui.HudLayout;
import com.swinganimations.gui.Ui;
import com.swinganimations.render.Batch;
import com.swinganimations.render.Projection;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EventHud: список активных ивентов HolyWorld (сверху по 6 строк, остальное листается клавишей «Следующая страница»).
 * Фильтр по номеру анархии (0 = все сервера). Координаты берутся из чата в момент объявления ивента: мод ставит метку
 * (маяк в мире и подпись с расстоянием). Формат строки с координатами заранее неизвестен, поэтому парсер терпимый
 * и пишет подходящие строки в лог с префиксом «EventHud chat:», чтобы их можно было прислать и уточнить.
 */
public final class EventHudModule extends Module {
    private static final int ROWS = 6;
    private static final Pattern COORDS = Pattern.compile("(-?\\d{1,7})\\D{1,12}(-?\\d{1,3})\\D{1,12}(-?\\d{1,7})");
    private static final Pattern HINT = Pattern.compile("(?i)ивент|event|коорд|coord|x\\s*[:=]|кординат");

    private final Setting.Num server = add(new Setting.Num("server", "Anarchy number (0 = all)", 0f, 0f, 999f, 1f));
    private final Setting.Num rows = add(new Setting.Num("rows", "Rows", 6f, 3f, 10f, 1f));
    private final Setting.Bool autoMarker = add(new Setting.Bool("auto_marker", "Auto marker from chat", true));
    private final Setting.Num markerTtl = add(new Setting.Num("marker_ttl", "Marker lifetime (min)", 10f, 1f, 60f, 1f));
    private final Setting.Bool debugChat = add(new Setting.Bool("debug_chat", "Log chat lines with coords", true));
    private final Setting.Num interval = add(new Setting.Num("interval", "Update interval (s)", 15f, 10f, 60f, 1f));

    private final HudLayout.Win win = HudLayout.win("event_hud");
    private int page;
    private final List<Marker> markers = new ArrayList<>();
    private final float[] scr = new float[2];

    private static final class Marker {
        final double x, y, z;
        final String name;
        final long created;

        Marker(double x, double y, double z, String name, long created) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.name = name;
            this.created = created;
        }
    }

    public EventHudModule() {
        super("event_hud", "EventHud", "hud");
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
            if (!overlay && isEnabled()) onChat(msg.getString());
        });
    }

    @Override
    public void onEnable() {
        HolyWorldApi.start((int) interval.value);
    }

    @Override
    public void onDisable() {
        HolyWorldApi.stop();
        markers.clear();
    }

    public void nextPage() {
        page++;
    }

    // ---------------- чат -> метка ----------------

    /** Ищем три числа (X Y Z) в строке, где есть слово про ивент или координаты. Метка ставится только по такой строке. */
    void onChat(String raw) {
        if (!HINT.matcher(raw).find()) return;
        Matcher m = COORDS.matcher(raw);
        if (!m.find()) return;
        int x = Integer.parseInt(m.group(1));
        int y = Integer.parseInt(m.group(2));
        int z = Integer.parseInt(m.group(3));
        if (y < -64 || y > 320) return;
        if (debugChat.value) SwingAnimationsClient.LOGGER.info("EventHud chat: {}", raw);
        if (!autoMarker.value) return;
        String name = currentEventName(raw);
        markers.removeIf(k -> k.name.equals(name));
        markers.add(new Marker(x + 0.5, y, z + 0.5, name, System.currentTimeMillis()));
        while (markers.size() > 3) markers.remove(0);
    }

    /** Название берём из текущего списка API: если в строке упомянут ивент, то его, иначе «Ивент». */
    private static String currentEventName(String raw) {
        for (HolyWorldApi.Event e : HolyWorldApi.latest()) {
            if (raw.contains(e.name())) return e.name();
        }
        return "Ивент";
    }

    @Override
    public boolean wantsWorldRender() { return !markers.isEmpty(); }

    @Override
    public void render(RenderContext c) {
        long now = c.now;
        long ttl = (long) (markerTtl.value * 60_000L);
        markers.removeIf(k -> now - k.created > ttl);
        for (Marker k : markers) drawBeacon(c, k, now);
    }

    /** Вертикальный луч света от точки вверх: снизу ярче, к верху растворяется, слегка пульсирует. */
    private static void drawBeacon(RenderContext c, Marker k, long now) {
        int rgb = Ui.ACCENT & 0xFFFFFF;
        float pulse = 0.8f + 0.2f * (float) Math.sin(now / 350.0);
        double h = 48;
        double r = 0.22;
        Matrix4f m = c.m;
        Batch.begin(true, true);
        Batch b = Batch.triangles();
        for (int i = 0; i < 4; i++) {
            double a0 = i * Math.PI / 2, a1 = (i + 1) * Math.PI / 2;
            double x0 = k.x + Math.cos(a0) * r, z0 = k.z + Math.sin(a0) * r;
            double x1 = k.x + Math.cos(a1) * r, z1 = k.z + Math.sin(a1) * r;
            int lo = Batch.argb(rgb, 0.55f * pulse);
            int hi = Batch.argb(rgb, 0f);
            b.v(m, x0, k.y, z0, lo); b.v(m, x1, k.y, z1, lo); b.v(m, x1, k.y + h, z1, hi);
            b.v(m, x0, k.y, z0, lo); b.v(m, x1, k.y + h, z1, hi); b.v(m, x0, k.y + h, z0, hi);
        }
        b.flush();
        Batch.end();
    }

    // ---------------- HUD ----------------

    @Override
    public void renderHud(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden) return;
        int sw = ctx.getScaledWindowWidth();
        int sh = ctx.getScaledWindowHeight();
        drawMarkerLabels(ctx, sw, sh);

        List<HolyWorldApi.Event> all = filtered();
        boolean edit = HudLayout.editing();
        int perPage = Math.max(3, (int) rows.value);
        int pages = Math.max(1, (all.size() + perPage - 1) / perPage);
        if (page >= pages) page = 0;

        int x = 8;
        int y = 8 + 16 + 4 + 16 + 4 + 16 + 4;
        int w = 170;
        int h = 16 + 4 + perPage * 16 + 4 * (perPage - 1) + 6;
        HudLayout.push(ctx, win);
        Hud.panel(ctx, x, y, w, h);
        String title = "Ивенты" + (server.value > 0 ? " #" + (int) server.value : "") + "  " + (page + 1) + "/" + pages;
        Hud.text(ctx, title, x + 8, y + 6, Ui.TEXT);
        int ry = y + 22;
        if (all.isEmpty()) {
            String msg = edit ? "Пример: Золотая лихорадка" : "Сейчас ивентов нет";
            Hud.text(ctx, msg, x + 8, ry + 4, Ui.TEXT_DIM);
        } else {
            int from = page * perPage;
            int to = Math.min(all.size(), from + perPage);
            for (int i = from; i < to; i++) {
                HolyWorldApi.Event e = all.get(i);
                Hud.chip(ctx, x + 6, ry, w - 12, 16);
                Hud.text(ctx, e.name(), x + 14, ry + 4, rareColor(e.rare()));
                String tag = e.server().replace("LITE_NEW_ANARCHY_", "N").replace("LITE_ANARCHY_", "A");
                Hud.textRight(ctx, tag, x + w - 14, ry + 4, Ui.TEXT_DIM);
                ry += 20;
            }
        }
        HudLayout.pop(ctx, win, x, y, w, h);
    }

    private void drawMarkerLabels(DrawContext ctx, int sw, int sh) {
        if (!Projection.valid()) return;
        for (Marker k : markers) {
            if (!Projection.project(k.x, k.y + 2, k.z, scr, sw, sh)) continue;
            int px = Math.round(scr[0]);
            int py = Math.round(scr[1]);
            int d = Math.round((float) Projection.distance(k.x, k.y, k.z));
            String text = k.name + "  " + d + " м";
            int tw = Hud.w(text) + 16;
            Hud.chip(ctx, px - tw / 2, py - 22, tw, 16);
            Hud.text(ctx, text, px - tw / 2 + 8, py - 18, Ui.TEXT);
            ctx.fill(px - 1, py - 4, px + 1, py, Ui.ACCENT);
        }
    }

    private List<HolyWorldApi.Event> filtered() {
        if (HudLayout.editing() && HolyWorldApi.latest().isEmpty()) return List.of();
        List<HolyWorldApi.Event> out = new ArrayList<>();
        for (HolyWorldApi.Event e : HolyWorldApi.latest()) {
            if (HolyWorldApi.onAnarchy(e.server(), (int) server.value)) out.add(e);
        }
        return out;
    }

    private static int rareColor(String rare) {
        if (rare == null) return Ui.TEXT;
        return switch (rare.toUpperCase(java.util.Locale.ROOT)) {
            case "LEGENDARY" -> 0xFFFFC93C;
            case "EPIC" -> 0xFFB65CFF;
            case "RARE" -> 0xFF5CB8FF;
            default -> Ui.TEXT;
        };
    }
}
