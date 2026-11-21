package com.kitkatvisuals.gui;

import com.kitkatvisuals.config.CrosshairPresets;
import com.kitkatvisuals.module.CrosshairModule;
import com.kitkatvisuals.module.ModuleManager;
import com.kitkatvisuals.module.Setting;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Редактор прицела: сетка 15x15 (ЛКМ рисует/стирает по первой клетке, ПКМ стирает), ползунки размера пикселя, зазора и цвета,
 * симметрия, предпросмотр, сохранение и загрузка пресетов в config/kitkatvisuals/crosshairs/*.json.
 */
public final class CrosshairEditorScreen extends Screen {
    private static final int N = CrosshairModule.GRID;
    private static final int ROW = 16;

    private final Screen parent;
    private final CrosshairModule m;

    private TextFieldWidget nameField;
    private final List<String> presets = new ArrayList<>();
    private int scroll;
    private String status = "";
    private boolean symmetry = true;

    private int gx, gy, cell;
    private int px, pw;
    private int sliderTop, listTop, listRows;
    private int paint;     // 0 нет, 1 рисуем, 2 стираем
    private int dragSlider = -1;

    private final Setting.Num[] sliders;
    private static final String[] LABELS = {"Размер пикселя", "Зазор", "Красный", "Зелёный", "Синий"};

    public CrosshairEditorScreen(Screen parent, CrosshairModule m) {
        super(Text.literal("Crosshair Editor"));
        this.parent = parent;
        this.m = m;
        this.sliders = new Setting.Num[]{m.pixelSize, m.pixelGap, m.colR, m.colG, m.colB};
    }

    @Override
    protected void init() {
        cell = MathHelper.clamp(Math.min((height - 40) / N, (int) (width * 0.42f) / N), 6, 18);
        gx = 14;
        gy = Math.max(10, (height - cell * N) / 2);
        px = gx + cell * N + 18;
        pw = Math.max(150, width - px - 12);

        int y = 10;
        sliderTop = y + 76;                       // под предпросмотром
        int by = sliderTop + sliders.length * 20 + 4;
        int half = (pw - 4) / 2;

        addDrawableChild(new UiButton(px, by, half, 18, UiButton.Kind.TOGGLE, () -> Text.literal("Обводка"), null,
                () -> m.outline.value, () -> { m.outline.value = !m.outline.value; ModuleManager.save(); }));
        addDrawableChild(new UiButton(px + half + 4, by, half, 18, UiButton.Kind.TOGGLE, () -> Text.literal("Симметрия"), null,
                () -> symmetry, () -> symmetry = !symmetry));
        by += 22;
        addDrawableChild(new UiButton(px, by, half, 18, UiButton.Kind.PLAIN, () -> Text.literal("Очистить"), null, null,
                () -> { java.util.Arrays.fill(m.cells(), false); m.commitGrid(); status = "Сетка очищена"; }));
        addDrawableChild(new UiButton(px + half + 4, by, half, 18, UiButton.Kind.PLAIN, () -> Text.literal("Из пресета игры"), null, null,
                () -> { m.importPreset(); status = "Форма прицела скопирована"; }));
        by += 26;

        nameField = new TextFieldWidget(textRenderer, px + 4, by + 3, pw - half - 12, 12, Text.empty());
        nameField.setDrawsBackground(false);
        nameField.setMaxLength(32);
        addDrawableChild(nameField);
        addDrawableChild(new UiButton(px + pw - half + 4, by - 1, half - 4, 18, UiButton.Kind.PLAIN, () -> Text.literal("Сохранить"), null, null, this::savePreset));
        by += 24;

        listTop = by + 12;
        listRows = Math.max(1, (height - listTop - 8) / ROW);
        refresh();
    }

    private void refresh() {
        presets.clear();
        presets.addAll(CrosshairPresets.list());
        scroll = MathHelper.clamp(scroll, 0, Math.max(0, presets.size() - listRows));
    }

