package com.swinganimations.gui;

import com.swinganimations.fun.DebuffTrial;
import com.swinganimations.fun.FunGame;
import com.swinganimations.fun.FunStats;
import com.swinganimations.fun.GuessGame;
import com.swinganimations.fun.MagicBall;
import com.swinganimations.render.Batch;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Содержимое вкладки Fun: внутри своя маленькая навигация по страницам (Рулетка, Шар, Испытание, Угадай,
 * Статы), всё рисуется теми же стеклянными примитивами, что и остальной ClickGUI. Никакого чата —
 * весь вывод только в панели. Логика каждой игры живёт в своём классе пакета fun, здесь только отрисовка.
 */
public final class FunPanel {
    private enum Page { ROULETTE, BALL, DEBUFF, GUESS, STATS }

    private static Page page = Page.ROULETTE;

    private static final int C_RED = 0xFFE23B4E;
    private static final int C_GREEN = 0xFF2FD07A;
    private static final int TABH = 20;
    private static final int[] STEPS = {-10, -1, 1, 10};

    private record Hit(int x, int y, int w, int h, Runnable action) { }

    private final List<Hit> hits = new ArrayList<>();

    private static Text tr(String key, String fallback, Object... args) {
        return Text.translatableWithFallback("swinganimations.fun." + key, fallback, args);
    }

