package com.kitkatvisuals.gui;

import com.kitkatvisuals.module.MarksModule;
import com.kitkatvisuals.util.DeathTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Список меток текущего сервера: у каждой подписана анархия (A17 или «Все»), метки чужих анархий приглушены
 * (они там не показываются). ЛКМ по цветной точке меняет цвет, по подписи анархии привязывает метку к текущей
 * анархии или делает «Все», крестик удаляет, ПКМ по строке переносит метку в поля для правки.
 * Добавление: название и координаты «X Y Z» (можно вставить из чата; пусто = где стоите).
 */
public final class MarksScreen extends Screen {
    private static final Pattern NUM = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    private static final int VIS = 7;
    private static final int ROW = 18;
    private static final int GAP = 4;
    private static final int BTN_H = 18;

    private final Screen parent;
    private final MarksModule module;
    private final List<MarksModule.Mark> rows = new ArrayList<>();
    private TextFieldWidget nameField, coordField, active;
    private int scroll;
    private boolean allAnarchies;
    private long errUntil;

    private int px, py, pw, ph, listY, nameY, coordY, btnY, btnW;

    public MarksScreen(Screen parent, MarksModule module) {
        super(Text.literal("Marks"));
        this.parent = parent;
        this.module = module;
    }

    @Override
    protected void init() {
        pw = Math.min(340, width - 16);
        ph = 24 + VIS * ROW + 6 + 20 + GAP + 20 + 6 + BTN_H + 12;
        px = (width - pw) / 2;
        py = Math.max(6, (height - ph) / 2);
        listY = py + 24;
        nameY = listY + VIS * ROW + 6;
        coordY = nameY + 20 + GAP;
        btnY = coordY + 20 + 6;
        btnW = (pw - 24 - 2 * GAP) / 3;

        nameField = new TextFieldWidget(textRenderer, px + 20, nameY + 6, pw - 40, 12, Text.empty());
        nameField.setDrawsBackground(false);
        nameField.setMaxLength(40);
        coordField = new TextFieldWidget(textRenderer, px + 20, coordY + 6, pw - 40, 12, Text.empty());
        coordField.setDrawsBackground(false);
        coordField.setMaxLength(64);
        focus(nameField);
    }

    private void focus(TextFieldWidget f) {
        active = f;
        nameField.setFocused(f == nameField);
        coordField.setFocused(f == coordField);
        setFocused(f);
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    // ---------------- данные ----------------

    private void rebuild(MinecraftClient mc) {
        rows.clear();
        String server = DeathTracker.serverKey(mc);
        for (MarksModule.Mark m : module.all()) if (m.server.equals(server)) rows.add(m);
        scroll = MathHelper.clamp(scroll, 0, Math.max(0, rows.size() - VIS));
    }

    private static String dimLetter(String dim) {
        return dim.endsWith("the_nether") ? " Н" : dim.endsWith("the_end") ? " Э" : "";
    }

    private static int[] parseCoords(String s, MinecraftClient mc) {
        Matcher m = NUM.matcher(s);
        List<Integer> v = new ArrayList<>();
        while (m.find() && v.size() < 3) v.add((int) Math.floor(Double.parseDouble(m.group())));
        if (v.size() == 3) return new int[]{v.get(0), v.get(1), v.get(2)};
        if (v.size() == 2 && mc.player != null) return new int[]{v.get(0), mc.player.getBlockY(), v.get(1)};
        return null;
    }

    private void insertHere() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        coordField.setText(mc.player.getBlockX() + " " + mc.player.getBlockY() + " " + mc.player.getBlockZ());
        focus(nameField);
    }

