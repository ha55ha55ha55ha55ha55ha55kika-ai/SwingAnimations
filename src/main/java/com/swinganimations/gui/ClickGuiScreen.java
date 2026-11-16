package com.swinganimations.gui;

import com.swinganimations.config.UserConfigs;
import com.swinganimations.input.KeyBinds;
import com.swinganimations.module.Module;
import com.swinganimations.module.ModuleManager;
import com.swinganimations.module.Setting;
import com.swinganimations.module.ThemeModule;
import com.swinganimations.render.Batch;
import com.swinganimations.util.Spring;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ClickGUI в виде одного стеклянного окна: слева логотип, вкладки категорий со счётчиками и плашка игрока,
 * справа список модулей (клик = вкл/выкл, стрелка или ПКМ = настройки) либо вкладка Configs.
 * Для FPS: только fill-примитивы со склейкой строк (без шейдеров и собственного размытия),
 * пружины считаются только у видимых элементов, список конфигов читается с диска лишь по действию.
 */
public class ClickGuiScreen extends Screen {
    private static final int SIDEBAR = 124;
    private static final int PAD = 12;
    private static final String CONFIGS = "configs";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final Identifier LOGO = Identifier.of("swinganimations", "textures/gui/logo_small.png"); // 128x92

    private static final int GREEN = 0xFF6BE08B;
    private static final int RED = 0xFFFF6B6B;

    /** Последняя открытая вкладка (запоминается между открытиями окна). */
    private static String selected = "render";

    private enum Kind { MODULE, BOOL, NUM, MODE, BIND, BUTTON }

    private static final class Row {
        final Kind kind;
        final Module module;
        final Setting setting;
        int y;
        int h;

        Row(Kind kind, Module module, Setting setting, int h) {
            this.kind = kind;
            this.module = module;
            this.setting = setting;
            this.h = h;
        }
    }

    private static final class Block {
        final Module module;
        final int y;
        final int h;

        Block(Module module, int y, int h) {
            this.module = module;
            this.y = y;
            this.h = h;
        }
    }

    private static final class Tab {
        final String id;
        final String titleKey;
        final String fallback;
        final Identifier icon;
        final List<Module> modules = new ArrayList<>();

        Tab(String id, String titleKey, String fallback, String icon) {
            this.id = id;
            this.titleKey = titleKey;
            this.fallback = fallback;
            this.icon = Identifier.of("swinganimations", "textures/gui/icon_" + icon + ".png");
        }
    }

    private static final class Cfg {
        final String name;
        final String date;

        Cfg(String name, String date) {
            this.name = name;
            this.date = date;
        }
    }

    private record Hit(int x, int y, int w, int h, Runnable action) { }

    private final Screen parent;
    private final List<Tab> tabs = new ArrayList<>();
    private final Tab configsTab = new Tab(CONFIGS, "swinganimations.cat.configs", "Configs", "pin");
    private final Set<Module> expanded = new HashSet<>();
    private final List<Row> rows = new ArrayList<>();
    private final List<Block> blocks = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private final Map<Object, Spring> springs = new HashMap<>();
    private final long openedAt = System.currentTimeMillis();

    private int wx, wy, ww, wh;
    private int lx, lw, ly, lb;
    private float scroll, scrollTarget;
    private int contentH;

    private Module listening;
    private Row pressRow;
    private Row dragNum;
    private int pressBtn;
    private double pressY, lastY;
    private boolean moved;

    private TextFieldWidget nameField;
    private String nameDraft = "";
    private int fieldX, fieldW;
    private List<Cfg> cfgs = new ArrayList<>();
    private Text status;
    private int statusColor = GREEN;

    public ClickGuiScreen(Screen parent) {
        super(Text.translatable("swinganimations.title"));
        this.parent = parent;
        Tab render = new Tab("render", "swinganimations.cat.render", "Visuals", "flask");
        Tab hud = new Tab("hud", "swinganimations.cat.hud", "HUD", "pulse");
        Tab move = new Tab("movement", "swinganimations.cat.movement", "Movement", "speed");
        Tab player = new Tab("player", "swinganimations.cat.player", "Player", "user");
        Tab anim = new Tab("animations", "swinganimations.cat.animations", "Animations", "clock");
        for (Module m : ModuleManager.MODULES) {
            switch (m.category) {
                case "render" -> render.modules.add(m);
                case "hud" -> hud.modules.add(m);
                case "movement" -> move.modules.add(m);
                case "player" -> player.modules.add(m);
                default -> anim.modules.add(m);
            }
        }
        tabs.add(render);
        tabs.add(hud);
        tabs.add(move);
        tabs.add(player);
        tabs.add(anim);
        if (current() == null) selected = "render";
        if (isConfigs()) refreshCfgs();
    }

