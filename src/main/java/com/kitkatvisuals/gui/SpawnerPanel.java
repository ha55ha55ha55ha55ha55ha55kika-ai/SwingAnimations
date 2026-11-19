package com.kitkatvisuals.gui;

import com.kitkatvisuals.module.ModuleManager;
import com.kitkatvisuals.module.SpawnerSearchModule;
import com.kitkatvisuals.util.SpawnerFilter;
import com.kitkatvisuals.util.SpawnerInfo;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Панель SpawnerSearch рядом с контейнером (аукцион и т.п.):
 *  - карточка спавнера: моб (3D-модель), меч (иконка с блеском) и список зачарований; на всё можно навести мышь и получить подсказку;
 *  - карточка фильтра: зачарования на мече (в том числе «Фармер»), моб, «все/любое» и минимальный уровень.
 * Карточка спавнера «залипает» на последнем спавнере, на который вы наводили курсор, поэтому можно спокойно
 * перевести мышь на панель; закрывается кнопкой X. Только отрисовка и клики по панели, на сервер ничего не уходит.
 */
public final class SpawnerPanel {
    private static final int PW = 190;
    private static final int GAP = 4;
    private static final int DWELL_MS = 130;
    private static final int MAX_ROWS = 8;

    private interface Click { void run(int button); }

