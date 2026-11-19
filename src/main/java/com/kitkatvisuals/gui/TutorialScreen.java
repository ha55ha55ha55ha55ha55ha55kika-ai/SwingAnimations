package com.kitkatvisuals.gui;

import com.kitkatvisuals.input.KeyBinds;
import com.kitkatvisuals.module.Module;
import com.kitkatvisuals.module.ModuleManager;
import com.kitkatvisuals.module.ThemeModule;
import com.kitkatvisuals.render.Batch;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Обучение: короткий тур по моду. Сначала как открыть меню и пользоваться функциями, потом список ВСЕХ функций по
 * разделам (название + что делает). Любой шаг можно пропустить: кнопка «Пропустить», Esc, либо переход по разделам
 * по чипам сверху. Тексты лежат в lang (kitkatvisuals.tut.*), описание функции: kitkatvisuals.tut.mod.<id>.
 */
public final class TutorialScreen extends Screen {
    private static final int PAD = 14;
    private static final int BODY_TOP = 64;
    private static final int BODY_BOTTOM = 44;
    private static final String[] SECTIONS = {"intro", "render", "hud", "movement", "player", "animations", "finish"};
    private static final String[] SECTION_FALLBACK = {"Intro", "Visuals", "HUD", "Movement", "Player", "Animations", "Finish"};

    private record Block(Text head, Text body, boolean bullet) { }

    private static final class Step {
        final String section;
        final int page;
        final Text title;
        final List<Block> blocks = new ArrayList<>();

        Step(String section, int page, Text title) {
            this.section = section;
            this.page = page;
            this.title = title;
        }
    }

    private record Chip(int x, int y, int w, int h, String section) { }

    private final Screen parent;
    private final List<Step> steps = new ArrayList<>();
    private final List<Chip> chips = new ArrayList<>();
    private int index;
    private long stepAt = System.currentTimeMillis();
    private float progress;
    private int cx, cy, cw, ch;
    private UiButton backBtn, skipBtn;

    public TutorialScreen(Screen parent) {
        super(Text.translatable("kitkatvisuals.title"));
        this.parent = parent;
    }

    private static Text tr(String key, String fallback, Object... args) {
        return Text.translatableWithFallback("kitkatvisuals." + key, fallback, args);
    }

    // ---------------- построение ----------------

    @Override
    protected void init() {
        String keepSection = steps.isEmpty() ? null : steps.get(Math.min(index, steps.size() - 1)).section;
        int keepPage = steps.isEmpty() ? 0 : steps.get(Math.min(index, steps.size() - 1)).page;

        cw = Math.min(width - 16, 470);
        ch = Math.min(height - 16, 290);
        cx = (width - cw) / 2;
        cy = (height - ch) / 2;
        buildSteps();
        index = 0;
        if (keepSection != null) {
            for (int i = 0; i < steps.size(); i++) {
                if (steps.get(i).section.equals(keepSection) && steps.get(i).page == keepPage) index = i;
            }
        }

        int by = cy + ch - 30;
        skipBtn = addDrawableChild(new UiButton(cx + PAD, by, 100, 20, UiButton.Kind.PLAIN,
                () -> tr("tut.btn.skip", "Skip tutorial"), null, null, this::finish));
        backBtn = addDrawableChild(new UiButton(cx + cw - PAD - 96 - 6 - 72, by, 72, 20, UiButton.Kind.PLAIN,
                () -> tr("tut.btn.back", "Back"), null, null, this::back));
        addDrawableChild(new UiButton(cx + cw - PAD - 96, by, 96, 20, UiButton.Kind.PLAIN,
                () -> index >= steps.size() - 1 ? tr("tut.btn.finish", "Finish") : tr("tut.btn.next", "Next"),
                null, null, this::next));
    }

    private static String catOf(Module m) {
        return switch (m.category) {
            case "render", "hud", "movement", "player" -> m.category;
            default -> "animations";
        };
    }

    private int rowHeight(Block b, int wrapW) {
        int lines = Math.max(1, Ui.tr().wrapLines(b.body(), wrapW).size());
        return (b.head() != null ? 11 : 0) + lines * 10 + 7;
    }

