package com.kitkatvisuals.gui;

import com.kitkatvisuals.module.NotesModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Редактор заметок: список с галочками, поле ввода (Enter добавляет), кнопка вставки своих координат.
 * ЛКМ по заметке отмечает её сделанной, ПКМ переносит текст в поле для правки, крестик удаляет.
 */
public final class NotesScreen extends Screen {
    private static final int VIS = 8;
    private static final int ROW = 16;
    private static final int GAP = 4;
    private static final int BTN_H = 18;

    private final Screen parent;
    private final NotesModule module;
    private TextFieldWidget field;
    private int scroll;

    private int px, py, pw, ph, listY, fieldY, btnY, btnW;

    public NotesScreen(Screen parent, NotesModule module) {
        super(Text.literal("Notes"));
        this.parent = parent;
        this.module = module;
    }

    @Override
    protected void init() {
        pw = Math.min(300, width - 16);
        int listH = VIS * ROW;
        ph = 24 + listH + 6 + 20 + 6 + BTN_H + 12;
        px = (width - pw) / 2;
        py = Math.max(6, (height - ph) / 2);
        listY = py + 24;
        fieldY = listY + listH + 6;
        btnY = fieldY + 20 + 6;
        btnW = (pw - 24 - 2 * GAP) / 3;

        field = new TextFieldWidget(textRenderer, px + 12 + 8, fieldY + 6, pw - 24 - 16, 12, Text.empty());
        field.setDrawsBackground(false);
        field.setMaxLength(NotesModule.MAX_LEN);
        field.setFocused(true);
        setFocused(field);
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    private int maxScroll() {
        return Math.max(0, module.notes().size() - VIS);
    }

    // ---------------- действия ----------------

    private void addFromField() {
        if (module.addNote(field.getText())) {
            field.setText("");
            scroll = maxScroll(); // показываем новую заметку внизу
        }
    }

    private void insertCoords() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        String dim = mc.world.getRegistryKey() == World.NETHER ? "Незер"
                : mc.world.getRegistryKey() == World.END ? "Энд"
                : mc.world.getRegistryKey() == World.OVERWORLD ? "Мир" : "";
        String s = "XYZ: " + mc.player.getBlockX() + " " + mc.player.getBlockY() + " " + mc.player.getBlockZ()
                + (dim.isEmpty() ? "" : " (" + dim + ")");
        String cur = field.getText();
        if (!cur.isEmpty() && !cur.endsWith(" ")) field.write(" ");
        field.write(s);
        field.setFocused(true);
    }

    // ---------------- отрисовка ----------------

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        renderBackground(ctx, mx, my, delta);
        List<NotesModule.Note> notes = module.notes();
        scroll = MathHelper.clamp(scroll, 0, maxScroll());

        Ui.rect(ctx, px, py, pw, ph, 8, Ui.BG);
        Ui.outline(ctx, px, py, pw, ph, 8, Ui.OUTLINE);
        Hud.text(ctx, "Заметки", px + 12, py + 8, Ui.TEXT);
        Hud.textRight(ctx, module.doneCount() + "/" + notes.size(), px + pw - 12, py + 8, Ui.ACCENT);