    private static Text tr(String key, String fallback, Object... args) {
        return Text.translatableWithFallback("swinganimations." + key, fallback, args);
    }

    private boolean isConfigs() { return CONFIGS.equals(selected); }

    private Tab current() {
        if (isConfigs()) return configsTab;
        for (Tab t : tabs) if (t.id.equals(selected)) return t;
        return null;
    }

    private Spring spring(Object key) {
        return springs.computeIfAbsent(key, k -> new Spring(0f, 220f, 24f));
    }

    private float anim(Object key, boolean on) {
        Spring s = spring(key);
        s.target = on ? 1f : 0f;
        return MathHelper.clamp(s.update(), 0f, 1f);
    }

    // ---------------- раскладка ----------------

    @Override
    protected void init() {
        ww = Math.min(width - 16, MathHelper.clamp((int) (width * 0.66f), 360, 540));
        wh = Math.min(height - 16, MathHelper.clamp((int) (height * 0.80f), 230, 350));
        wx = (width - ww) / 2;
        wy = (height - wh) / 2;
        lx = wx + SIDEBAR + PAD;
        lw = ww - SIDEBAR - PAD * 2;
        ly = wy + 44;
        lb = wy + wh - 10;

        int saveW = 78;
        fieldW = lw - saveW - 6;
        fieldX = lx;
        nameField = new TextFieldWidget(textRenderer, fieldX + 7, ly + 4, fieldW - 14, 12, Text.empty());
        nameField.setDrawsBackground(false);
        nameField.setMaxLength(32);
        nameField.setText(nameDraft);
        nameField.setFocused(false);
    }

    private int listTop() { return isConfigs() ? ly + 42 : ly; }

    private int viewH() { return Math.max(10, lb - listTop()); }

    private void layout() {
        rows.clear();
        blocks.clear();
        if (isConfigs()) {
            contentH = cfgs.isEmpty() ? 0 : cfgs.size() * 39 - 5;
        } else {
            Tab t = current();
            int y = 0;
            if (t != null) {
                for (Module m : t.modules) {
                    int start = y;
                    y = add(new Row(Kind.MODULE, m, null, 24), y);
                    if (expanded.contains(m)) {
                        y += 2;
                        for (Setting s : m.settings) {
                            if (!s.isVisible()) continue;
                            if (s instanceof Setting.Bool) y = add(new Row(Kind.BOOL, m, s, 18), y);
                            else if (s instanceof Setting.Mode) y = add(new Row(Kind.MODE, m, s, 18), y);
                            else if (s instanceof Setting.Num) y = add(new Row(Kind.NUM, m, s, 26), y);
                        }
                        if (m.hasExtraButton()) y = add(new Row(Kind.BUTTON, m, null, 20), y);
                        y = add(new Row(Kind.BIND, m, null, 18), y);
                        y += 4;
                    }
                    blocks.add(new Block(m, start, y - start));
                    y += 5;
                }
            }
            contentH = Math.max(0, y - 5);
        }
        clampScroll();
    }

    private int add(Row r, int y) {
        r.y = y;
        rows.add(r);
        return y + r.h;
    }

    private void clampScroll() {
        float max = Math.max(0, contentH - viewH());
        scrollTarget = MathHelper.clamp(scrollTarget, 0f, max);
        scroll = MathHelper.clamp(scroll, 0f, max);
    }

    private int rowTop(Row r) { return listTop() + r.y - Math.round(scroll); }

    private int rowX(Row r) { return r.kind == Kind.MODULE ? lx : lx + 10; }

    private int rowW(Row r) { return r.kind == Kind.MODULE ? lw - 8 : lw - 8 - 20; }

    private Row rowAt(double mx, double my) {
        if (isConfigs()) return null;
        if (mx < lx || mx > lx + lw || my < listTop() || my > lb) return null;
        for (Row r : rows) {
            int ry = rowTop(r);
            if (my >= ry && my < ry + r.h && mx >= rowX(r) && mx < rowX(r) + rowW(r)) return r;
        }
        return null;
    }

