package com.kitkatvisuals.gui;

import com.kitkatvisuals.module.EventHudModule;
import com.kitkatvisuals.util.Spring;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Фильтр ивентов для EventHud: список всех известных ивентов с галочками. Отмеченные показываются в списке, на плитках
 * поиска анархии и в метках, снятые скрываются. Новые ивенты по умолчанию показываются. Нажатие на строку переключает её.
 */
public final class EventFilterScreen extends Screen {
    private static final int ROW_H = 18;
    private static final int GAP = 3;
    private static final int BTN_H = 18;

    private final Screen parent;
    private final EventHudModule module;
    private final Spring appear = new Spring(0f, 170f, 20f);
    private final Map<String, Spring> hover = new HashMap<>();
    private List<String> names = List.of();
    private float scroll, scrollTarget;
    private int px, py, pw, ph, lx, ly, lw, lh, btnY, btnW;

    public EventFilterScreen(Screen parent, EventHudModule module) {
        super(Text.literal("Event filter"));
        this.parent = parent;
        this.module = module;
        appear.target = 1f;
    }

    @Override
    protected void init() {
        pw = MathHelper.clamp(width - 24, 200, 260);
        ph = MathHelper.clamp(height - 16, 120, 300);
        px = (width - pw) / 2;
        py = Math.max(6, (height - ph) / 2);
        btnY = py + ph - 12 - BTN_H;
        btnW = (pw - 24 - 2 * GAP) / 3;
        lx = px + 12;
        ly = py + 36;
        lw = pw - 24;
        lh = Math.max(ROW_H, btnY - 6 - ly);
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    private float maxScroll() {
        return Math.max(0f, names.size() * (ROW_H + GAP) - GAP - lh);
    }

    private static String fit(String s, int max) {
        if (Hud.w(s) <= max) return s;
        String t = s;
        while (t.length() > 1 && Hud.w(t + "…") > max) t = t.substring(0, t.length() - 1);
        return t + "…";
    }

    // ---------------- отрисовка ----------------

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        renderBackground(ctx, mx, my, delta);
        names = module.knownNames();
        scrollTarget = MathHelper.clamp(scrollTarget, 0f, maxScroll());
        scroll += (scrollTarget - scroll) * 0.35f;
        float a = appear.update();
        float s = 0.92f + 0.08f * MathHelper.clamp(a, 0f, 1.1f);
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(px + pw / 2f, py + ph / 2f, 0f);
        m.scale(s, s, 1f);
        m.translate(-(px + pw / 2f), -(py + ph / 2f), 0f);

        Ui.rect(ctx, px, py, pw, ph, 8, Ui.BG);
        Ui.outline(ctx, px, py, pw, ph, 8, Ui.OUTLINE);
        Hud.text(ctx, "Фильтр ивентов", px + 12, py + 8, Ui.TEXT);
        int shown = 0;
        for (String n : names) if (!module.isHidden(n)) shown++;
        Hud.textRight(ctx, shown + " / " + names.size(), px + pw - 12, py + 8, Ui.ACCENT);
        Hud.text(ctx, "Нажмите на ивент, чтобы скрыть или показать", px + 12, py + 22, Ui.TEXT_DIM);

        if (names.isEmpty()) {
            Hud.text(ctx, "Список пуст: ждём данные от API", lx, ly + 4, Ui.TEXT_DIM);
        }

        ctx.enableScissor(lx, ly, lx + lw, ly + lh);
        for (int i = 0; i < names.size(); i++) {
            int y = Math.round(ly + i * (ROW_H + GAP) - scroll);
            if (y + ROW_H < ly || y > ly + lh) continue;
            String name = names.get(i);
            boolean on = !module.isHidden(name);
            boolean hov = mx >= lx && mx < lx + lw && my >= Math.max(y, ly) && my < Math.min(y + ROW_H, ly + lh);
            Spring hv = hover.computeIfAbsent(name, k -> new Spring(0f, 220f, 24f));
            hv.target = hov ? 1f : 0f;
            hv.update();

            Ui.rect(ctx, lx, y, lw, ROW_H, 5, hov ? Ui.FIELD_HOVER : Ui.FIELD);
            Ui.outline(ctx, lx, y, lw, ROW_H, 5, on ? Ui.ACCENT_LINE : Ui.OUTLINE);

            int bx = lx + 7, by = y + (ROW_H - 10) / 2;
            Ui.outline(ctx, bx, by, 10, 10, 2, on ? Ui.ACCENT : Ui.OUTLINE);
            if (on) ctx.fill(bx + 3, by + 3, bx + 7, by + 7, Ui.ACCENT);

            int active = module.activeCount(name);
            String right = active > 0 ? "×" + active : "";
            int rw = right.isEmpty() ? 0 : Hud.w(right) + 6;
            if (active > 0) {
                int rc = EventHudModule.rankColor(EventHudModule.rarityRank(module.rareOf(name)));
                Hud.textRight(ctx, right, lx + lw - 7, y + (ROW_H - 8) / 2, on ? rc : 0x66FFFFFF);
            }
            Hud.text(ctx, fit(name, lw - 28 - rw), lx + 23, y + (ROW_H - 8) / 2, on ? Ui.TEXT : Ui.TEXT_DIM);
        }
        ctx.disableScissor();

        button(ctx, mx, my, px + 12, btnY, "Все");
        button(ctx, mx, my, px + 12 + btnW + GAP, btnY, "Ничего");
        button(ctx, mx, my, px + 12 + 2 * (btnW + GAP), btnY, "Готово");
        m.pop();
    }

    private void button(DrawContext ctx, int mx, int my, int x, int y, String label) {
        boolean hov = mx >= x && mx < x + btnW && my >= y && my < y + BTN_H;
        Ui.rect(ctx, x, y, btnW, BTN_H, 6, hov ? Ui.FIELD_HOVER : Ui.FIELD);
        Ui.outline(ctx, x, y, btnW, BTN_H, 6, hov ? Ui.ACCENT_LINE : Ui.OUTLINE);
        Hud.text(ctx, label, x + (btnW - Hud.w(label)) / 2, y + (BTN_H - 8) / 2, Ui.TEXT);
    }

    // ---------------- ввод ----------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);
        if (my >= btnY && my < btnY + BTN_H) {
            if (mx >= px + 12 && mx < px + 12 + btnW) {
                module.showAllEvents();
                return true;
            }
            if (mx >= px + 12 + btnW + GAP && mx < px + 12 + 2 * btnW + GAP) {
                module.hideAllEvents();
                return true;
            }
            if (mx >= px + 12 + 2 * (btnW + GAP) && mx < px + 12 + 3 * btnW + 2 * GAP) {
                close();
                return true;
            }
        }
        if (mx >= lx && mx < lx + lw && my >= ly && my < ly + lh) {
            double rel = my - ly + scroll;
            int i = (int) (rel / (ROW_H + GAP));
            double inRow = rel - i * (ROW_H + GAP);
            if (i >= 0 && i < names.size() && inRow < ROW_H) {
                module.toggleEvent(names.get(i));
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        scrollTarget = MathHelper.clamp(scrollTarget - (float) vertical * ROW_H * 2f, 0f, maxScroll());
        return true;
    }
}