        // список
        if (notes.isEmpty()) {
            String hint = "Пока пусто. Напишите ниже и нажмите Enter";
            Hud.text(ctx, hint, px + (pw - Hud.w(hint)) / 2, listY + VIS * ROW / 2 - 4, Ui.TEXT_DIM);
        }
        int lx = px + 12;
        int lw = pw - 24;
        for (int r = 0; r < VIS; r++) {
            int i = scroll + r;
            if (i >= notes.size()) break;
            NotesModule.Note n = notes.get(i);
            int y = listY + r * ROW;
            boolean hov = mx >= lx && mx < lx + lw && my >= y && my < y + ROW - 2;
            Ui.rect(ctx, lx, y, lw, ROW - 2, 4, hov ? Ui.FIELD_HOVER : Ui.FIELD);
            // галочка
            Ui.outline(ctx, lx + 5, y + 2, 10, 10, 3, n.done ? Ui.ACCENT : Ui.OUTLINE);
            if (n.done) Ui.rect(ctx, lx + 7, y + 4, 6, 6, 2, Ui.ACCENT);
            String t = Ui.tr().trimToWidth(n.text, lw - 20 - 18);
            int col = n.done ? Ui.TEXT_DIM : Ui.TEXT;
            Hud.text(ctx, t, lx + 20, y + 3, col);
            if (n.done) ctx.fill(lx + 20, y + 7, lx + 20 + Hud.w(t), y + 8, Ui.TEXT_DIM);
            // крестик
            boolean xh = mx >= lx + lw - 14 && mx < lx + lw - 2 && my >= y && my < y + ROW - 2;
            Hud.text(ctx, "x", lx + lw - 11, y + 3, xh ? 0xFFFF6B6B : Ui.TEXT_DIM);
        }
        if (notes.size() > VIS) { // полоса прокрутки
            int trackH = VIS * ROW - 2;
            int thumbH = Math.max(12, trackH * VIS / notes.size());
            int ty = listY + (trackH - thumbH) * scroll / Math.max(1, maxScroll());
            ctx.fill(px + pw - 8, ty, px + pw - 6, ty + thumbH, Ui.ACCENT_LINE);
        }

        // поле ввода
        int fx = px + 12;
        int fw = pw - 24;
        Ui.rect(ctx, fx, fieldY, fw, 20, 5, Ui.FIELD);
        Ui.outline(ctx, fx, fieldY, fw, 20, 5, field.isFocused() ? Ui.ACCENT_LINE : Ui.OUTLINE);
        if (field.getText().isEmpty()) Hud.text(ctx, "Новая заметка...", fx + 8, fieldY + 6, 0x77BEC3CD);
        field.render(ctx, mx, my, delta);

        // кнопки
        button(ctx, mx, my, px + 12, "Координаты");
        button(ctx, mx, my, px + 12 + btnW + GAP, "Добавить");
        button(ctx, mx, my, px + 12 + 2 * (btnW + GAP), "Убрать готовые");
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
        int fx = px + 12;
        int fw = pw - 24;
        if (mx >= fx && mx < fx + fw && my >= fieldY && my < fieldY + 20) {
            field.setFocused(true);
            field.mouseClicked(mx, my, button);
            return true;
        }
        if (button == 0 && my >= btnY && my < btnY + BTN_H) {
            if (mx >= px + 12 && mx < px + 12 + btnW) { insertCoords(); return true; }
            if (mx >= px + 12 + btnW + GAP && mx < px + 12 + 2 * btnW + GAP) { addFromField(); return true; }
            if (mx >= px + 12 + 2 * (btnW + GAP) && mx < px + 12 + 3 * btnW + 2 * GAP) { module.clearDone(); return true; }
        }
        int lx = px + 12;
        int lw = pw - 24;
        if (mx >= lx && mx < lx + lw && my >= listY && my < listY + VIS * ROW) {
            int i = scroll + (int) ((my - listY) / ROW);
            List<NotesModule.Note> notes = module.notes();
            if (i >= 0 && i < notes.size()) {
                if (button == 1) { // ПКМ: править
                    field.setText(notes.get(i).text);
                    module.remove(i);
                    field.setFocused(true);
                } else if (button == 0) {
                    if (mx >= lx + lw - 14) module.remove(i);
                    else module.toggle(i);
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (my >= listY && my < listY + VIS * ROW) {
            scroll = MathHelper.clamp(scroll - (int) Math.signum(vertical), 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) return super.keyPressed(keyCode, scanCode, modifiers);
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            addFromField();
            return true;
        }
        if (field.keyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        return field.charTyped(chr, modifiers) || super.charTyped(chr, modifiers);
    }
}