    private void buildSteps() {
        steps.clear();
        int wrapW = cw - PAD * 2 - 10;
        int bodyH = ch - BODY_TOP - BODY_BOTTOM;
        Text openKey = KeyBinds.OPEN != null ? KeyBinds.OPEN.getBoundKeyLocalizedText() : Text.literal("Right Shift");

        Step intro = new Step("intro", 0, tr("tut.intro.title", "Welcome to KitKatVisuals"));
        intro.blocks.add(new Block(null, tr("tut.intro.p1",
                "A client mod packed with visual features: swing animations, HUD, crosshair, minimap, ESP and much more."), false));
        intro.blocks.add(new Block(null, tr("tut.intro.p2",
                "We will show the basics in a couple of minutes. You can skip this at any time: press \"Skip tutorial\" or Esc."), false));
        steps.add(intro);

        Step open = new Step("intro", 1, tr("tut.open.title", "How to open the menu"));
        open.blocks.add(new Block(null, tr("tut.open.p1", "Press %s to open the ClickGUI: all features of the mod live there.", openKey), true));
        open.blocks.add(new Block(null, tr("tut.open.p2",
                "You can change the key in Options > Controls > KitKatVisuals."), true));
        steps.add(open);

        Step use = new Step("intro", 2, tr("tut.use.title", "How to use features"));
        use.blocks.add(new Block(null, tr("tut.use.p1", "Left click a feature to turn it on or off."), true));
        use.blocks.add(new Block(null, tr("tut.use.p2", "Right click it (or the arrow on the right) to open its settings."), true));
        use.blocks.add(new Block(null, tr("tut.use.p3", "The \"Bind\" row in settings assigns a key to the feature (Esc clears it)."), true));
        use.blocks.add(new Block(null, tr("tut.use.p4",
                "Use the search at the top to find features and settings. The Configs tab saves your own setups."), true));
        use.blocks.add(new Block(null, tr("tut.use.p5",
                "HUD windows can be dragged with the mouse while the chat is open; right click puts them back."), true));
        use.blocks.add(new Block(null, tr("tut.use.p6",
                "Next is the list of all features by section. Pick a section above to jump to it or skip it."), true));
        steps.add(use);

        for (int s = 1; s <= 5; s++) {
            String cat = SECTIONS[s];
            Text catTitle = tr("cat." + cat, SECTION_FALLBACK[s]);
            List<Block> rows = new ArrayList<>();
            for (Module m : ModuleManager.MODULES) {
                if (!catOf(m).equals(cat)) continue;
                Text name = Text.translatableWithFallback("kitkatvisuals.module." + m.id, m.fallbackName);
                rows.add(new Block(name, tr("tut.mod." + m.id, ""), false));
            }
            if (rows.isEmpty()) continue;
            List<List<Block>> pages = new ArrayList<>();
            List<Block> cur = new ArrayList<>();
            int used = 0;
            for (Block b : rows) {
                int h = rowHeight(b, wrapW);
                if (!cur.isEmpty() && used + h > bodyH) {
                    pages.add(cur);
                    cur = new ArrayList<>();
                    used = 0;
                }
                cur.add(b);
                used += h;
            }
            if (!cur.isEmpty()) pages.add(cur);
            for (int p = 0; p < pages.size(); p++) {
                Text title = pages.size() > 1 ? tr("tut.cat.page", "%s (%s/%s)", catTitle, p + 1, pages.size()) : catTitle;
                Step st = new Step(cat, p, title);
                st.blocks.addAll(pages.get(p));
                steps.add(st);
            }
        }

        Step fin = new Step("finish", 0, tr("tut.finish.title", "All set!"));
        fin.blocks.add(new Block(null, tr("tut.finish.p1", "Enable the features you like in the ClickGUI (%s).", openKey), true));
        fin.blocks.add(new Block(null, tr("tut.finish.p2",
                "You can open this tutorial again: the \"?\" button at the top right of the ClickGUI, or Settings > General."), true));
        steps.add(fin);
    }

    // ---------------- навигация ----------------

    private void go(int i) {
        i = MathHelper.clamp(i, 0, steps.size() - 1);
        if (i != index) {
            index = i;
            stepAt = System.currentTimeMillis();
        }
    }

    private void next() {
        if (index >= steps.size() - 1) finish();
        else go(index + 1);
    }

    private void back() { go(index - 1); }

