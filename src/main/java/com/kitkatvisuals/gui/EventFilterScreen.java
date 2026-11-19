package com.kitkatvisuals.gui;

import com.kitkatvisuals.module.EventHudModule;
import com.kitkatvisuals.util.Spring;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import com.kitkatvisuals.util.Rarity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Фильтр ивентов для EventHud: список всех известных ивентов с галочками. Отмеченные показываются в списке, на плитках
 * поиска анархии и в метках, снятые скрываются. Новые ивенты по умолчанию показываются. Нажатие на строку переключает её.
 * Если у ивента бывает больше одной редкости, справа появляется стрелка: она раскрывает список его редкостей, и каждую
 * можно скрыть отдельно (например, показывать Таинственный корабль, но только Роскошный).
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
    /** Строки списка: ивент (rare == null) или одна из его редкостей. Пересобирается каждый кадр. */
    private List<Row> items = List.of();
    private final Set<String> expanded = new HashSet<>();

    private record Row(String name, String rare, boolean hasRares) { }

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
        return Math.max(0f, items.size() * (ROW_H + GAP) - GAP - lh);
    }

    private void rebuild() {
        names = module.knownNames();
        List<Row> out = new ArrayList<>(names.size() + 8);
        for (String n : names) {
            List<String> rares = module.knownRaresOf(n);
            boolean has = rares.size() > 1; // с одной редкостью («обычный») раскрывать нечего
            out.add(new Row(n, null, has));
            if (has && expanded.contains(n)) for (String r : rares) out.add(new Row(n, r, false));
        }
        items = out;
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
        rebuild();
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
        Hud.text(ctx, fit("Нажмите на ивент: скрыть или показать. > : редкости", pw - 24), px + 12, py + 22, Ui.TEXT_DIM);

        if (names.isEmpty()) {
            Hud.text(ctx, "Список пуст: ждём данные от API", lx, ly + 4, Ui.TEXT_DIM);
        }

        ctx.enableScissor(lx, ly, lx + lw, ly + lh);
        for (int i = 0; i < items.size(); i++) {
            int y = Math.round(ly + i * (ROW_H + GAP) - scroll);
            if (y + ROW_H < ly || y > ly + lh) continue;
            Row row = items.get(i);
            String name = row.name();
            boolean isRare = row.rare() != null;
            int ind = isRare ? 14 : 0; // у строк редкостей отступ
            boolean evOn = !module.isHidden(name);
            boolean on = isRare ? !module.isRareHidden(name, row.rare()) : evOn;
            boolean hov = mx >= lx && mx < lx + lw && my >= Math.max(y, ly) && my < Math.min(y + ROW_H, ly + lh);
            Spring hv = hover.computeIfAbsent(name + "#" + row.rare(), k -> new Spring(0f, 220f, 24f));
            hv.target = hov ? 1f : 0f;
            hv.update();

            Ui.rect(ctx, lx + ind, y, lw - ind, ROW_H, 5, hov ? Ui.FIELD_HOVER : Ui.FIELD);
            Ui.outline(ctx, lx + ind, y, lw - ind, ROW_H, 5, on ? Ui.ACCENT_LINE : Ui.OUTLINE);

            int bx = lx + ind + 7, by = y + (ROW_H - 10) / 2;
            Ui.outline(ctx, bx, by, 10, 10, 2, on ? Ui.ACCENT : Ui.OUTLINE);
            if (on) ctx.fill(bx + 3, by + 3, bx + 7, by + 7, Ui.ACCENT);

            int rightEdge = lx + lw - 7;
            int textY = y + (ROW_H - 8) / 2;
            if (isRare) {
                int active = module.activeCount(name, row.rare());
                int rc = Rarity.color(Rarity.rank(row.rare()));
                int used = 0;
                if (active > 0) {
                    String right = "×" + active;
                    Hud.textRight(ctx, right, rightEdge, textY, on && evOn ? rc : 0x66FFFFFF);
                    used = Hud.w(right) + 6;
                }
                int tc = on && evOn ? rc : Ui.TEXT_DIM;
                Hud.text(ctx, fit(Rarity.label(row.rare()), lw - ind - 28 - used), lx + ind + 23, textY, tc);
            } else {
                if (row.hasRares()) { // стрелка раскрытия и «сколько редкостей показано»
                    boolean open = expanded.contains(name);
                    Hud.textRight(ctx, open ? "v" : ">", rightEdge, textY, hov && mx >= lx + lw - 24 ? Ui.ACCENT : Ui.TEXT_DIM);
                    rightEdge -= Hud.w(">") + 8;
                    int total = module.knownRaresOf(name).size();
                    int hiddenN = module.hiddenRareCount(name);
                    if (hiddenN > 0) {
                        String frac = (total - hiddenN) + "/" + total;
                        Hud.textRight(ctx, frac, rightEdge, textY, evOn ? Ui.ACCENT : 0x66FFFFFF);
                        rightEdge -= Hud.w(frac) + 6;
                    }
                }
                int active = module.activeCount(name);
                if (active > 0) {
                    String right = "×" + active;
                    int rc = EventHudModule.rankColor(EventHudModule.rarityRank(module.rareOf(name)));
                    Hud.textRight(ctx, right, rightEdge, textY, evOn ? rc : 0x66FFFFFF);
                    rightEdge -= Hud.w(right) + 6;
                }
                Hud.text(ctx, fit(name, rightEdge - (lx + 23) - 4), lx + 23, textY, evOn ? Ui.TEXT : Ui.TEXT_DIM);
            }
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
            if (i >= 0 && i < items.size() && inRow < ROW_H) {
                Row row = items.get(i);
                if (row.rare() != null) {
                    module.toggleRare(row.name(), row.rare());
                } else if (row.hasRares() && mx >= lx + lw - 24) {
                    if (!expanded.remove(row.name())) expanded.add(row.name());
                } else {
                    module.toggleEvent(row.name());
                }
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