    private void add() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        String ct = coordField.getText().trim();
        int[] c;
        if (ct.isEmpty()) c = new int[]{mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ()};
        else c = parseCoords(ct, mc);
        if (c == null) {
            errUntil = System.currentTimeMillis() + 1200L;
            return;
        }
        int an = allAnarchies ? 0 : module.currentAnarchy(mc);
        module.addMark(mc, nameField.getText(), c[0], c[1], c[2], an);
        nameField.setText("");
        coordField.setText("");
        rebuild(mc);
        scroll = Math.max(0, rows.size() - VIS);
        focus(nameField);
    }

    // ---------------- отрисовка ----------------

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        renderBackground(ctx, mx, my, delta);
        rebuild(mc);
        int cur = module.currentAnarchy(mc);

        Ui.rect(ctx, px, py, pw, ph, 8, Ui.BG);
        Ui.outline(ctx, px, py, pw, ph, 8, Ui.OUTLINE);
        Hud.text(ctx, "Метки", px + 12, py + 8, Ui.TEXT);
        Hud.textRight(ctx, cur > 0 ? "Сейчас: анархия " + cur : "Анархия не определена", px + pw - 12, py + 8,
                cur > 0 ? Ui.ACCENT : Ui.TEXT_DIM);

        if (rows.isEmpty()) {
            String hint = "Меток нет. Введите название и координаты ниже";
            Hud.text(ctx, hint, px + (pw - Hud.w(hint)) / 2, listY + VIS * ROW / 2 - 4, Ui.TEXT_DIM);
        }
        int lx = px + 12;
        int lw = pw - 24;
        for (int r = 0; r < VIS; r++) {
            int i = scroll + r;
            if (i >= rows.size()) break;
            MarksModule.Mark m = rows.get(i);
            int y = listY + r * ROW;
            boolean on = module.shownHere(m, mc);
            boolean hov = mx >= lx && mx < lx + lw && my >= y && my < y + ROW - 2;
            Ui.rect(ctx, lx, y, lw, ROW - 2, 4, hov ? Ui.FIELD_HOVER : Ui.FIELD);

            int col = 0xFF000000 | m.rgb;
            boolean dotHov = mx >= lx + 3 && mx < lx + 17 && my >= y && my < y + ROW - 2;
            Ui.rect(ctx, lx + 5, y + 3, 10, 10, 5, on ? col : Ui.fade(col, 0.35f));
            if (dotHov) Ui.outline(ctx, lx + 4, y + 2, 12, 12, 6, Ui.ACCENT_LINE);

            String tag = m.anarchy > 0 ? "A" + m.anarchy : "Все";
            boolean here = m.anarchy == 0 || m.anarchy == cur;
            int tw = Hud.w(tag) + 10;
            int tx = lx + lw - 16 - tw;
            Ui.rect(ctx, tx, y + 2, tw, 12, 6, here ? Ui.ACCENT_BG : 0x33FF6B6B);
            Ui.outline(ctx, tx, y + 2, tw, 12, 6, here ? Ui.ACCENT_LINE : 0x66FF6B6B);
            Hud.text(ctx, tag, tx + 5, y + 4, here ? Ui.ACCENT : 0xFFFF8E8E);

            String cs = m.x + " " + m.y + " " + m.z + dimLetter(m.dim);
            int cw = Hud.w(cs);
            int cx = tx - 6 - cw;
            Hud.text(ctx, cs, cx, y + 4, Ui.TEXT_DIM);

            String nm = Ui.tr().trimToWidth(m.name, Math.max(16, cx - (lx + 21) - 6));
            Hud.text(ctx, nm, lx + 21, y + 4, on ? Ui.TEXT : Ui.TEXT_DIM);

            boolean xh = mx >= lx + lw - 14 && mx < lx + lw - 2 && my >= y && my < y + ROW - 2;
            Hud.text(ctx, "x", lx + lw - 11, y + 4, xh ? 0xFFFF6B6B : Ui.TEXT_DIM);
        }
        if (rows.size() > VIS) {
            int trackH = VIS * ROW - 2;
            int thumbH = Math.max(12, trackH * VIS / rows.size());
            int ty = listY + (trackH - thumbH) * scroll / Math.max(1, rows.size() - VIS);
            ctx.fill(px + pw - 8, ty, px + pw - 6, ty + thumbH, Ui.ACCENT_LINE);
        }

        int fx = px + 12;
        int fw = pw - 24;
        boolean err = System.currentTimeMillis() < errUntil;
        field(ctx, fx, nameY, fw, nameField, "Название метки");
        field(ctx, fx, coordY, fw, coordField, err ? "Не понял координаты, нужно X Y Z" : "X Y Z (пусто = где стою, можно вставить из чата)");
        if (err) Ui.outline(ctx, fx, coordY, fw, 20, 5, 0xFFFF6B6B);
        nameField.render(ctx, mx, my, delta);
        coordField.render(ctx, mx, my, delta);

        button(ctx, mx, my, px + 12, "Моя позиция");
        button(ctx, mx, my, px + 12 + btnW + GAP, allAnarchies || cur == 0 ? "Все анархии" : "Только A" + cur);
        button(ctx, mx, my, px + 12 + 2 * (btnW + GAP), "Добавить");
    }

    private void field(DrawContext ctx, int x, int y, int w, TextFieldWidget f, String hint) {
        Ui.rect(ctx, x, y, w, 20, 5, Ui.FIELD);
        Ui.outline(ctx, x, y, w, 20, 5, f.isFocused() ? Ui.ACCENT_LINE : Ui.OUTLINE);
        if (f.getText().isEmpty()) Hud.text(ctx, Ui.tr().trimToWidth(hint, w - 16), x + 8, y + 6, 0x77BEC3CD);
    }

    private void button(DrawContext ctx, int mx, int my, int x, String label) {
        boolean hov = mx >= x && mx < x + btnW && my >= btnY && my < btnY + BTN_H;
        Ui.rect(ctx, x, btnY, btnW, BTN_H, 6, hov ? Ui.FIELD_HOVER : Ui.FIELD);
        Ui.outline(ctx, x, btnY, btnW, BTN_H, 6, hov ? Ui.ACCENT_LINE : Ui.OUTLINE);
        String t = Ui.tr().trimToWidth(label, btnW - 6);
        Hud.text(ctx, t, x + (btnW - Hud.w(t)) / 2, btnY + (BTN_H - 8) / 2, Ui.TEXT);
    }

    // ---------------- ввод ----------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int fx = px + 12;
        int fw = pw - 24;
        if (mx >= fx && mx < fx + fw && my >= nameY && my < nameY + 20) {
            focus(nameField);
            nameField.mouseClicked(mx, my, button);
            return true;
        }
        if (mx >= fx && mx < fx + fw && my >= coordY && my < coordY + 20) {
            focus(coordField);
            coordField.mouseClicked(mx, my, button);
            return true;
        }
        if (button == 0 && my >= btnY && my < btnY + BTN_H) {
            if (mx >= px + 12 && mx < px + 12 + btnW) { insertHere(); return true; }
            if (mx >= px + 12 + btnW + GAP && mx < px + 12 + 2 * btnW + GAP) { allAnarchies = !allAnarchies; return true; }
            if (mx >= px + 12 + 2 * (btnW + GAP) && mx < px + 12 + 3 * btnW + 2 * GAP) { add(); return true; }
        }
        int lx = px + 12;
        int lw = pw - 24;
        if (mx >= lx && mx < lx + lw && my >= listY && my < listY + VIS * ROW) {
            int i = scroll + (int) ((my - listY) / ROW);
            if (i >= 0 && i < rows.size()) {
                MarksModule.Mark m = rows.get(i);
                if (button == 1) { // ПКМ: править
                    nameField.setText(m.name);
                    coordField.setText(m.x + " " + m.y + " " + m.z);
                    allAnarchies = m.anarchy == 0;
                    module.removeMark(m);
                    focus(nameField);
                } else if (button == 0) {
                    String tag = m.anarchy > 0 ? "A" + m.anarchy : "Все";
                    int tw = Hud.w(tag) + 10;
                    int tx = lx + lw - 16 - tw;
                    if (mx >= lx + lw - 14) {
                        module.removeMark(m);
                    } else if (mx >= lx + 3 && mx < lx + 17) {
                        module.cycleColor(m);
                    } else if (mx >= tx && mx < tx + tw) {
                        int cur = module.currentAnarchy(mc);
                        if (m.anarchy > 0) module.setAnarchy(mc, m, 0);
                        else if (cur > 0) module.setAnarchy(mc, m, cur);
                    }
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (my >= listY && my < listY + VIS * ROW) {
            scroll = MathHelper.clamp(scroll - (int) Math.signum(vertical), 0, Math.max(0, rows.size() - VIS));
            return true;
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) return super.keyPressed(keyCode, scanCode, modifiers);
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            add();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            focus(active == nameField ? coordField : nameField);
            return true;
        }
        if (active != null && active.keyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        return (active != null && active.charTyped(chr, modifiers)) || super.charTyped(chr, modifiers);
    }
}