    private record Region(int x, int y, int w, int h, Click click, List<Text> tip) {
        boolean has(int mx, int my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    private static final List<Region> REGIONS = new ArrayList<>();
    private static final Map<EntityType<?>, LivingEntity> PREVIEWS = new HashMap<>();
    private static final Map<SpawnerInfo, ItemStack> SWORDS = new IdentityHashMap<>();
    private static net.minecraft.client.world.ClientWorld previewWorld;
    private static boolean previewBroken;

    private static Object lastScreen;
    private static Slot shownSlot;
    private static Slot candidate;
    private static long candidateSince;
    private static int bx, by, bw, bh;
    private static boolean hasBounds;
    private static boolean swallowRelease;

    private SpawnerPanel() {}

    // ---------------- запросы ----------------

    public static boolean isShown(Slot slot) { return slot != null && slot == shownSlot; }

    private static boolean inPanel(double mx, double my) {
        return hasBounds && mx >= bx && mx < bx + bw && my >= by && my < by + bh;
    }

    /** Клик по панели. true: клик поглощён (слот под панелью не трогаем). */
    public static boolean click(double mx, double my, int button) {
        if (!inPanel(mx, my)) return false;
        swallowRelease = true;
        for (int i = REGIONS.size() - 1; i >= 0; i--) {
            Region r = REGIONS.get(i);
            if (r.click() != null && r.has((int) mx, (int) my)) {
                r.click().run(button);
                break;
            }
        }
        return true;
    }

    public static boolean release() {
        boolean s = swallowRelease;
        swallowRelease = false;
        return s;
    }

    // ---------------- отрисовка ----------------

    public static void render(HandledScreen<?> screen, DrawContext ctx, int mx, int my, Slot hovered, int gx, int gy, int gw, int gh) {
        SpawnerSearchModule m = ModuleManager.spawnerSearch;
        REGIONS.clear();
        hasBounds = false;
        if (m == null || !m.isEnabled() || !m.panelEnabled() || !m.inScope()) {
            prevBw = 0;
            return;
        }
        if (screen != lastScreen) {
            lastScreen = screen;
            shownSlot = null;
            candidate = null;
        }

        int total = 0;
        int matching = 0;
        for (Slot s : screen.getScreenHandler().slots) {
            SpawnerInfo i = SpawnerInfo.of(s.getStack());
            if (i == null) continue;
            total++;
            if (m.matches(i)) matching++;
        }
        if (total == 0) {
            prevBw = 0;
            return;
        }

        // выбор спавнера для карточки: курсор должен задержаться на слоте, чтобы быстрый проход не переключал карточку
        long now = System.currentTimeMillis();
        boolean over = inPanelPrev(mx, my);
        if (!over) {
            SpawnerInfo hi = hovered == null ? null : SpawnerInfo.of(hovered.getStack());
            if (hi != null) {
                if (candidate != hovered) {
                    candidate = hovered;
                    candidateSince = now;
                }
                if (shownSlot != hovered && now - candidateSince >= DWELL_MS) shownSlot = hovered;
            } else {
                candidate = null;
            }
        }
        SpawnerInfo info = shownSlot == null ? null : SpawnerInfo.of(shownSlot.getStack());
        if (info == null) shownSlot = null;

        int detailsH = info == null ? 0 : detailsHeight(info);
        int filterH = 138;
        int avail = screen.height - 8;
        boolean both = info != null && detailsH + GAP + filterH <= avail;
        int totalH = info == null ? filterH : (both ? detailsH + GAP + filterH : detailsH);

        int px = gx + gw + 8;
        if (px + PW > screen.width - 4) px = gx - PW - 8;
        if (px < 4) px = Math.max(4, screen.width - PW - 4);
        int py = MathHelper.clamp(gy, 4, Math.max(4, screen.height - totalH - 4));

        bx = px;
        by = py;
        bw = PW;
        bh = totalH;
        hasBounds = true;
        prevBx = bx;
        prevBy = by;
        prevBw = bw;
        prevBh = bh;

        int y = py;
        if (info != null) {
            drawDetails(ctx, m, px, y, detailsH, info, shownSlot.getStack(), mx, my);
            y += detailsH + GAP;
        }
        if (info == null || both) drawFilter(ctx, m, px, y, filterH, matching, total, mx, my);

        for (int i = REGIONS.size() - 1; i >= 0; i--) {
            Region r = REGIONS.get(i);
            if (r.tip() != null && r.has(mx, my)) {
                ctx.drawTooltip(Ui.tr(), r.tip(), mx, my);
                break;
            }
        }
    }

    // курсор над панелью по данным прошлого кадра (позиция панели считается после выбора карточки)
    private static int prevBx, prevBy, prevBw, prevBh;

    private static boolean inPanelPrev(int mx, int my) {
        return prevBw > 0 && mx >= prevBx && mx < prevBx + prevBw && my >= prevBy && my < prevBy + prevBh;
    }

    // ---------------- карточка спавнера ----------------

    private static int detailsHeight(SpawnerInfo info) {
        int n = info.enchants.size();
        int rows = n == 0 ? 1 : Math.min(n, MAX_ROWS) + (n > MAX_ROWS ? 1 : 0);
        return 112 + rows * 14 + 8;
    }

    private static String cut(String s, int px) {
        return Ui.tr().trimToWidth(s, px);
    }

    private static ItemStack swordStack(SpawnerInfo info) {
        ItemStack st = SWORDS.get(info);
        if (st == null) {
            if (SWORDS.size() > 200) SWORDS.clear();
            Identifier id = info.swordId == null ? null : Identifier.tryParse(info.swordId);
            st = id != null && Registries.ITEM.containsId(id) ? new ItemStack(Registries.ITEM.get(id)) : ItemStack.EMPTY;
            if (!st.isEmpty() && !info.enchants.isEmpty()) st.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
            SWORDS.put(info, st);
        }
        return st;
    }

    private static void drawDetails(DrawContext ctx, SpawnerSearchModule m, int x, int y, int h, SpawnerInfo info, ItemStack spawner, int mx, int my) {
        Hud.panel(ctx, x, y, PW, h);

        // шапка
        ctx.drawItem(spawner, x + 6, y + 6);
        String title = info.mobName != null ? info.mobName : "Спавнер";
        Hud.text(ctx, cut(title, 100), x + 28, y + 6, Ui.ACCENT);
        Hud.text(ctx, cut(spawner.getName().getString(), 100), x + 28, y + 17, Ui.TEXT_DIM);

        // закрыть
        int cx = x + PW - 16;
        boolean hc = mx >= cx && mx < cx + 10 && my >= y + 5 && my < y + 15;
        Ui.rect(ctx, cx, y + 5, 10, 10, 3, hc ? 0xFF5A2B2B : 0x66000000);
        Hud.text(ctx, "x", cx + 3, y + 6, Ui.TEXT);
        REGIONS.add(new Region(cx, y + 5, 10, 10, b -> shownSlot = null, List.of(Text.literal("Закрыть карточку"))));

        // «подходит под фильтр»
        if (m.filterActive()) {
            boolean ok = m.matches(info);
            String t = ok ? "Подходит" : "Не подходит";
            int w = Hud.w(t) + 8;
            int px = cx - w - 4;
            Ui.rect(ctx, px, y + 5, w, 10, 3, ok ? 0xFF2E8B57 : 0xFF6B3A3A);
            Hud.text(ctx, t, px + 4, y + 6, Ui.TEXT);
        }
        ctx.fill(x + 6, y + 29, x + PW - 6, y + 30, Ui.SEP);

        // моб
        int mx0 = x + 6;
        int my0 = y + 36;
        Ui.rect(ctx, mx0, my0, 58, 66, 4, 0x40000000);
        Ui.outline(ctx, mx0, my0, 58, 66, 4, Ui.OUTLINE);
        boolean drawn = drawMob(ctx, mx0, my0, 58, 66, info, mx, my);
        if (!drawn) Hud.text(ctx, "?", mx0 + 27, my0 + 28, Ui.TEXT_DIM);
        List<Text> mobTip = new ArrayList<>();
        mobTip.add(Text.literal(info.mobName != null ? info.mobName : "Моб не определён").formatted(Formatting.YELLOW));
        if (info.mobId != null) mobTip.add(Text.literal(info.mobId).formatted(Formatting.DARK_GRAY));
        REGIONS.add(new Region(mx0, my0, 58, 66, null, mobTip));

        // меч
        int sx = x + 72;
        Hud.text(ctx, "Меч", sx, y + 36, Ui.TEXT_DIM);
        ItemStack sword = swordStack(info);
        Ui.rect(ctx, sx, y + 47, 36, 36, 4, 0x40000000);
        Ui.outline(ctx, sx, y + 47, 36, 36, 4, info.enchants.isEmpty() ? Ui.OUTLINE : Ui.ACCENT_LINE);
        if (!sword.isEmpty()) {
            var ms = ctx.getMatrices();
            ms.push();
            ms.translate(sx + 2, y + 49, 0f);
            ms.scale(2f, 2f, 1f);
            ctx.drawItem(sword, 0, 0);
            ms.pop();
        }
        String swordName = info.swordName != null ? info.swordName : "Меч не определён";
        Hud.text(ctx, cut(swordName, PW - 72 - 44), sx + 42, y + 52, Ui.TEXT);
        String cnt = info.enchants.isEmpty() ? "без зачарований" : info.enchants.size() + " " + plural(info.enchants.size());
        Hud.text(ctx, cnt, sx + 42, y + 64, info.enchants.isEmpty() ? Ui.TEXT_DIM : 0xFFB388FF);
        List<Text> swordTip = new ArrayList<>();
        swordTip.add(Text.literal(swordName).formatted(Formatting.AQUA));
        Set<String> keys = m.keys();
        for (SpawnerInfo.Ench e : info.enchants) swordTip.add(enchLine(e, keys, m));
        if (info.enchants.isEmpty()) swordTip.add(Text.literal("без зачарований").formatted(Formatting.DARK_GRAY));
        REGIONS.add(new Region(sx, y + 47, PW - 72 - 6, 36, null, swordTip));

        // зачарования
        Hud.text(ctx, "Зачарования", x + 8, y + 94 + 4, Ui.TEXT_DIM);
        int ry = y + 112;
        int n = info.enchants.size();
        if (n == 0) {
            Ui.rect(ctx, x + 6, ry, PW - 12, 13, 3, 0x18FFFFFF);
            Hud.text(ctx, "ничего нет", x + 10, ry + 3, Ui.TEXT_DIM);
            return;
        }
        int shownRows = Math.min(n, MAX_ROWS);
        for (int i = 0; i < shownRows; i++) {
            drawEnchRow(ctx, m, x, ry + i * 14, info.enchants.get(i), keys, mx, my);
        }
        if (n > MAX_ROWS) {
            int yy = ry + shownRows * 14;
            Hud.text(ctx, "+" + (n - MAX_ROWS) + " ещё", x + 10, yy + 3, Ui.TEXT_DIM);
            List<Text> rest = new ArrayList<>();
            for (int i = MAX_ROWS; i < n; i++) rest.add(enchLine(info.enchants.get(i), keys, m));
            REGIONS.add(new Region(x + 6, yy, PW - 12, 13, null, rest));
        }
    }

    private static String plural(int n) {
        int a = n % 100;
        int b = n % 10;
        if (a >= 11 && a <= 14) return "зачарований";
        if (b == 1) return "зачарование";
        if (b >= 2 && b <= 4) return "зачарования";
        return "зачарований";
    }

    private static SpawnerFilter.Entry entryOf(SpawnerInfo.Ench e) {
        for (SpawnerFilter.Entry fe : SpawnerFilter.ENTRIES) if (fe.matches(e.shortId(), e.label())) return fe;
        return null;
    }

    private static boolean selected(SpawnerInfo.Ench e, Set<String> keys) {
        SpawnerFilter.Entry fe = entryOf(e);
        return fe != null && keys.contains(fe.key());
    }

    private static Text enchLine(SpawnerInfo.Ench e, Set<String> keys, SpawnerSearchModule m) {
        boolean sel = selected(e, keys);
        Text t = e.text().copy().formatted(sel ? Formatting.GOLD : (e.custom() ? Formatting.LIGHT_PURPLE : Formatting.GRAY));
        return sel ? Text.literal("+ ").formatted(Formatting.GOLD).append(t) : t;
    }

    private static void drawEnchRow(DrawContext ctx, SpawnerSearchModule m, int x, int y, SpawnerInfo.Ench e, Set<String> keys, int mx, int my) {
        boolean sel = selected(e, keys);
        boolean hov = mx >= x + 6 && mx < x + PW - 6 && my >= y && my < y + 13;
        int bg = sel ? 0x40FFD24A : (hov ? 0x30FFFFFF : 0x18FFFFFF);
        Ui.rect(ctx, x + 6, y, PW - 12, 13, 3, bg);
        if (sel) ctx.fill(x + 6, y + 2, x + 8, y + 11, 0xFFFFD24A);
        int nameColor = sel ? 0xFFFFD24A : (e.custom() ? 0xFFB388FF : 0xFFE8EEF8);
        String lvl = e.level() >= 1 && e.level() <= 10 ? Text.translatable("enchantment.level." + e.level()).getString() : String.valueOf(e.level());
        int lw = Hud.w(lvl);
        Hud.text(ctx, cut(e.name(), PW - 12 - lw - 46), x + 11, y + 3, nameColor);
        Hud.textRight(ctx, lvl, x + PW - 10, y + 3, nameColor);

        SpawnerFilter.Entry fe = entryOf(e);
        int max = fe != null ? fe.maxLevel() : 5;
        int pips = MathHelper.clamp(Math.max(max, e.level()), 1, 6);
        int pw = pips * 5;
        int px = x + PW - 10 - lw - 4 - pw;
        for (int i = 0; i < pips; i++) {
            ctx.fill(px + i * 5, y + 4, px + i * 5 + 4, y + 9, i < e.level() ? (sel ? 0xFFFFD24A : Ui.ACCENT) : 0x40FFFFFF);
        }

        List<Text> tip = new ArrayList<>();
        tip.add(enchLine(e, keys, m));
        if (e.custom()) {
            tip.add(Text.literal("Особое зачарование сервера").formatted(Formatting.DARK_PURPLE));
        } else if (fe != null) {
            tip.add(Text.literal("Макс. уровень: " + fe.maxLevel()).formatted(Formatting.DARK_GRAY));
        }
        if (sel) tip.add(Text.literal("Входит в ваш фильтр").formatted(Formatting.GOLD));
        REGIONS.add(new Region(x + 6, y, PW - 12, 13, null, tip));
    }

    // ---------------- моб ----------------

    private static boolean drawMob(DrawContext ctx, int x, int y, int w, int h, SpawnerInfo info, int mx, int my) {
        if (info.mobId == null) return false;
        Identifier id = Identifier.tryParse(info.mobId);
        if (id == null || !Registries.ENTITY_TYPE.containsId(id)) return false;
        EntityType<?> type = Registries.ENTITY_TYPE.get(id);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!previewBroken && mc.world != null) {
            try {
                if (previewWorld != mc.world) {
                    PREVIEWS.clear();
                    previewWorld = mc.world;
                }
                LivingEntity e = PREVIEWS.get(type);
                if (e == null && !PREVIEWS.containsKey(type)) {
                    Entity created = type.create(mc.world, SpawnReason.COMMAND);
                    e = created instanceof LivingEntity le ? le : null;
                    PREVIEWS.put(type, e);
                }
                if (e != null) {
                    float eh = Math.max(e.getHeight(), 0.4f);
                    float ew = Math.max(e.getWidth(), 0.3f);
                    float size = MathHelper.clamp(46f / Math.max(eh, ew * 0.9f), 8f, 44f);
                    InventoryScreen.drawEntity(ctx, x, y, x + w, y + h, (int) size, 0.0625f, mx, my, e);
                    return true;
                }
            } catch (Throwable t) {
                previewBroken = true;
            }
        }
        SpawnEggItem egg = SpawnEggItem.forEntity(type);
        if (egg != null) {
            var ms = ctx.getMatrices();
            ms.push();
            ms.translate(x + w / 2f - 12f, y + h / 2f - 12f, 0f);
            ms.scale(1.5f, 1.5f, 1f);
            ctx.drawItem(new ItemStack(egg), 0, 0);
            ms.pop();
            return true;
        }
        return false;
    }