    private static boolean in(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ---------------- маршрутизация ----------------

    public void render(DrawContext ctx, int x, int y, int w, int h, int mx, int my, int rgb) {
        hits.clear();
        int cy = drawSubTabs(ctx, x, y, w, mx, my, rgb);
        int ch = h - (cy - y);
        switch (page) {
            case ROULETTE -> renderRoulette(ctx, x, cy, w, ch, mx, my, rgb);
            case BALL -> renderBall(ctx, x, cy, w, ch, mx, my, rgb);
            case DEBUFF -> renderDebuff(ctx, x, cy, w, ch, mx, my, rgb);
            case GUESS -> renderGuess(ctx, x, cy, w, ch, mx, my, rgb);
            case STATS -> renderStats(ctx, x, cy, w, ch);
        }
    }

    public boolean click(double mx, double my) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (mx >= hit.x() && mx < hit.x() + hit.w() && my >= hit.y() && my < hit.y() + hit.h()) {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc != null) mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 0.6f));
                hit.action().run();
                return true;
            }
        }
        return false;
    }

    private int drawSubTabs(DrawContext ctx, int x, int y, int w, int mx, int my, int rgb) {
        Page[] pages = Page.values();
        String[] keys = {"tab.roulette", "tab.ball", "tab.debuff", "tab.guess", "tab.stats"};
        String[] fallback = {"Roulette", "8-Ball", "Trial", "Guess", "Stats"};
        int gap = 4;
        int bw = (w - gap * (pages.length - 1)) / pages.length;
        for (int i = 0; i < pages.length; i++) {
            int bx = x + i * (bw + gap);
            boolean sel = pages[i] == page;
            boolean hov = !sel && in(mx, my, bx, y, bw, TABH);
            Ui.rect(ctx, bx, y, bw, TABH, 6, sel ? Batch.argb(rgb, 0.34f) : Batch.argb(0xFFFFFF, hov ? 0.12f : 0.06f));
            Text label = tr(keys[i], fallback[i]);
            Hud.text(ctx, label, bx + (bw - Hud.w(label)) / 2, y + (TABH - 8) / 2, sel ? Ui.TEXT : Ui.TEXT_DIM);
            Page p = pages[i];
            hits.add(new Hit(bx, y, bw, TABH, () -> page = p));
        }
        return y + TABH + 6;
    }

    // ---------------- Рулетка ----------------

    private void renderRoulette(DrawContext ctx, int x, int y, int w, int h, int mx, int my, int rgb) {
        int info = 20, strip = 40, hist = 12, ctrl = 20, btn = 24, stat = 20;
        int total = info + 6 + strip + 6 + hist + 6 + ctrl + 6 + btn + 5 + stat;
        int top = y + Math.max(0, (h - total) / 3);

        drawChanceInfo(ctx, x, top, w);
        int sy = top + info + 6;
        drawStrip(ctx, x, sy, w);
        int hy = sy + strip + 6;
        drawHistory(ctx, x, hy);
        int cy = hy + hist + 6;
        drawChanceControls(ctx, x, cy, w, mx, my);
        int ty = cy + ctrl + 6;
        drawDeposit(ctx, x, ty, w, mx, my, rgb, btn);
        drawStatusLine(ctx, x, ty + btn + 5, w, FunGame.status,
                tr("hint", "Green = you live. Red = /%s.", FunGame.COMMAND).getString(), FunGame.statusColor);
    }

    private void drawChanceInfo(DrawContext ctx, int x, int y, int w) {
        Text chance = tr("chance", "Death chance: %s%%", FunGame.deathChance);
        int bw = Hud.w(chance) + 16;
        Ui.rect(ctx, x, y, bw, 20, 6, Batch.argb(C_RED & 0xFFFFFF, 0.18f));
        Hud.text(ctx, chance, x + 8, y + 6, C_RED);

        Text esc = tr("escalation", "Escalation");
        int ew = Hud.w(esc) + 28;
        int ex = x + w - ew;
        boolean on = FunGame.escalation;
        Ui.rect(ctx, ex, y, ew, 20, 6, on ? Batch.argb(0xFF8BB9FF, 0.30f) : Batch.argb(0xFFFFFF, 0.06f));
        Hud.text(ctx, esc, ex + 8, y + 6, on ? Ui.ACCENT : Ui.TEXT_DIM);
        int swW = 14, swH = 9;
        int sx = ex + ew - 8 - swW, sy2 = y + (20 - swH) / 2;
        Ui.rect(ctx, sx, sy2, swW, swH, 4, on ? Ui.ACCENT : Ui.SWITCH_OFF);
        Ui.rect(ctx, on ? sx + swW - swH + 1 : sx + 1, sy2 + 1, swH - 2, swH - 2, 3, 0xFFFFFFFF);
        hits.add(new Hit(ex, y, ew, 20, FunGame::toggleEscalation));
    }

    private void drawStrip(DrawContext ctx, int x, int y, int w) {
        Ui.rect(ctx, x, y, w, 40, 8, 0x99000000);
        Ui.outline(ctx, x, y, w, 40, 8, Ui.OUTLINE);

        int vx = x + 3, vw = w - 6;
        int cx = x + w / 2;
        float p = FunGame.pointer();
        FunGame.Color[] strip = FunGame.strip;
        int first = Math.max(0, (int) Math.floor((p - (cx - vx)) / FunGame.PITCH) - 1);
        int last = Math.min(strip.length - 1, (int) Math.ceil((p + (vx + vw - cx)) / FunGame.PITCH) + 1);
        int under = (int) Math.floor(p / FunGame.PITCH);

        ctx.enableScissor(vx, y + 2, vx + vw, y + 38);
        for (int k = first; k <= last; k++) {
            int tx = Math.round(cx + k * FunGame.PITCH + (FunGame.PITCH - FunGame.TILE) / 2f - p);
            int color = strip[k] == FunGame.Color.DEAD ? C_RED : C_GREEN;
            Ui.rect(ctx, tx, y + 4, FunGame.TILE, 32, 5, color);
            if (!FunGame.spinning && k == under) Ui.outline(ctx, tx - 1, y + 3, FunGame.TILE + 2, 34, 5, 0xCCFFFFFF);
        }
        for (int i = 0; i < 6; i++) { // затемнение по краям
            int a = 0x78 * (5 - i) / 5;
            ctx.fill(vx + i * 4, y + 2, vx + i * 4 + 4, y + 38, a << 24);
            ctx.fill(vx + vw - i * 4 - 4, y + 2, vx + vw - i * 4, y + 38, a << 24);
        }
        ctx.fill(cx - 1, y + 1, cx + 1, y + 39, 0xFFFFFFFF); // указатель
        ctx.fill(cx - 4, y + 1, cx + 4, y + 3, 0xFFFFFFFF);
        ctx.fill(cx - 4, y + 37, cx + 4, y + 39, 0xFFFFFFFF);
        ctx.disableScissor();
    }

    private void drawHistory(DrawContext ctx, int x, int y) {
        Text label = tr("history", "History");
        Hud.text(ctx, label, x + 2, y + 2, Ui.TEXT_DIM);
        int hx = x + Hud.w(label) + 10;
        if (FunGame.history.isEmpty()) {
            Hud.text(ctx, "-", hx, y + 2, Ui.TEXT_DIM);
            return;
        }
        for (FunGame.Color c : FunGame.history) {
            Ui.rect(ctx, hx, y + 2, 8, 8, 2, c == FunGame.Color.DEAD ? C_RED : C_GREEN);
            hx += 11;
        }
    }

    private void drawChanceControls(DrawContext ctx, int x, int y, int w, int mx, int my) {
        int n = STEPS.length;
        int gap = 4;
        int bw = (w - gap * (n - 1)) / n;
        boolean locked = FunGame.spinning || FunGame.escalation;
        for (int i = 0; i < n; i++) {
            int bx = x + i * (bw + gap);
            boolean hov = !locked && in(mx, my, bx, y, bw, 20);
            float a = hov ? 0.18f : 0.08f;
            if (locked) a *= 0.5f;
            Ui.rect(ctx, bx, y, bw, 20, 6, Batch.argb(0xFFFFFF, a));
            String label = (STEPS[i] > 0 ? "+" : "") + STEPS[i] + "%";
            Hud.text(ctx, label, bx + (bw - Hud.w(label)) / 2, y + 6, locked ? Ui.TEXT_DIM : Ui.TEXT);
            final int d = STEPS[i];
            if (!locked) hits.add(new Hit(bx, y, bw, 20, () -> FunGame.addChance(d)));
        }
    }

    private void drawDeposit(DrawContext ctx, int x, int y, int w, int mx, int my, int rgb, int btn) {
        boolean locked = FunGame.spinning;
        boolean hov = !locked && in(mx, my, x, y, w, btn);
        Text label;
        if (locked) {
            Ui.rect(ctx, x, y, w, btn, 7, Batch.argb(0xFFFFFF, 0.06f));
            label = tr("spinning", "Spinning...");
        } else {
            Ui.rect(ctx, x, y, w, btn, 7, Batch.argb(rgb, hov ? 0.56f : 0.38f));
            Ui.outline(ctx, x, y, w, btn, 7, Batch.argb(rgb, hov ? 0.9f : 0.5f));
            label = tr("deposit", "DEPOSIT YOUR LIFE");
        }
        Hud.text(ctx, label, x + (w - Hud.w(label)) / 2, y + (btn - 8) / 2, locked ? Ui.TEXT_DIM : Ui.TEXT);
        if (!locked) hits.add(new Hit(x, y, w, btn, FunGame::spin));
    }

    // ---------------- Магический шар ----------------

    private static String ballText = MagicBall.roll();

    private void renderBall(DrawContext ctx, int x, int y, int w, int h, int mx, int my, int rgb) {
        int panelH = 60, btn = 24;
        int top = y + Math.max(0, (h - (panelH + 6 + btn)) / 3);
        Ui.rect(ctx, x, top, w, panelH, 10, 0x99000000);
        Ui.outline(ctx, x, top, w, panelH, 10, Ui.OUTLINE);
        List<OrderedText> lines = Ui.tr().wrapLines(Text.literal(ballText), w - 20);
        int ly = top + Math.max(6, (panelH - lines.size() * 10) / 2);
        for (OrderedText line : lines) {
            int lw = Ui.tr().getWidth(line);
            ctx.drawText(Ui.tr(), line, x + (w - lw) / 2, ly, Ui.ACCENT, false);
            ly += 10;
        }

        int by = top + panelH + 6;
        boolean hov = in(mx, my, x, y, w, btn) || in(mx, my, x, by, w, btn);
        Ui.rect(ctx, x, by, w, btn, 7, Batch.argb(rgb, hov ? 0.56f : 0.38f));
        Ui.outline(ctx, x, by, w, btn, 7, Batch.argb(rgb, hov ? 0.9f : 0.5f));
        Text label = tr("ball.roll", "Ask the ball");
        Hud.text(ctx, label, x + (w - Hud.w(label)) / 2, by + (btn - 8) / 2, Ui.TEXT);
        hits.add(new Hit(x, by, w, btn, () -> ballText = MagicBall.roll()));
    }

    // ---------------- Испытание ----------------

    private void renderDebuff(DrawContext ctx, int x, int y, int w, int h, int mx, int my, int rgb) {
        int panelH = 44, btn = 24;
        int top = y + Math.max(0, (h - (panelH + 6 + btn + 6 + 10)) / 3);
        boolean active = DebuffTrial.active;

        Ui.rect(ctx, x, top, w, panelH, 10, 0x99000000);
        Ui.outline(ctx, x, top, w, panelH, 10, Ui.OUTLINE);
        Text state = active
                ? tr("debuff.active", "Trial running: %ss left", DebuffTrial.remainingSeconds())
                : tr("debuff.idle", "No trial running");
        Hud.text(ctx, state, x + 10, top + (active ? 8 : (panelH - 8) / 2), active ? Ui.ACCENT : Ui.TEXT_DIM);
        if (active) {
            int barY = top + 24, barW = w - 20;
            Ui.rect(ctx, x + 10, barY, barW, 6, 3, Batch.argb(0xFFFFFF, 0.08f));
            int fill = Math.round(barW * DebuffTrial.remaining());
            if (fill > 0) Ui.rect(ctx, x + 10, barY, fill, 6, 3, Ui.ACCENT);
        }

        int by = top + panelH + 6;
        boolean hov = in(mx, my, x, by, w, btn);
        if (active) {
            Ui.rect(ctx, x, by, w, btn, 7, Batch.argb(C_RED & 0xFFFFFF, hov ? 0.5f : 0.32f));
            Text label = tr("debuff.stop", "Stop early");
            Hud.text(ctx, label, x + (w - Hud.w(label)) / 2, by + (btn - 8) / 2, Ui.TEXT);
            hits.add(new Hit(x, by, w, btn, DebuffTrial::stop));
        } else {
            Ui.rect(ctx, x, by, w, btn, 7, Batch.argb(rgb, hov ? 0.56f : 0.38f));
            Ui.outline(ctx, x, by, w, btn, 7, Batch.argb(rgb, hov ? 0.9f : 0.5f));
            Text label = tr("debuff.start", "Start trial (30s)");
            Hud.text(ctx, label, x + (w - Hud.w(label)) / 2, by + (btn - 8) / 2, Ui.TEXT);
            hits.add(new Hit(x, by, w, btn, DebuffTrial::start));
        }
        Text hint = tr("debuff.hint", "Random harmless screen effect for 30s. Only on your own client.");
        List<OrderedText> lines = Ui.tr().wrapLines(Hud.t(hint), w);
        int hy = by + btn + 6;
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            ctx.drawText(Ui.tr(), lines.get(i), x + 2, hy + i * 10, Ui.TEXT_DIM, false);
        }
    }

    // ---------------- Угадай число ----------------

    private void renderGuess(DrawContext ctx, int x, int y, int w, int h, int mx, int my, int rgb) {
        int gridH = 44, stat = 10, btn = 20;
        int top = y + Math.max(0, (h - (stat + 6 + gridH + 6 + btn)) / 3);

        String hintText = guessHint();
        Hud.text(ctx, hintText, x + 2, top, GuessGame.won ? C_GREEN : Ui.TEXT_DIM);

        int gy = top + stat + 6;
        int cols = 5, rows = 2;
        int gap = 4;
        int cw = (w - gap * (cols - 1)) / cols;
        int ch2 = (gridH - gap) / rows;
        for (int i = 0; i < 10; i++) {
            int n = i + 1;
            int col = i % cols, row = i / cols;
            int bx = x + col * (cw + gap);
            int by = gy + row * (ch2 + gap);
            boolean selected = n == GuessGame.lastGuess;
            boolean hov = !GuessGame.won && in(mx, my, bx, by, cw, ch2);
            float a = selected ? 0.4f : (hov ? 0.16f : 0.08f);
            Ui.rect(ctx, bx, by, cw, ch2, 6, Batch.argb(selected ? rgb : 0xFFFFFF, a));
            String label = String.valueOf(n);
            Hud.text(ctx, label, bx + (cw - Hud.w(label)) / 2, by + (ch2 - 8) / 2, Ui.TEXT);
            if (!GuessGame.won) hits.add(new Hit(bx, by, cw, ch2, () -> GuessGame.guess(n)));
        }

        int by2 = gy + gridH + 6;
        boolean hov = in(mx, my, x, by2, w, btn);
        Ui.rect(ctx, x, by2, w, btn, 7, Batch.argb(rgb, hov ? 0.4f : 0.22f));
        Text label = tr("guess.reset", "New number");
        Hud.text(ctx, label, x + (w - Hud.w(label)) / 2, by2 + 6, Ui.TEXT);
        hits.add(new Hit(x, by2, w, btn, GuessGame::reset));
    }

    private static String guessHint() {
        if (GuessGame.won) {
            return tr("guess.won", "Guessed in %s tries!", GuessGame.attempts).getString();
        }
        if (GuessGame.lastGuess < 0) {
            return tr("guess.start", "Pick a number from 1 to 10").getString();
        }
        return GuessGame.lastDirection > 0
                ? tr("guess.higher", "Higher! (try %s)", GuessGame.attempts).getString()
                : tr("guess.lower", "Lower! (try %s)", GuessGame.attempts).getString();
    }

    // ---------------- Статистика ----------------

    private void renderStats(DrawContext ctx, int x, int y, int w, int h) {
        record Row(String key, String fallback, int value) { }
        Row[] rows = {
                new Row("stats.spins", "Spins: %s", FunStats.spins),
                new Row("stats.deaths", "Deaths: %s", FunStats.deaths),
                new Row("stats.survivals", "Survivals: %s", FunStats.survivals),
                new Row("stats.streak", "Best streak: %s", FunStats.bestStreak),
                new Row("stats.debuff", "Trials run: %s", FunStats.debuffTrials),
                new Row("stats.guess", "Numbers guessed: %s", FunStats.guessGames),
        };
        int top = y + Math.max(0, (h - rows.length * 18) / 3);
        int ry = top;
        for (Row r : rows) {
            Text t = tr(r.key(), r.fallback(), r.value());
            Ui.rect(ctx, x, ry, w, 16, 6, Batch.argb(0xFFFFFF, 0.05f));
            Hud.text(ctx, t, x + 8, ry + 4, Ui.TEXT);
            ry += 18;
        }
        if (FunStats.guessBestAttempts >= 0) {
            Text best = tr("stats.guess_best", "Best guess: %s tries", FunStats.guessBestAttempts);
            Hud.text(ctx, best, x + 8, ry + 4, Ui.TEXT_DIM);
        }
    }

    private void drawStatusLine(DrawContext ctx, int x, int y, int w, Text status, String fallback, int color) {
        Text t = status != null ? status : Text.literal(fallback);
        int c = status != null ? color : Ui.TEXT_DIM;
        List<OrderedText> lines = Ui.tr().wrapLines(Hud.t(t), w);
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            ctx.drawText(Ui.tr(), lines.get(i), x + 2, y + i * 10, c, false);
        }
    }
}