    // ---------------- отрисовка ----------------

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (ThemeModule.blur() && client != null && client.world != null) {
            super.renderBackground(ctx, mouseX, mouseY, delta); // ванильное размытие + затемнение
        } else {
            ctx.fill(0, 0, width, height, 0x59000000);
        }
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        ThemeModule.sync();
        renderBackground(ctx, mx, my, delta);
        int rgb = Ui.ACCENT & 0xFFFFFF;
        float f = MathHelper.clamp((System.currentTimeMillis() - openedAt) / 280f, 0f, 1f);
        ctx.fillGradient(0, 0, width, height, Batch.argb(rgb, 0.10f * f),
                Batch.argb(Batch.mix(rgb, 0x000000, 0.85f), 0.50f * f));

        hits.clear();
        layout();
        scroll += (scrollTarget - scroll) * 0.35f;
        if (Math.abs(scrollTarget - scroll) < 0.4f) scroll = scrollTarget;

        Ui.shadow(ctx, wx, wy, ww, wh, 12);
        Ui.rect(ctx, wx, wy, ww, wh, 12, Batch.argb(Batch.mix(0x160D0D, rgb, 0.07f), 0.90f));
        Ui.rect(ctx, wx + 6, wy + 6, SIDEBAR - 8, wh - 12, 9, Batch.argb(Batch.mix(0x0A0505, rgb, 0.05f), 0.72f));
        Ui.outline(ctx, wx, wy, ww, wh, 12, Batch.argb(Batch.mix(0xFFFFFF, rgb, 0.35f), 0.18f));

        drawSidebar(ctx, mx, my, rgb);
        drawHeader(ctx, rgb);
        ctx.enableScissor(lx - 4, listTop(), lx + lw + 4, lb);
        if (isConfigs()) drawCfgList(ctx, mx, my, rgb);
        else drawModules(ctx, mx, my, rgb);
        ctx.disableScissor();
        if (isConfigs()) drawCfgTop(ctx, mx, my, delta, rgb);
        drawScrollbar(ctx, rgb);