    private void savePreset() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) { status = "Введите имя пресета"; return; }
        status = CrosshairPresets.save(name, m.toPreset()) ? "Сохранено: " + name : "Не удалось сохранить";
        refresh();
    }

    private void loadPreset(String name) {
        CrosshairPresets.Data d = CrosshairPresets.load(name);
        if (d == null) { status = "Файл повреждён или не найден"; return; }
        m.applyPreset(d);
        nameField.setText(name);
        status = "Загружено: " + name;
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void close() {
        m.commitGrid();
        if (client != null) client.setScreen(parent);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // Намеренно пусто: затемнение рисуем сами в render(), чтобы super.render() не закрыл сетку.
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        ctx.fill(0, 0, width, height, 0xB0000000);
        // сетка
        Ui.rect(ctx, gx - 6, gy - 6, cell * N + 12, cell * N + 12, 6, Ui.BG);
        boolean[] c = m.cells();
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                int x = gx + i * cell, y = gy + j * cell;
                boolean center = i == N / 2 && j == N / 2;
                int bg = ((i + j) & 1) == 0 ? 0xFF1E1E1E : 0xFF262626;
                ctx.fill(x, y, x + cell, y + cell, bg);
                if (c[j * N + i]) ctx.fill(x + 1, y + 1, x + cell - 1, y + cell - 1, 0xFF000000 | m.customRgb());
                if (center) {
                    ctx.drawBorder(x, y, cell, cell, Ui.ACCENT_LINE);
                }
            }
        }
        // панель
        Ui.rect(ctx, px - 6, 4, pw + 12, height - 8, 6, Ui.BG);
        Ui.text(ctx, Text.literal("Редактор прицела"), px, 8, Ui.TEXT);
        // предпросмотр на «небе»
        int bx = px, by = 22, bw = pw, bh = 56;
        ctx.fillGradient(bx, by, bx + bw, by + bh, 0xFF5E8FC9, 0xFF9CC4E8);
        m.drawCustom(ctx, bx + bw / 2, by + bh / 2, 0, 0xFF000000 | m.customRgb());
        // ползунки
        for (int i = 0; i < sliders.length; i++) {
            int y = sliderTop + i * 20;
            Setting.Num n = sliders[i];
            Ui.text(ctx, Text.literal(LABELS[i] + ": " + (int) n.value), px, y, Ui.TEXT_DIM);
            ctx.fill(px, y + 11, px + pw, y + 14, Ui.SWITCH_OFF);
            int kx = px + Math.round(n.fraction() * (pw - 6));
            ctx.fill(px, y + 11, kx + 3, y + 14, i >= 2 ? 0xFF000000 | channelColor(i) : Ui.ACCENT);
            ctx.fill(kx, y + 8, kx + 6, y + 17, Ui.KNOB);
        }
        super.render(ctx, mx, my, delta);
        // имя и список
        Ui.outline(ctx, nameField.getX() - 4, nameField.getY() - 3, nameField.getWidth() + 8, 18, 4, Ui.OUTLINE);
        if (nameField.getText().isEmpty() && !nameField.isFocused()) {
            Ui.text(ctx, Text.literal("Имя пресета"), nameField.getX(), nameField.getY() + 2, 0x66FFFFFF);
        }
        Ui.text(ctx, Text.literal(status.isEmpty() ? "Пресеты (клик: загрузить, x: удалить)" : status), px, listTop - 12, Ui.TEXT_DIM);
        for (int i = 0; i < listRows; i++) {
            int idx = scroll + i;
            if (idx >= presets.size()) break;
            int y = listTop + i * ROW;
            boolean hov = mx >= px && mx < px + pw && my >= y && my < y + ROW - 1;
            Ui.rect(ctx, px, y, pw, ROW - 2, 3, hov ? Ui.FIELD_HOVER : Ui.FIELD);
            Ui.text(ctx, Text.literal(presets.get(idx)), px + 5, y + 4, Ui.TEXT);
            Ui.text(ctx, Text.literal("x"), px + pw - 10, y + 4, mx >= px + pw - 14 && hov ? 0xFFFF6060 : Ui.TEXT_DIM);
        }
        Ui.text(ctx, Text.literal("ЛКМ рисует, ПКМ стирает"), gx, gy + cell * N + 10, Ui.TEXT_DIM);
    }

    private int channelColor(int i) { return i == 2 ? 0xFF4040 : (i == 3 ? 0x40FF40 : 0x4080FF); }

    private int cellAt(double mx, double my) {
        int i = (int) Math.floor((mx - gx) / cell), j = (int) Math.floor((my - gy) / cell);
        return i < 0 || j < 0 || i >= N || j >= N ? -1 : j * N + i;
    }

    private void set(int idx, boolean v) {
        boolean[] c = m.cells();
        int i = idx % N, j = idx / N;
        c[idx] = v;
        if (symmetry) {
            c[j * N + (N - 1 - i)] = v;
            c[(N - 1 - j) * N + i] = v;
            c[(N - 1 - j) * N + (N - 1 - i)] = v;
        }
    }

    private void setSlider(int i, double mx) {
        Setting.Num n = sliders[i];
        float f = MathHelper.clamp((float) ((mx - px) / (pw - 6)), 0f, 1f);
        n.set(n.min + f * (n.max - n.min));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        int idx = cellAt(mx, my);
        if (idx >= 0 && (button == 0 || button == 1)) {
            paint = button == 1 || m.cells()[idx] ? 2 : 1;
            set(idx, paint == 1);
            return true;
        }
        if (button == 0) {
            for (int i = 0; i < sliders.length; i++) {
                int y = sliderTop + i * 20;
                if (mx >= px && mx <= px + pw && my >= y + 6 && my <= y + 18) {
                    dragSlider = i;
                    setSlider(i, mx);
                    return true;
                }
            }
            for (int i = 0; i < listRows; i++) {
                int pi = scroll + i;
                int y = listTop + i * ROW;
                if (pi < presets.size() && mx >= px && mx < px + pw && my >= y && my < y + ROW - 1) {
                    if (mx >= px + pw - 14) {
                        status = CrosshairPresets.delete(presets.get(pi)) ? "Удалено: " + presets.get(pi) : "Не удалось удалить";
                        refresh();
                    } else {
                        loadPreset(presets.get(pi));
                    }
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragSlider >= 0) {
            setSlider(dragSlider, mx);
            return true;
        }
        if (paint != 0) {
            int idx = cellAt(mx, my);
            if (idx >= 0) set(idx, paint == 1);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (paint != 0 || dragSlider >= 0) m.commitGrid();   // commitGrid сохраняет и сетку, и ползунки
        paint = 0;
        dragSlider = -1;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (mx >= px) {
            scroll = MathHelper.clamp(scroll - (int) Math.signum(v), 0, Math.max(0, presets.size() - listRows));
            return true;
        }
        return super.mouseScrolled(mx, my, h, v);
    }
}
