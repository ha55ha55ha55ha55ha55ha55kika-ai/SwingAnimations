package com.swinganimations.gui;

import com.swinganimations.animation.Curve;
import com.swinganimations.animation.SwingLogic;
import com.swinganimations.animation.SwingMode;
import com.swinganimations.config.ConfigManager;
import com.swinganimations.config.HandProfile;
import com.swinganimations.config.ItemFilter;
import com.swinganimations.config.Presets;
import com.swinganimations.config.SwingConfig;
import com.swinganimations.input.KeyBinds;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Меню слева, чтобы справа внизу оставалась видна рука с предметом: кнопка «Тест» зацикливает
 * свинг прямо в реальном first-person рендере (фон меню не затемняется и не блюрится).
 */
public class SettingsScreen extends Screen {
    private enum Page { GENERAL, MAIN, OFF, PRESETS }

    private static final int LX = 12;
    private static final int RX = 168;
    private static final int W = 150;

    private final Screen parent;
    private final SwingConfig cfg = ConfigManager.get();
    private Page page = Page.GENERAL;

    public SettingsScreen(Screen parent) {
        super(Text.translatable("swinganimations.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Page[] pages = Page.values();
        for (int i = 0; i < pages.length; i++) {
            Page p = pages[i];
            ButtonWidget b = ButtonWidget.builder(
                    Text.translatable("swinganimations.tab." + p.name().toLowerCase(Locale.ROOT)),
                    btn -> switchPage(p)).dimensions(LX + i * 77, 8, 75, 20).build();
            b.active = p != page;
            addDrawableChild(b);
        }
        switch (page) {
            case GENERAL -> buildGeneral();
            case MAIN -> buildProfile(Hand.MAIN_HAND, cfg.mainHand);
            case OFF -> buildProfile(Hand.OFF_HAND, cfg.offHand);
            case PRESETS -> buildPresets();
        }
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), b -> close())
                .dimensions(RX, height - 28, W, 20).build());
    }

    private void switchPage(Page p) {
        page = p;
        SwingLogic.previewHand = null;
        rebuild();
    }

    private void rebuild() {
        clearChildren();
        init();
    }

    // ---------- pages ----------

    private void buildGeneral() {
        int y = 36;
        addDrawableChild(toggle(LX, y, 306, "swinganimations.option.enabled",
                () -> cfg.enabled, v -> cfg.enabled = v));
        y += 24;
        addDrawableChild(cycle(LX, y, 306, "swinganimations.option.filter", "swinganimations.filter.",
                ItemFilter.values(), () -> cfg.filter, v -> cfg.filter = v));
        y += 34;

        TextFieldWidget field = new TextFieldWidget(textRenderer, LX, y + 10, 306, 20, Text.empty());
        field.setMaxLength(2048);
        field.setPlaceholder(Text.translatable("swinganimations.option.custom_hint"));
        field.setText(String.join(", ", cfg.customItems));
        field.setChangedListener(s -> {
            List<String> list = new ArrayList<>();
            for (String part : s.split(",")) {
                String t = part.trim();
                if (!t.isEmpty()) list.add(t);
            }
            cfg.customItems = list;
            cfg.rebuildCaches();
        });
        addDrawableChild(field);
    }

    private void buildProfile(Hand hand, HandProfile hp) {
        int y = 36;
        addDrawableChild(cycle(LX, y, W, "swinganimations.option.mode", null,
                SwingMode.values(), () -> hp.mode, v -> hp.mode = v, SwingMode::translationKey));
        y += 24;
        slider(LX, y, "swinganimations.slider.speed", 0.3f, 3f, hp.speed, "%.2f", v -> hp.speed = v);
        y += 24;
        addDrawableChild(cycle(LX, y, W, "swinganimations.option.curve", "swinganimations.curve.",
                Curve.values(), () -> hp.curve, v -> hp.curve = v));
        y += 24;
        slider(LX, y, "swinganimations.slider.strength", 0f, 1f, hp.curveStrength, "%.2f", v -> hp.curveStrength = v);
        y += 24;
        slider(LX, y, "swinganimations.slider.x", -1f, 1f, hp.x, "%.2f", v -> hp.x = v);
        y += 24;
        slider(LX, y, "swinganimations.slider.y", -1f, 1f, hp.y, "%.2f", v -> hp.y = v);
        y += 24;
        slider(LX, y, "swinganimations.slider.z", -1f, 1f, hp.z, "%.2f", v -> hp.z = v);

        y = 36;
        slider(RX, y, "swinganimations.slider.pitch", -180f, 180f, hp.pitch, "%.0f", v -> hp.pitch = v);
        y += 24;
        slider(RX, y, "swinganimations.slider.yaw", -180f, 180f, hp.yaw, "%.0f", v -> hp.yaw = v);
        y += 24;
        slider(RX, y, "swinganimations.slider.roll", -180f, 180f, hp.roll, "%.0f", v -> hp.roll = v);
        y += 24;
        slider(RX, y, "swinganimations.slider.scale", 0.3f, 1.5f, hp.scale, "%.2f", v -> hp.scale = v);
        y += 24;
        addDrawableChild(toggle(RX, y, W, "swinganimations.option.walk", () -> hp.walkSway, v -> hp.walkSway = v));
        y += 24;
        addDrawableChild(toggle(RX, y, W, "swinganimations.option.crit", () -> hp.critAnimation, v -> hp.critAnimation = v));
        y += 24;
        addDrawableChild(ButtonWidget.builder(testLabel(hand), b -> {
            SwingLogic.previewHand = SwingLogic.previewHand == hand ? null : hand;
            b.setMessage(testLabel(hand));
        }).dimensions(RX, y, W, 20).build());
    }

    private void buildPresets() {
        List<String> names = Presets.names(cfg);
        for (int i = 0; i < names.size() && i < 16; i++) {
            String n = names.get(i);
            Text label = Text.literal(n.equals(cfg.activePreset) ? "\u25B6 " + n : n);
            addDrawableChild(ButtonWidget.builder(label, b -> {
                Presets.apply(cfg, n);
                ConfigManager.save();
                rebuild();
            }).dimensions(LX + (i % 2) * 156, 36 + (i / 2) * 24, W, 20).build());
        }
        addDrawableChild(ButtonWidget.builder(Text.translatable("swinganimations.preset.save"), b -> {
            Presets.saveCurrent(cfg);
            ConfigManager.save();
            rebuild();
        }).dimensions(LX, height - 28, W, 20).build());
    }

    // ---------- widget helpers ----------

    private void slider(int x, int y, String key, float min, float max, float cur, String fmt, ValueSlider.Setter s) {
        addDrawableChild(new ValueSlider(x, y, W, 20, key, min, max, cur, fmt, s));
    }

    private static Text onOff(String key, boolean on) {
        return Text.translatable(key, Text.translatable(on ? "swinganimations.on" : "swinganimations.off"));
    }

    private static Text testLabel(Hand hand) {
        return onOff("swinganimations.option.test", SwingLogic.previewHand == hand);
    }

    private ButtonWidget toggle(int x, int y, int w, String key, BooleanSupplier get, Consumer<Boolean> set) {
        return ButtonWidget.builder(onOff(key, get.getAsBoolean()), b -> {
            set.accept(!get.getAsBoolean());
            b.setMessage(onOff(key, get.getAsBoolean()));
        }).dimensions(x, y, w, 20).build();
    }

    private <E extends Enum<E>> ButtonWidget cycle(int x, int y, int w, String key, String prefix,
                                                   E[] values, Supplier<E> get, Consumer<E> set) {
        return cycle(x, y, w, key, prefix, values, get, set,
                e -> prefix + e.name().toLowerCase(Locale.ROOT));
    }

    private <E extends Enum<E>> ButtonWidget cycle(int x, int y, int w, String key, String prefix,
                                                   E[] values, Supplier<E> get, Consumer<E> set,
                                                   java.util.function.Function<E, String> keyOf) {
        return ButtonWidget.builder(Text.translatable(key, Text.translatable(keyOf.apply(get.get()))), b -> {
            E next = values[(get.get().ordinal() + 1) % values.length];
            set.accept(next);
            b.setMessage(Text.translatable(key, Text.translatable(keyOf.apply(next))));
        }).dimensions(x, y, w, 20).build();
    }

    // ---------- screen plumbing ----------

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // Намеренно пусто: без блюра/затемнения мир и рука видны позади меню (нужно для «Теста»).
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(6, 3, 324, height - 3, 0xA0000000);
        super.render(ctx, mouseX, mouseY, delta);
        if (page == Page.GENERAL) {
            ctx.drawTextWithShadow(textRenderer, Text.translatable("swinganimations.option.custom"), LX, 94, 0xFFFFFF);
            ctx.drawTextWithShadow(textRenderer, Text.translatable("swinganimations.hint.1"), LX, 140, 0xAAAAAA);
            ctx.drawTextWithShadow(textRenderer, Text.translatable("swinganimations.hint.2"), LX, 152, 0xAAAAAA);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (KeyBinds.OPEN != null && KeyBinds.OPEN.matchesKey(keyCode, scanCode)
                && !(getFocused() instanceof TextFieldWidget)) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void removed() {
        SwingLogic.previewHand = null;
        ConfigManager.save();
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