    // ---------------- карточка фильтра ----------------

    private static void button(DrawContext ctx, int x, int y, int w, int h, String label, boolean on, int mx, int my, Click click, List<Text> tip) {
        boolean hov = mx >= x && mx < x + w && my >= y && my < y + h;
        int bg = on ? 0x55FFD24A : (hov ? Ui.FIELD_HOVER : Ui.FIELD);
        Ui.rect(ctx, x, y, w, h, 3, bg);
        if (on) Ui.outline(ctx, x, y, w, h, 3, 0xFFFFD24A);
        int tw = Hud.w(label);
        Hud.text(ctx, label, x + Math.max(3, (w - tw) / 2), y + (h - 8) / 2, on ? 0xFFFFE08A : Ui.TEXT);
        REGIONS.add(new Region(x, y, w, h, click, tip));
    }

    private static void drawFilter(DrawContext ctx, SpawnerSearchModule m, int x, int y, int h, int matching, int total, int mx, int my) {
        Hud.panel(ctx, x, y, PW, h);
        Hud.text(ctx, "Фильтр спавнеров", x + 8, y + 6, Ui.ACCENT);
        String cnt = m.filterActive() ? matching + " / " + total : String.valueOf(total);
        Hud.textRight(ctx, cnt, x + PW - 8, y + 6, !m.filterActive() ? Ui.TEXT_DIM : (matching > 0 ? 0xFF7CE38B : 0xFFFF8A8A));
        ctx.fill(x + 6, y + 17, x + PW - 6, y + 18, Ui.SEP);

        Set<String> keys = m.keys();
        int cw = (PW - 12 - 4) / 2;
        int cy = y + 22;
        int idx = 0;
        for (SpawnerFilter.Entry e : SpawnerFilter.ENTRIES) {
            int col = idx % 2;
            int row = idx / 2;
            int bx0 = x + 6 + col * (cw + 4);
            int by0 = cy + row * 15;
            boolean on = keys.contains(e.key());
            String label = e.title().getString();
            if (e.custom()) label = label + " *";
            final String key = e.key();
            List<Text> tip = new ArrayList<>();
            tip.add(e.title().copy().formatted(on ? Formatting.GOLD : Formatting.WHITE));
            tip.add(Text.literal(e.custom() ? "Серверное зачарование" : "Ванильное зачарование").formatted(Formatting.DARK_GRAY));
            tip.add(Text.literal(on ? "Нажмите, чтобы убрать из фильтра" : "Нажмите, чтобы искать по нему").formatted(Formatting.GRAY));
            button(ctx, bx0, by0, cw, 13, cut(label, cw - 6), on, mx, my, b -> m.toggleKey(key), tip);
            idx++;
        }
        int rows = (SpawnerFilter.ENTRIES.size() + 1) / 2;
        int ry = cy + rows * 15 + 3;

        button(ctx, x + 6, ry, PW - 12, 14, "Моб: " + m.mobLabel(), !m.mobIsAny(), mx, my,
                b -> m.cycleMob(b == 1 ? -1 : 1), List.of(Text.literal("ЛКМ: следующий моб, ПКМ: предыдущий").formatted(Formatting.GRAY)));
        int by1 = ry + 17;
        int hw = (PW - 12 - 4) / 2;
        button(ctx, x + 6, by1, hw, 14, m.matchAll() ? "Все выбранные" : "Любое из выбранных", false, mx, my,
                b -> m.toggleMatch(), List.of(Text.literal("Нужны все выбранные зачарования или хотя бы одно").formatted(Formatting.GRAY)));
        button(ctx, x + 6 + hw + 4, by1, hw, 14, "Уровень от " + m.minLevel(), m.minLevel() > 1, mx, my,
                b -> m.cycleMinLevel(b == 1 ? -1 : 1), List.of(Text.literal("ЛКМ: больше, ПКМ: меньше").formatted(Formatting.GRAY)));
    }
}
