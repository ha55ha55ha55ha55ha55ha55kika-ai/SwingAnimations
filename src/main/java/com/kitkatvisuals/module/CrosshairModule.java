package com.kitkatvisuals.module;

import com.kitkatvisuals.config.CrosshairPresets;
import com.kitkatvisuals.gui.CrosshairEditorScreen;
import com.kitkatvisuals.gui.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;

/**
 * Свой прицел: 10 форм, 10 цветов, быстрая смена клавишей (в «Управлении» назначьте
 * «Next crosshair» и «Next crosshair color»). Ванильный прицел скрывается миксином InGameHud.
 *
 * Формы: 1 крест, 2 точка, 3 круг, 4 крест с точкой, 5 «X», 6 «T», 7 уголки, 8 ромб,
 * 9 шеврон, 10 кольцо с засечками. Все рисуются обычными прямоугольниками, поэтому чёткие при любом масштабе GUI.
 */
public final class CrosshairModule extends Module {
    public static final int STYLES = 10;
    private static final String[] COLORS = {"White", "Red", "Green", "Blue", "Yellow", "Cyan", "Pink", "Orange", "Accent", "Rainbow"};
    private static final int[] RGB = {0xFFFFFF, 0xFF4D4D, 0x5CFF7A, 0x4DA3FF, 0xFFE04D, 0x4DF0FF, 0xFF7AD9, 0xFFA14D};
    private static final int TARGET_RGB = 0xFF3B3B;

    public static final int GRID = 15;
    private static final String DEFAULT_GRID = defaultGrid();

    public final Setting.Mode mode = add(new Setting.Mode("mode", "Mode", "Preset", "Custom"));
    public final Setting.Bool openEditor = add(new Setting.Bool("editor", "Open editor", false));
    private final Setting.Num style = add(new Setting.Num("style", "Style (1-10)", 1f, 1f, STYLES, 1f)
            .<Setting.Num>visibleWhen(() -> !this.custom()));
    private final Setting.Mode color = add(new Setting.Mode("color", "Color", COLORS)
            .<Setting.Mode>visibleWhen(() -> !this.custom()));
    private final Setting.Num size = add(new Setting.Num("size", "Size", 5f, 2f, 16f, 1f)
            .<Setting.Num>visibleWhen(() -> !this.custom()));
    private final Setting.Num gap = add(new Setting.Num("gap", "Gap", 3f, 0f, 10f, 1f)
            .<Setting.Num>visibleWhen(() -> !this.custom()));
    private final Setting.Num thickness = add(new Setting.Num("thickness", "Thickness", 1f, 1f, 4f, 1f)
            .<Setting.Num>visibleWhen(() -> !this.custom()));
    /** Параметры пользовательского прицела (редактируются в редакторе). */
    public final Setting.Num pixelSize = add(new Setting.Num("pixel_size", "Pixel size", 2f, 1f, 6f, 1f)
            .<Setting.Num>visibleWhen(() -> this.custom()));
    public final Setting.Num pixelGap = add(new Setting.Num("pixel_gap", "Pixel gap", 0f, 0f, 12f, 1f)
            .<Setting.Num>visibleWhen(() -> this.custom()));
    public final Setting.Num colR = add(new Setting.Num("col_r", "Red", 255f, 0f, 255f, 1f)
            .<Setting.Num>visibleWhen(() -> this.custom()));
    public final Setting.Num colG = add(new Setting.Num("col_g", "Green", 255f, 0f, 255f, 1f)
            .<Setting.Num>visibleWhen(() -> this.custom()));
    public final Setting.Num colB = add(new Setting.Num("col_b", "Blue", 255f, 0f, 255f, 1f)
            .<Setting.Num>visibleWhen(() -> this.custom()));
    private final Setting.Str gridStr = add(new Setting.Str("grid", "Custom grid", DEFAULT_GRID));
    public final Setting.Bool outline = add(new Setting.Bool("outline", "Outline", true));
    private final Setting.Bool dynamic = add(new Setting.Bool("dynamic", "Dynamic gap", true));
    private final Setting.Bool onTarget = add(new Setting.Bool("on_target", "Red on target", true));
    private final Setting.Bool indicator = add(new Setting.Bool("indicator", "Attack indicator", true));