        if (listening != null) {
            Text t = Text.translatable("swinganimations.ui.bind_hint");
            Hud.text(ctx, t, (width - Hud.w(t)) / 2, height - 12, Ui.ACCENT);
        }
    }

    private void scaled(DrawContext ctx, String s, int x, int y, int color, float scale) {
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(x, y, 0f);
        m.scale(scale, scale, 1f);
        Hud.text(ctx, s, 0, 0, color);
        m.pop();
    }

    private void drawSidebar(DrawContext ctx, int mx, int my, int rgb) {
        // логотип и название
        int lgW = 26, lgH = 19;
        ctx.drawTexture(RenderLayer::getGuiTextured, LOGO, wx + 14, wy + 14, 0f, 0f, lgW, lgH, 128, 92, 128, 92);
        String name = "SwingAnimations";
        float s = Math.min(0.95f, (SIDEBAR - 54f) / Math.max(1, Hud.w(name)));
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(wx + 46, wy + 14 + (lgH - 8 * s) / 2f, 0f);
        m.scale(s, s, 1f);
        Hud.text(ctx, name, 0, 0, Ui.ACCENT);
        m.pop();

        int tx = wx + 10;
        int tw = SIDEBAR - 20;
        int ty = wy + 48;
        for (Tab t : tabs) {
            drawTab(ctx, t, tx, ty, tw, mx, my, rgb);
            ty += 25;
        }
        int cy = wy + wh - 10 - 26 - 8 - 22;
        drawTab(ctx, configsTab, tx, cy, tw, mx, my, rgb);

        // плашка игрока
        int px = wx + 10, py = wy + wh - 10 - 26, pw = SIDEBAR - 20;
        Ui.rect(ctx, px, py, pw, 26, 8, Batch.argb(0xFFFFFF, 0.06f));
        String nick = client != null ? client.getSession().getUsername() : "Player";
        Ui.rect(ctx, px + 4, py + 4, 18, 18, 5, 0xFF000000 | rgb);
        String letter = nick.isEmpty() ? "?" : nick.substring(0, 1).toUpperCase(Locale.ROOT);
        Hud.text(ctx, letter, px + 4 + (18 - Hud.w(letter)) / 2, py + 9, 0xFF10121C);
        Hud.text(ctx, Ui.tr().trimToWidth(nick, pw - 34), px + 27, py + 4, Ui.TEXT);
        scaled(ctx, "v" + version(), px + 27, py + 15, Ui.ACCENT, 0.85f);
    }

    private static String version() {
        return FabricLoader.getInstance().getModContainer("swinganimations")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    private void drawTab(DrawContext ctx, Tab t, int x, int y, int w, int mx, int my, int rgb) {
        int h = 22;
        boolean sel = t.id.equals(selected);
        boolean hov = mx >= x && mx < x + w && my >= y && my < y + h;
        float ks = anim("sel:" + t.id, sel);
        float kh = anim("tab:" + t.id, hov && !sel);
        if (ks > 0.01f) {
            Ui.rect(ctx, x, y, w, h, 7, Batch.argb(rgb, 0.17f * ks));
            Ui.rect(ctx, x, y + 4, 2, h - 8, 1, Batch.argb(rgb, ks));
        } else if (kh > 0.01f) {
            Ui.rect(ctx, x, y, w, h, 7, Batch.argb(0xFFFFFF, 0.07f * kh));
        }
        Hud.icon(ctx, t.icon, x + 9, y + (h - 10) / 2, 10);
        int col = sel ? Ui.ACCENT : Ui.TEXT_DIM;
        Hud.text(ctx, tr(t.titleKey.substring("swinganimations.".length()), t.fallback), x + 24, y + (h - 8) / 2, col);
        int n = t == configsTab ? cfgCount() : t.modules.size();
        Hud.textRight(ctx, String.valueOf(n), x + w - 8, y + (h - 8) / 2, sel ? Ui.ACCENT : 0x80BEC3CD);
        hits.add(new Hit(x, y, w, h, () -> selectTab(t)));
    }

    private int cfgCount() { return cfgs.size(); }

    private void selectTab(Tab t) {
        if (t.id.equals(selected)) return;
        selected = t.id;
        scroll = 0f;
        scrollTarget = 0f;
        listening = null;
        if (nameField != null) nameField.setFocused(false);
        if (isConfigs()) refreshCfgs();
    }

    private void drawHeader(DrawContext ctx, int rgb) {
        Tab t = current();
        if (t == null) return;
        Text title = tr(t.titleKey.substring("swinganimations.".length()), t.fallback);
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(lx, wy + 12, 0f);
        m.scale(1.3f, 1.3f, 1f);
        Hud.text(ctx, title, 0, 0, Ui.TEXT);
        m.pop();
        if (isConfigs()) {
            if (status != null) Hud.text(ctx, status, lx, wy + 27, statusColor);
        } else {
            int on = 0;
            for (Module mod : t.modules) if (mod.isEnabled()) on++;
            Hud.text(ctx, tr("ui.modules_count", "Modules: %s, enabled: %s", t.modules.size(), on),
                    lx, wy + 27, Ui.TEXT_DIM);
        }
    }

    private void drawScrollbar(DrawContext ctx, int rgb) {
        int vh = viewH();
        if (contentH <= vh) return;
        int top = listTop();
        int th = Math.max(14, vh * vh / contentH);
        int range = vh - th;
        int ty = top + Math.round(range * (scroll / (contentH - vh)));
        int x = lx + lw - 3;
        Ui.rect(ctx, x, top, 2, vh, 1, Batch.argb(0xFFFFFF, 0.05f));
        Ui.rect(ctx, x, ty, 2, th, 1, Batch.argb(rgb, 0.75f));
    }

    // ---- модули ----

    private void drawModules(DrawContext ctx, int mx, int my, int rgb) {
        int top = listTop();
        for (Block b : blocks) {
            int by = top + b.y - Math.round(scroll);
            if (by + b.h < top || by > lb) continue;
            boolean on = b.module.isEnabled();
            Ui.rect(ctx, lx, by, lw - 8, b.h, 8, on ? Batch.argb(rgb, 0.13f) : Batch.argb(0xFFFFFF, 0.045f));
        }
        for (Row r : rows) {
            int ry = rowTop(r);
            if (ry + r.h < top || ry > lb) continue;
            boolean hov = mx >= rowX(r) && mx < rowX(r) + rowW(r) && my >= ry && my < ry + r.h
                    && my >= top && my < lb;
            drawRow(ctx, r, ry, hov, rgb);
        }
    }

    private void drawRow(DrawContext ctx, Row r, int ry, boolean hov, int rgb) {
        int x = rowX(r);
        int w = rowW(r);
        int ty = ry + (r.h - 8) / 2;
        switch (r.kind) {
            case MODULE -> {
                boolean on = r.module.isEnabled();
                float k = anim(r.module, hov);
                if (k > 0.01f) Ui.rect(ctx, x, ry, w, r.h, 8, Batch.argb(0xFFFFFF, 0.06f * k));
                if (on) Ui.rect(ctx, x, ry + 5, 2, r.h - 10, 1, Ui.ACCENT);
                Text name = Text.translatableWithFallback("swinganimations.module." + r.module.id, r.module.fallbackName);
                Hud.text(ctx, name, x + 10, ty, on ? Ui.ACCENT : Ui.TEXT);
                int right = x + w;
                Hud.text(ctx, expanded.contains(r.module) ? "v" : ">", right - 13, ty, Ui.TEXT_DIM);
                int sw = 22, sh = 11;
                int sx = right - 20 - sw - 4, sy = ry + (r.h - sh) / 2;
                Ui.rect(ctx, sx, sy, sw, sh, 5, on ? Ui.ACCENT : Ui.SWITCH_OFF);
                Ui.rect(ctx, on ? sx + sw - sh + 1 : sx + 1, sy + 1, sh - 2, sh - 2, 4, on ? Ui.TEXT : Ui.TEXT_DIM);
            }
            case BOOL -> {
                Setting.Bool b = (Setting.Bool) r.setting;
                Hud.text(ctx, label(r), x + 4, ty, b.value ? Ui.TEXT : Ui.TEXT_DIM);
                int sw = 20, sh = 9;
                int sx = x + w - 4 - sw, sy = ry + (r.h - sh) / 2;
                Ui.rect(ctx, sx, sy, sw, sh, 4, b.value ? Ui.ACCENT : Ui.SWITCH_OFF);
                Ui.rect(ctx, b.value ? sx + sw - sh + 1 : sx + 1, sy + 1, sh - 2, sh - 2, 3, b.value ? Ui.TEXT : Ui.TEXT_DIM);
            }
            case MODE -> {
                Setting.Mode m = (Setting.Mode) r.setting;
                Hud.text(ctx, label(r), x + 4, ty, Ui.TEXT_DIM);
                Text v = Text.translatableWithFallback("swinganimations.value." + m.get().toLowerCase(Locale.ROOT), m.get());
                int vw = Hud.w(v);
                Ui.rect(ctx, x + w - 4 - vw - 8, ry + 2, vw + 8, r.h - 4, 4, hov ? Batch.argb(rgb, 0.24f) : Batch.argb(rgb, 0.14f));
                Hud.text(ctx, v, x + w - 4 - vw - 4, ty, Ui.ACCENT);
            }
            case NUM -> {
                Setting.Num n = (Setting.Num) r.setting;
                Hud.text(ctx, label(r), x + 4, ry + 2, Ui.TEXT_DIM);
                String v = n.step >= 1f ? String.format(Locale.ROOT, "%.0f", n.value) : String.format(Locale.ROOT, "%.2f", n.value);
                Hud.textRight(ctx, v, x + w - 4, ry + 2, Ui.TEXT);
                int tx = x + 4, tw = w - 8, tr = ry + r.h - 8;
                Ui.rect(ctx, tx, tr, tw, 4, 2, Batch.argb(0xFFFFFF, 0.10f));
                int filled = Math.round(tw * n.fraction());
                if (filled > 0) Ui.rect(ctx, tx, tr, Math.max(4, filled), 4, 2, Ui.ACCENT);
                int kr = (hov || dragNum == r) ? 4 : 3;
                Ui.rect(ctx, tx + filled - kr, tr + 2 - kr, kr * 2, kr * 2, kr, Ui.KNOB);
            }
            case BIND -> {
                Hud.text(ctx, Text.translatable("swinganimations.ui.bind"), x + 4, ty, Ui.TEXT_DIM);
                boolean ls = listening == r.module;
                Text val = ls ? Text.literal("...") : bindText(r.module);
                int vw = Hud.w(val);
                Ui.rect(ctx, x + w - 4 - vw - 8, ry + 2, vw + 8, r.h - 4, 4,
                        ls ? Batch.argb(rgb, 0.30f) : Batch.argb(0xFFFFFF, 0.08f));
                Hud.text(ctx, val, x + w - 4 - vw - 4, ty, ls ? Ui.ACCENT : Ui.TEXT);
            }
            case BUTTON -> {
                Ui.rect(ctx, x, ry + 1, w, r.h - 2, 6, hov ? Batch.argb(rgb, 0.26f) : Batch.argb(rgb, 0.14f));
                Text t = Text.translatable("swinganimations.ui.open_anim");
                Hud.text(ctx, t, x + (w - Hud.w(t)) / 2, ty, Ui.TEXT);
            }
        }
    }

    private static Text label(Row r) {
        return Text.translatableWithFallback("swinganimations.setting." + r.module.id + "." + r.setting.id, r.setting.fallback);
    }

    private static Text bindText(Module m) {
        if (m.bindCode < 0) return Text.translatable("swinganimations.ui.none");
        if (m.bindMouse) {
            return switch (m.bindCode) {
                case 0 -> Text.literal("LMB");
                case 1 -> Text.literal("RMB");
                case 2 -> Text.literal("MMB");
                default -> Text.literal("Mouse " + (m.bindCode + 1));
            };
        }
        return InputUtil.fromKeyCode(m.bindCode, -1).getLocalizedText();
    }

    // ---- конфиги ----

    private void refreshCfgs() {
        List<Cfg> out = new ArrayList<>();
        for (UserConfigs.Entry e : UserConfigs.list()) {
            String d = e.modified() > 0 ? DATE.format(Instant.ofEpochMilli(e.modified()).atZone(ZoneId.systemDefault())) : "";
            out.add(new Cfg(e.name(), d));
        }
        cfgs = out;
    }

    private void button(DrawContext ctx, int x, int y, int w, int h, Text label, int style, boolean inList,
                        int mx, int my, int rgb, Runnable action) {
        // style: 0 обычная, 1 акцент, 2 опасная; inList: кнопка в прокручиваемом списке (не ловим клики вне видимой зоны)
        boolean hov = mx >= x && mx < x + w && my >= y && my < y + h && (!inList || (my >= listTop() && my < lb));
        float k = hov ? 1f : 0f;
        int base = style == 1 ? rgb : (style == 2 ? 0xFF6B6B : 0xFFFFFF);
        float a = style == 1 ? 0.34f + 0.22f * k : 0.08f + 0.10f * k;
        Ui.rect(ctx, x, y, w, h, 6, Batch.argb(base, a));
        int col = style == 2 ? RED : (style == 1 ? Ui.TEXT : (hov ? Ui.TEXT : Ui.TEXT_DIM));
        Hud.text(ctx, label, x + (w - Hud.w(label)) / 2, y + (h - 8) / 2, col);
        if (!inList || (y + h > listTop() && y < lb)) hits.add(new Hit(x, y, w, h, action));
    }

    /** Поле имени, кнопка «Сохранить» и подпись списка (не прокручиваются). */
    private void drawCfgTop(DrawContext ctx, int mx, int my, float delta, int rgb) {
        int saveW = 78;
        Ui.rect(ctx, fieldX, ly, fieldW, 20, 6, Batch.argb(0xFFFFFF, 0.07f));
        Ui.outline(ctx, fieldX, ly, fieldW, 20, 6, nameField.isFocused() ? Ui.ACCENT_LINE : Batch.argb(0xFFFFFF, 0.10f));
        if (nameField.getText().isEmpty() && !nameField.isFocused()) {
            Hud.text(ctx, tr("cfg.name_hint", "Config name..."), fieldX + 8, ly + 6, 0x70BEC3CD);
        }
        nameField.render(ctx, mx, my, delta);
        button(ctx, lx + fieldW + 6, ly, saveW, 20, tr("cfg.save", "Save"), 1, false, mx, my, rgb, this::saveNew);

        scaled(ctx, tr("cfg.saved", "SAVED CONFIGS").getString().toUpperCase(Locale.ROOT) + " \u00B7 " + cfgs.size(),
                lx + 2, ly + 29, 0x90BEC3CD, 0.85f);
        Text open = tr("cfg.open_folder", "Open folder");
        int ow = Hud.w(open);
        boolean hov = mx >= lx + lw - ow - 4 && mx < lx + lw && my >= ly + 26 && my < ly + 38;
        Hud.text(ctx, open, lx + lw - ow - 4, ly + 28, hov ? Ui.ACCENT : 0x90BEC3CD);
        hits.add(new Hit(lx + lw - ow - 4, ly + 26, ow + 4, 12, this::openFolder));
    }

    private void drawCfgList(DrawContext ctx, int mx, int my, int rgb) {
        int top = listTop();
        if (cfgs.isEmpty()) {
            Text t = tr("cfg.empty", "No saved configs yet");
            Hud.text(ctx, t, lx + (lw - Hud.w(t)) / 2, top + 24, 0x80BEC3CD);
            return;
        }
        String active = UserConfigs.active();
        int y = top - Math.round(scroll);
        for (Cfg c : cfgs) {
            if (y + 34 >= top && y <= lb) {
                boolean isActive = c.name.equals(active);
                int cw = lw - 8;
                Ui.rect(ctx, lx, y, cw, 34, 8, isActive ? Batch.argb(rgb, 0.13f) : Batch.argb(0xFFFFFF, 0.045f));
                if (isActive) Ui.outline(ctx, lx, y, cw, 34, 8, Batch.argb(rgb, 0.35f));
                int nameX = lx + 10;
                Hud.text(ctx, c.name, nameX, y + 7, Ui.TEXT);
                if (isActive) {
                    Text badge = tr("cfg.active", "active");
                    int bw = (int) (Hud.w(badge) * 0.85f) + 8;
                    int bx = nameX + Hud.w(c.name) + 6;
                    Ui.rect(ctx, bx, y + 6, bw, 11, 5, Batch.argb(rgb, 0.22f));
                    scaled(ctx, badge.getString(), bx + 4, y + 8, Ui.ACCENT, 0.85f);
                }
                if (!c.date.isEmpty()) {
                    scaled(ctx, tr("cfg.modified", "Modified %s", c.date).getString(), nameX, y + 20, 0x80BEC3CD, 0.85f);
                }
                Text bDel = tr("cfg.delete", "Delete");
                Text bUpd = tr("cfg.update", "Update");
                Text bLoad = tr("cfg.load", "Load");
                int wDel = Hud.w(bDel) + 12, wUpd = Hud.w(bUpd) + 12, wLoad = Hud.w(bLoad) + 12;
                int bx = lx + cw - 8 - wDel;
                int by = y + 9;
                final String nm = c.name;
                button(ctx, bx, by, wDel, 16, bDel, 2, true, mx, my, rgb, () -> deleteCfg(nm));
                bx -= 4 + wUpd;
                button(ctx, bx, by, wUpd, 16, bUpd, 0, true, mx, my, rgb, () -> updateCfg(nm));
                bx -= 4 + wLoad;
                button(ctx, bx, by, wLoad, 16, bLoad, 1, true, mx, my, rgb, () -> loadCfg(nm));
            }
            y += 39;
        }
    }

    private void saveNew() {
        String n = UserConfigs.sanitize(nameField.getText());
        if (n.isEmpty()) {
            setStatus(tr("cfg.need_name", "Enter a config name"), RED);
            return;
        }
        if (UserConfigs.save(n)) {
            setStatus(tr("cfg.saved_ok", "Config \"%s\" saved", n), GREEN);
            nameField.setText("");
            nameDraft = "";
            nameField.setFocused(false);
        } else {
            setStatus(tr("cfg.fail", "Operation failed"), RED);
        }
        refreshCfgs();
    }

    private void loadCfg(String n) {
        if (UserConfigs.load(n)) setStatus(tr("cfg.loaded", "Config \"%s\" loaded", n), GREEN);
        else setStatus(tr("cfg.fail", "Operation failed"), RED);
        refreshCfgs();
    }

    private void updateCfg(String n) {
        if (UserConfigs.save(n)) setStatus(tr("cfg.updated", "Config \"%s\" updated", n), GREEN);
        else setStatus(tr("cfg.fail", "Operation failed"), RED);
        refreshCfgs();
    }

    private void deleteCfg(String n) {
        if (UserConfigs.delete(n)) setStatus(tr("cfg.deleted", "Config \"%s\" deleted", n), GREEN);
        else setStatus(tr("cfg.fail", "Operation failed"), RED);
        refreshCfgs();
    }

    private void openFolder() {
        try {
            Util.getOperatingSystem().open(UserConfigs.folder().toFile());
        } catch (RuntimeException e) {
            setStatus(tr("cfg.fail", "Operation failed"), RED);
        }
    }

    private void setStatus(Text t, int color) {
        status = t;
        statusColor = color;
    }

    // ---------------- ввод ----------------

    private void click() {
        if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 0.6f));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (listening != null) {
            listening.bindMouse = true;
            listening.bindCode = button;
            listening = null;
            ModuleManager.save();
            return true;
        }
        if (button == 0) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (mx >= h.x() && mx < h.x() + h.w() && my >= h.y() && my < h.y() + h.h()) {
                    click();
                    h.action().run();
                    return true;
                }
            }
        }
        if (isConfigs()) {
            boolean inField = mx >= fieldX && mx < fieldX + fieldW && my >= ly && my < ly + 20;
            nameField.setFocused(inField);
            if (inField) nameField.mouseClicked(mx, my, button);
            return true;
        }
        Row r = rowAt(mx, my);
        if (r != null) {
            pressRow = r;
            pressBtn = button;
            pressY = my;
            lastY = my;
            moved = false;
            if (r.kind == Kind.NUM && button == 0) {
                dragNum = r;
                applyNum(r, mx);
            }
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private void applyNum(Row r, double mx) {
        Setting.Num n = (Setting.Num) r.setting;
        int tx = rowX(r) + 4, tw = rowW(r) - 8;
        double f = Math.max(0.0, Math.min(1.0, (mx - tx) / tw));
        n.set((float) (n.min + (n.max - n.min) * f));
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragNum != null) {
            applyNum(dragNum, mx);
            return true;
        }
        if (pressRow != null) {
            if (!moved && Math.abs(my - pressY) > 4) moved = true;
            if (moved) {
                scrollTarget -= (float) (my - lastY);
                scroll = scrollTarget;
                clampScroll();
            }
            lastY = my;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        boolean handled = false;
        if (dragNum != null) {
            dragNum = null;
            ModuleManager.save();
            handled = true;
        } else if (pressRow != null && !moved) {
            activate(pressRow, pressBtn, mx);
            handled = true;
        }
        pressRow = null;
        return handled || super.mouseReleased(mx, my, button);
    }

    private void activate(Row r, int button, double mx) {
        switch (r.kind) {
            case MODULE -> {
                boolean arrow = mx > rowX(r) + rowW(r) - 20;
                if (button == 1 || arrow) {
                    if (!expanded.remove(r.module)) expanded.add(r.module);
                } else if (button == 0) {
                    r.module.toggle();
                    ModuleManager.save();
                }
            }
            case BOOL -> {
                Setting.Bool b = (Setting.Bool) r.setting;
                b.value = !b.value;
                ModuleManager.save();
            }
            case MODE -> {
                ((Setting.Mode) r.setting).next();
                ModuleManager.save();
            }
            case BIND -> {
                if (button == 1) {
                    r.module.bindCode = -1;
                    r.module.bindMouse = false;
                    ModuleManager.save();
                } else {
                    listening = r.module;
                }
            }
            case BUTTON -> {
                if (client != null) client.setScreen(new SettingsScreen(this));
            }
            default -> { }
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (mx >= lx - 6 && mx <= lx + lw + 6) {
            scrollTarget -= (float) (vertical * 26);
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (listening != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                listening.bindCode = -1;
                listening.bindMouse = false;
            } else {
                listening.bindMouse = false;
                listening.bindCode = keyCode;
            }
            listening = null;
            ModuleManager.save();
            return true;
        }
        if (isConfigs() && nameField != null && nameField.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                nameField.setFocused(false);
            } else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                saveNew();
            } else {
                nameField.keyPressed(keyCode, scanCode, modifiers);
                nameDraft = nameField.getText();
            }
            return true;
        }
        if (KeyBinds.OPEN != null && KeyBinds.OPEN.matchesKey(keyCode, scanCode)) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (isConfigs() && nameField != null && nameField.isFocused()) {
            boolean ok = nameField.charTyped(chr, modifiers);
            nameDraft = nameField.getText();
            return ok;
        }
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void removed() {
        ModuleManager.save();
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