    private void jumpTo(String section) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).section.equals(section)) { go(i); return; }
        }
    }

    private void finish() {
        Tutorial.markDone();
        if (client != null) client.setScreen(parent);
    }

    private void click() {
        if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 0.6f));
    }

    // ---------------- отрисовка ----------------

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // фон рисуется в render(): без размытия, чтобы экран не ругался на повторный blur за кадр
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        ThemeModule.sync();
        int rgb = Ui.ACCENT & 0xFFFFFF;
        float open = MathHelper.clamp((System.currentTimeMillis() - openedAt) / 260f, 0f, 1f);
        ctx.fill(0, 0, width, height, Batch.argb(0x000000, 0.62f * open));

        Ui.shadow(ctx, cx, cy, cw, ch, 12);
        Ui.rect(ctx, cx, cy, cw, ch, 12, Batch.argb(Batch.mix(0x160D0D, rgb, 0.07f), 0.96f));
        Ui.outline(ctx, cx, cy, cw, ch, 12, Batch.argb(Batch.mix(0xFFFFFF, rgb, 0.35f), 0.20f));

        if (steps.isEmpty()) {
            super.render(ctx, mx, my, delta);
            return;
        }
        Step st = steps.get(MathHelper.clamp(index, 0, steps.size() - 1));

        // заголовок и счётчик
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(cx + PAD, cy + 12, 0f);
        m.scale(1.25f, 1.25f, 1f);
        Ui.text(ctx, st.title, 0, 0, Ui.TEXT);
        m.pop();
        Ui.textRight(ctx, Text.literal((index + 1) + " / " + steps.size()), cx + cw - PAD, cy + 14, Ui.TEXT_DIM);

        drawChips(ctx, mx, my, st);

        // прогресс
        progress += ((index + 1f) / steps.size() - progress) * 0.25f;
        int barW = cw - PAD * 2;
        Ui.rect(ctx, cx + PAD, cy + 55, barW, 2, 1, Batch.argb(0xFFFFFF, 0.08f));
        Ui.rect(ctx, cx + PAD, cy + 55, Math.max(2, Math.round(barW * progress)), 2, 1, Ui.ACCENT);

        // тело шага (плавное появление)
        float f = MathHelper.clamp((System.currentTimeMillis() - stepAt) / 180f, 0f, 1f);
        int slide = Math.round((1f - f) * 8f);
        int wrapW = cw - PAD * 2 - 10;
        int y = cy + BODY_TOP;
        int x = cx + PAD + slide;
        ctx.enableScissor(cx + 4, cy + BODY_TOP - 2, cx + cw - 4, cy + ch - BODY_BOTTOM + 2);
        for (Block b : st.blocks) {
            int tx = x;
            if (b.head() != null) {
                Ui.text(ctx, b.head(), x, y, Ui.fade(Ui.ACCENT, f));
                y += 11;
            }
            if (b.bullet()) {
                ctx.fill(x, y + 3, x + 3, y + 6, Ui.fade(Ui.ACCENT, f));
                tx = x + 9;
            }
            int color = Ui.fade(b.head() != null ? Ui.TEXT_DIM : Ui.TEXT, f);
            List<OrderedText> lines = Ui.tr().wrapLines(b.body(), b.bullet() ? wrapW - 9 : wrapW);
            for (OrderedText line : lines) {
                ctx.drawText(Ui.tr(), line, tx, y, color, false);
                y += 10;
            }
            if (lines.isEmpty()) y += 10;
            y += 7;
        }
        ctx.disableScissor();

        // разделитель над кнопками
        ctx.fill(cx + PAD, cy + ch - BODY_BOTTOM + 4, cx + cw - PAD, cy + ch - BODY_BOTTOM + 5, Ui.SEP);

        backBtn.visible = index > 0;
        skipBtn.visible = index < steps.size() - 1;
        super.render(ctx, mx, my, delta);
    }

    private final long openedAt = System.currentTimeMillis();

    private void drawChips(DrawContext ctx, int mx, int my, Step current) {
        chips.clear();
        List<String> present = new ArrayList<>();
        for (String s : SECTIONS) {
            for (Step st : steps) {
                if (st.section.equals(s)) { present.add(s); break; }
            }
        }
        int avail = cw - PAD * 2;
        int textSum = 0;
        List<Text> labels = new ArrayList<>();
        for (String s : present) {
            int si = indexOf(s);
            Text t = s.equals("intro") ? tr("tut.sec.intro", "Intro")
                    : s.equals("finish") ? tr("tut.sec.finish", "Finish") : tr("cat." + s, SECTION_FALLBACK[si]);
            labels.add(t);
            textSum += Ui.tr().getWidth(t);
        }
        int gap = 3;
        int padEach = MathHelper.clamp((avail - textSum - gap * (present.size() - 1)) / (present.size() * 2), 3, 7);
        int x = cx + PAD;
        int y = cy + 34;
        for (int i = 0; i < present.size(); i++) {
            String s = present.get(i);
            Text t = labels.get(i);
            int w = Ui.tr().getWidth(t) + padEach * 2;
            boolean sel = s.equals(current.section);
            boolean hov = mx >= x && mx < x + w && my >= y && my < y + 14;
            if (sel) {
                Ui.rect(ctx, x, y, w, 14, 5, Ui.ACCENT_BG);
                Ui.outline(ctx, x, y, w, 14, 5, Ui.ACCENT_LINE);
            } else if (hov) {
                Ui.rect(ctx, x, y, w, 14, 5, 0x14FFFFFF);
            }
            Ui.text(ctx, t, x + padEach, y + 3, sel ? Ui.ACCENT : Ui.TEXT_DIM);
            chips.add(new Chip(x, y, w, 14, s));
            x += w + gap;
        }
    }

    private static int indexOf(String s) {
        for (int i = 0; i < SECTIONS.length; i++) if (SECTIONS[i].equals(s)) return i;
        return 0;
    }

    // ---------------- ввод ----------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (Chip c : chips) {
                if (mx >= c.x() && mx < c.x() + c.w() && my >= c.y() && my < c.y() + c.h()) {
                    click();
                    jumpTo(c.section());
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_D -> {
                next();
                return true;
            }
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> {
                back();
                return true;
            }
            default -> { }
        }
        return super.keyPressed(keyCode, scanCode, modifiers); // Esc -> close() -> пропуск
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        finish(); // Esc = пропустить
    }
}