    // Прямоугольники текущей формы (переиспользуемые массивы, без аллокаций в кадре).
    private final int[] rx = new int[640], ry = new int[640], rw = new int[640], rh = new int[640];
    private int rn;
    private int t;
    private float dyn;

    public CrosshairModule() {
        super("crosshair", "Crosshair", "hud");
    }

    private static String defaultGrid() {
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                int dx = Math.abs(x - GRID / 2), dy = Math.abs(y - GRID / 2);
                sb.append((dx == 0 && dy >= 2 && dy <= 5) || (dy == 0 && dx >= 2 && dx <= 5) ? '1' : '0');
            }
        }
        return sb.toString();
    }

    public boolean custom() { return mode.is("Custom"); }

    // ---------------- пользовательская сетка ----------------

    private String cacheSrc;
    private final boolean[] cells = new boolean[GRID * GRID];

    /** Клетки сетки 15x15 (индекс = y * 15 + x). Для редактора: меняйте и вызывайте commitGrid(). */
    public boolean[] cells() {
        if (!gridStr.value.equals(cacheSrc)) {
            cacheSrc = gridStr.value;
            for (int i = 0; i < cells.length; i++) cells[i] = i < cacheSrc.length() && cacheSrc.charAt(i) == '1';
        }
        return cells;
    }

    public void commitGrid() {
        StringBuilder sb = new StringBuilder(GRID * GRID);
        for (boolean c : cells) sb.append(c ? '1' : '0');
        gridStr.value = sb.toString();
        cacheSrc = gridStr.value;
        ModuleManager.save();
    }

    public int customRgb() { return ((int) colR.value << 16) | ((int) colG.value << 8) | (int) colB.value; }

    /** Копирует форму текущего встроенного прицела в сетку (обрезается по 15x15). */
    public void importPreset() {
        boolean[] c = cells();
        java.util.Arrays.fill(c, false);
        t = (int) thickness.value;
        build(GRID / 2, GRID / 2, (int) size.value, (int) gap.value);
        for (int i = 0; i < rn; i++) {
            for (int x = rx[i]; x < rx[i] + rw[i]; x++) {
                for (int y = ry[i]; y < ry[i] + rh[i]; y++) {
                    if (x >= 0 && x < GRID && y >= 0 && y < GRID) c[y * GRID + x] = true;
                }
            }
        }
        commitGrid();
    }

    public CrosshairPresets.Data toPreset() {
        CrosshairPresets.Data d = new CrosshairPresets.Data();
        boolean[] c = cells();
        for (int y = 0; y < GRID; y++) {
            StringBuilder sb = new StringBuilder(GRID);
            for (int x = 0; x < GRID; x++) sb.append(c[y * GRID + x] ? '1' : '0');
            d.grid.add(sb.toString());
        }
        d.pixelSize = (int) pixelSize.value;
        d.gap = (int) pixelGap.value;
        d.color = String.format("#%06X", customRgb());
        d.outline = outline.value;
        return d;
    }

    /** Применяет пресет; поля вне допустимого диапазона обрезаются. Всегда безопасно для повреждённого JSON. */
    public void applyPreset(CrosshairPresets.Data d) {
        boolean[] c = cells();
        for (int y = 0; y < GRID; y++) {
            String row = d.grid != null && y < d.grid.size() && d.grid.get(y) != null ? d.grid.get(y) : "";
            for (int x = 0; x < GRID; x++) c[y * GRID + x] = x < row.length() && row.charAt(x) == '1';
        }
        pixelSize.set(d.pixelSize);
        pixelGap.set(d.gap);
        try {
            String hex = d.color == null ? "FFFFFF" : d.color.replace("#", "");
            int rgb = Integer.parseInt(hex, 16) & 0xFFFFFF;
            colR.set((rgb >> 16) & 255);
            colG.set((rgb >> 8) & 255);
            colB.set(rgb & 255);
        } catch (NumberFormatException ignored) {
            // цвет остаётся прежним
        }
        outline.value = d.outline;
        commitGrid();
    }

    /** Рисует пользовательский прицел с центром в (cx, cy). Используется в игре и в окне предпросмотра. */
    public void drawCustom(DrawContext ctx, int cx, int cy, int extraGap, int argb) {
        boolean[] c = cells();
        int s = (int) pixelSize.value;
        int g = (int) pixelGap.value + extraGap;
        int mid = GRID / 2;
        for (int pass = outline.value ? 0 : 1; pass < 2; pass++) {
            int pad = pass == 0 ? 1 : 0;
            int col = pass == 0 ? 0xB0000000 : argb;
            for (int j = 0; j < GRID; j++) {
                int y = cy - s / 2 + (j - mid) * s + Integer.signum(j - mid) * g;
                int runStart = 0, runEnd = Integer.MIN_VALUE;
                boolean run = false;
                for (int i = 0; i < GRID; i++) {
                    if (!c[j * GRID + i]) continue;
                    int x = cx - s / 2 + (i - mid) * s + Integer.signum(i - mid) * g;
                    if (run && x == runEnd) {
                        runEnd = x + s;
                    } else {
                        if (run) ctx.fill(runStart - pad, y - pad, runEnd + pad, y + s + pad, col);
                        runStart = x;
                        runEnd = x + s;
                        run = true;
                    }
                }
                if (run) ctx.fill(runStart - pad, y - pad, runEnd + pad, y + s + pad, col);
            }
        }
    }

    public void openEditor() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!isEnabled()) setEnabled(true);
        mode.index = 1;
        if (mc.currentScreen instanceof CrosshairEditorScreen) return;
        mc.setScreen(new CrosshairEditorScreen(mc.currentScreen, this));
    }

    @Override
    public void tick(MinecraftClient mc) {
        if (openEditor.value) {
            openEditor.value = false;
            openEditor();
        }
    }

    public int styleIndex() { return (int) style.value; }

    /** Следующая форма по кругу, возвращает её номер. */
    public int nextStyle() {
        style.set(style.value % STYLES + 1f);
        ModuleManager.save();
        return (int) style.value;
    }

    public String nextColor() {
        color.next();
        ModuleManager.save();
        return color.get();
    }

    // ---------------- геометрия ----------------

    private void add(int x, int y, int w, int h) {
        if (rn >= rx.length) return;
        rx[rn] = x;
        ry[rn] = y;
        rw[rn] = w;
        rh[rn] = h;
        rn++;
    }

    private void dot(int x, int y) { add(x - t / 2, y - t / 2, t, t); }

    private void circle(int cx, int cy, int r) {
        int x = r, y = 0, d = 1 - r;
        while (x >= y) {
            dot(cx + x, cy + y); dot(cx - x, cy + y); dot(cx + x, cy - y); dot(cx - x, cy - y);
            if (x != y) { dot(cx + y, cy + x); dot(cx - y, cy + x); dot(cx + y, cy - x); dot(cx - y, cy - x); }
            y++;
            if (d < 0) {
                d += 2 * y + 1;
            } else {
                x--;
                d += 2 * (y - x) + 1;
            }
        }
    }

    private void build(int cx, int cy, int sz, int g) {
        int half = t / 2;
        int style = (int) this.style.value;
        rn = 0;
        switch (style) {
            case 1 -> cross(cx, cy, sz, g, half, true);
            case 2 -> {
                int d = Math.max(2, t + 1);
                add(cx - d / 2, cy - d / 2, d, d);
            }
            case 3 -> circle(cx, cy, Math.max(3, sz + g / 2));
            case 4 -> {
                cross(cx, cy, sz, g, half, true);
                int d = t + 1;
                add(cx - d / 2, cy - d / 2, d, d);
            }
            case 5 -> {
                for (int i = 0; i < sz; i++) {
                    int o = g + 1 + i;
                    dot(cx - o, cy - o); dot(cx + o, cy - o); dot(cx - o, cy + o); dot(cx + o, cy + o);
                }
            }
            case 6 -> cross(cx, cy, sz, g, half, false);
            case 7 -> {
                int h = sz + g;
                int a = Math.max(2, sz / 2 + 1);
                int x0 = cx - h, x1 = cx + h, y0 = cy - h, y1 = cy + h;
                add(x0, y0, a, t); add(x0, y0, t, a);
                add(x1 - a + t, y0, a, t); add(x1, y0, t, a);
                add(x0, y1, a, t); add(x0, y1 - a + t, t, a);
                add(x1 - a + t, y1, a, t); add(x1, y1 - a + t, t, a);
            }
            case 8 -> {
                int r = Math.max(3, sz + g / 2);
                for (int i = 0; i <= r; i++) {
                    dot(cx + i, cy + (r - i)); dot(cx - i, cy + (r - i));
                    dot(cx + i, cy - (r - i)); dot(cx - i, cy - (r - i));
                }
            }
            case 9 -> {
                int top = cy - sz / 2;
                for (int i = 0; i <= sz; i++) {
                    dot(cx - i, top + i);
                    dot(cx + i, top + i);
                }
            }
            default -> {
                int r = Math.max(3, sz + g / 2);
                circle(cx, cy, r);
                add(cx + r + 2, cy - half, 3, t);
                add(cx - r - 4, cy - half, 3, t);
                add(cx - half, cy + r + 2, t, 3);
                add(cx - half, cy - r - 4, t, 3);
            }
        }
    }

    private void cross(int cx, int cy, int sz, int g, int half, boolean top) {
        add(cx - g - sz, cy - half, sz, t);
        add(cx + 1 + g, cy - half, sz, t);
        add(cx - half, cy + 1 + g, t, sz);
        if (top) add(cx - half, cy - g - sz, t, sz);
    }

    // ---------------- цвет и отрисовка ----------------

    private int currentRgb(MinecraftClient mc) {
        if (onTarget.value && mc.targetedEntity instanceof LivingEntity le && le.isAlive()) return TARGET_RGB;
        int i = color.index;
        if (i < RGB.length) return RGB[i];
        if (i == 8) return Ui.ACCENT & 0xFFFFFF;
        float hue = ((System.currentTimeMillis() / 20L) % 360L) / 360f;
        return MathHelper.hsvToRgb(hue, 0.7f, 1f);
    }

    @Override
    public void renderHud(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.options.hudHidden || !mc.options.getPerspective().isFirstPerson()) return;

        float target = 0f;
        if (dynamic.value) {
            double v = p.getVelocity().horizontalLengthSquared();
            if (v > 0.0004) target += 2f;
            if (p.isSprinting()) target += 2f;
            if (!p.isOnGround()) target += 2f;
        }
        dyn += (target - dyn) * 0.2f;

        t = (int) thickness.value;
        int cx = ctx.getScaledWindowWidth() / 2;
        int cy = ctx.getScaledWindowHeight() / 2;
        boolean cu = custom();
        if (!cu) build(cx, cy, (int) size.value, (int) gap.value + Math.round(dyn));

        int col;
        if (cu) {
            boolean red = onTarget.value && mc.targetedEntity instanceof LivingEntity le && le.isAlive();
            col = 0xFF000000 | (red ? TARGET_RGB : customRgb());
            drawCustom(ctx, cx, cy, Math.round(dyn), col);
            rn = 0;
        } else {
            col = 0xFF000000 | currentRgb(mc);
        }
        if (outline.value && !cu) {
            for (int i = 0; i < rn; i++) {
                ctx.fill(rx[i] - 1, ry[i] - 1, rx[i] + rw[i] + 1, ry[i] + rh[i] + 1, 0xB0000000);
            }
        }
        for (int i = 0; i < rn; i++) {
            ctx.fill(rx[i], ry[i], rx[i] + rw[i], ry[i] + rh[i], col);
        }

        if (indicator.value) {
            float prog = p.getAttackCooldownProgress(0.5f);
            if (prog < 1f) {
                int w = 17;
                int bx = cx - w / 2;
                int by = cu ? cy + (GRID / 2 + 1) * (int) pixelSize.value + (int) pixelGap.value + 6
                        : cy + (int) size.value + (int) gap.value + 8;
                ctx.fill(bx - 1, by - 1, bx + w + 1, by + 3, 0xB0000000);
                ctx.fill(bx, by, bx + Math.round(w * prog), by + 2, col);
            }
        }
    }
}
