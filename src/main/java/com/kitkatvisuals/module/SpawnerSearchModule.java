package com.kitkatvisuals.module;

import com.kitkatvisuals.gui.SpawnerPanel;
import com.kitkatvisuals.gui.Ui;
import com.kitkatvisuals.util.SpawnerFilter;
import com.kitkatvisuals.util.SpawnerInfo;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SpawnerSearch: в меню аукциона (и в любом контейнере) показывает, что лежит внутри золотого спавнера:
 * какой моб, какой меч и какие на мече зачарования. Показывается в подсказке предмета, короткой подписью на самом слоте
 * и подсветкой слотов по условиям (моб, минимальная острота). Только чтение данных предмета, ничего не отправляется на сервер.
 * Если на вашем сервере данные лежат в непривычном виде, клавиша «Spawner dump» записывает предмет под курсором в файл.
 */
public final class SpawnerSearchModule extends Module {
    private static final String[] MOBS = {"Any", "Zombie", "Skeleton", "Spider", "Cave Spider", "Creeper", "Enderman", "Blaze", "Pig",
            "Cow", "Iron Golem", "Witch", "Slime", "Magma Cube", "Wither Skeleton", "Zombified Piglin", "Guardian"};

    private final Setting.Bool panel = add(new Setting.Bool("panel", "Details panel and filter", true));
    private final Setting.Bool search = add(new Setting.Bool("search", "Highlight matches", true));
    private final Setting.Bool dim = add(new Setting.Bool("dim", "Dim non-matching spawners", true)
            .<Setting.Bool>visibleWhen(() -> this.search.value));
    private final Setting.Bool badge = add(new Setting.Bool("badge", "Short label on the slot", true));
    private final Setting.Bool tooltip = add(new Setting.Bool("tooltip", "Show in item tooltip", false));
    private final Setting.Bool onlyAuction = add(new Setting.Bool("only_auction", "Only in auction menus", false));
    private final Setting.Mode mob = add(new Setting.Mode("highlight_mob", "Mob filter", MOBS));
    private final Setting.Mode match = add(new Setting.Mode("match", "Enchant match", "All", "Any"));
    private final Setting.Num minLevel = add(new Setting.Num("min_level", "Min. enchant level", 1f, 1f, 10f, 1f));
    // Выбранные зачарования («farmer,sharpness»): меняется кнопками в панели, в ClickGUI не показывается.
    private final Setting.Str filter = add(new Setting.Str("filter", "Enchant filter", ""));

    /** Слот под курсором в открытом контейнере (обновляет миксин). */
    public static volatile Slot hovered;

    public SpawnerSearchModule() {
        super("spawner_search", "SpawnerSearch", "player");
        ItemTooltipCallback.EVENT.register((stack, ctx, type, lines) -> {
            try {
                onTooltip(stack, lines);
            } catch (Throwable ignored) {
                // подсказка не должна ломать интерфейс
            }
        });
    }

    public boolean inScope() {
        if (!isEnabled()) return false;
        if (!onlyAuction.value) return true;
        Screen s = MinecraftClient.getInstance().currentScreen;
        if (s == null) return false;
        String t = s.getTitle().getString().toLowerCase(Locale.ROOT);
        return t.contains("аукцион") || t.contains("auction") || t.contains("аук");
    }

    // ---------------- подсказка ----------------

    private void onTooltip(ItemStack stack, List<Text> lines) {
        if (!tooltip.value || !inScope()) return;
        SpawnerInfo i = SpawnerInfo.of(stack);
        if (i == null) return;
        lines.add(Text.literal("Внутри спавнера:").formatted(Formatting.GOLD));
        if (i.mobName != null) lines.add(Text.literal(" Моб: ").formatted(Formatting.GRAY).append(Text.literal(i.mobName).formatted(Formatting.YELLOW)));
        if (i.killsTotal > 0L) lines.add(Text.literal(" Мобов в спавнере: ").formatted(Formatting.GRAY)
                .append(Text.literal(spaced(Math.max(0L, i.mobCount)) + " / " + spaced(i.killsTotal)).formatted(Formatting.YELLOW)));
        if (i.swordName != null) {
            lines.add(Text.literal(" Меч: ").formatted(Formatting.GRAY).append(Text.literal(i.swordName).formatted(Formatting.AQUA)));
            if (i.enchants.isEmpty()) lines.add(Text.literal("  без зачарований").formatted(Formatting.DARK_GRAY));
            for (SpawnerInfo.Ench e : i.enchants) lines.add(Text.literal("  ").append(e.text().copy().formatted(Formatting.LIGHT_PURPLE)));
        }
        if (i.killsTotal > 0L) {
            long killed = Math.max(0L, Math.min(i.killsTotal, i.killsTotal - i.killsRemaining));
            int pct = (int) Math.round(killed * 100.0 / i.killsTotal);
            lines.add(Text.literal(" Убито: ").formatted(Formatting.GRAY)
                    .append(Text.literal(spaced(killed) + " / " + spaced(i.killsTotal) + " (" + pct + "%)").formatted(Formatting.YELLOW)));
        }
        if (i.eggs > 0) lines.add(Text.literal(" Яиц вставлено: ").formatted(Formatting.GRAY).append(Text.literal(String.valueOf(i.eggs)).formatted(Formatting.YELLOW)));
    }

    private static String spaced(long n) {
        return String.format(java.util.Locale.ROOT, "%,d", n).replace(',', ' ');
    }

    // ---------------- фильтр (им управляет панель) ----------------

    public boolean panelEnabled() { return panel.value; }

    private String keysCsv = "";
    private Set<String> keysSet = java.util.Collections.emptySet();

    /** Выбранные зачарования; разбор строки кэшируется (вызывается на каждый слот каждый кадр). */
    public Set<String> keys() {
        String v = filter.value == null ? "" : filter.value;
        if (!v.equals(keysCsv)) {
            keysCsv = v;
            keysSet = SpawnerFilter.parse(v);
        }
        return keysSet;
    }

    public void toggleKey(String key) {
        filter.value = SpawnerFilter.toggle(filter.value, key);
        ModuleManager.save();
    }

    public boolean matchAll() { return match.is("All"); }

    public void toggleMatch() {
        match.next();
        ModuleManager.save();
    }

    public int minLevel() { return (int) minLevel.value; }

    public void cycleMinLevel(int dir) {
        int v = (int) minLevel.value + dir;
        if (v > 5) v = 1;
        if (v < 1) v = 5;
        minLevel.set(v);
        ModuleManager.save();
    }

    public boolean mobIsAny() { return mob.is("Any"); }

    public void cycleMob(int dir) {
        int n = mob.modes.length;
        mob.index = ((mob.index + dir) % n + n) % n;
        ModuleManager.save();
    }

    /** Нужный моб в виде minecraft:zombie либо null, если мобов не фильтруем. */
    private String mobId() {
        return mob.is("Any") ? null : "minecraft:" + mob.get().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    public String mobLabel() {
        String id = mobId();
        if (id == null) return "любой";
        net.minecraft.util.Identifier ident = net.minecraft.util.Identifier.tryParse(id);
        return ident != null && Registries.ENTITY_TYPE.containsId(ident) ? Registries.ENTITY_TYPE.get(ident).getName().getString() : mob.get();
    }

    public boolean filterActive() { return mobId() != null || !keys().isEmpty(); }

    public boolean matches(SpawnerInfo i) {
        return SpawnerFilter.matches(i, keys(), matchAll(), minLevel(), mobId());
    }

    // ---------------- слот: подпись, подсветка, затемнение ----------------

    /** Вызывается миксином после отрисовки слота. */
    public void drawSlot(DrawContext ctx, Slot slot) {
        if (!inScope()) return;
        SpawnerInfo i = SpawnerInfo.of(slot.getStack());
        if (i == null) return;
        int x = slot.x, y = slot.y;
        if (search.value && filterActive()) {
            if (matches(i)) {
                ctx.fill(x, y, x + 16, y + 16, 0x44FFD24A);
                frame(ctx, x, y, 0xFFFFD24A);
            } else if (dim.value) {
                ctx.fill(x, y, x + 16, y + 16, 0xB0101010);
            }
        }
        if (SpawnerPanel.isShown(slot)) frame(ctx, x, y, Ui.ACCENT);
        if (!badge.value) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        ctx.fill(x, y + 6, x + 16, y + 16, 0xA0000000);
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(x + 1f, y + 7f, 300f);
        m.scale(0.5f, 0.5f, 1f);
        if (i.mobName != null) ctx.drawText(mc.textRenderer, cut(i.mobName, 5), 0, 0, 0xFFFFE066, false);
        SpawnerInfo.Ench e = badgeEnchant(i);
        if (e != null) {
            String extra = i.enchants.size() > 1 ? "+" + (i.enchants.size() - 1) : "";
            ctx.drawText(mc.textRenderer, cut(e.name(), 3) + e.level() + extra, 0, 9, e.custom() ? 0xFFFF9BFF : 0xFFB388FF, false);
        } else if (i.swordName != null) {
            ctx.drawText(mc.textRenderer, "меч", 0, 9, 0xFF7FDBFF, false);
        }
        m.pop();
    }

    private static void frame(DrawContext ctx, int x, int y, int color) {
        ctx.fill(x, y, x + 16, y + 1, color);
        ctx.fill(x, y + 15, x + 16, y + 16, color);
        ctx.fill(x, y, x + 1, y + 16, color);
        ctx.fill(x + 15, y, x + 16, y + 16, color);
    }

    /** Что писать на слоте: сначала выбранные в фильтре зачарования, иначе самое сильное. */
    private SpawnerInfo.Ench badgeEnchant(SpawnerInfo i) {
        Set<String> ks = keys();
        for (String k : ks) {
            SpawnerFilter.Entry fe = SpawnerFilter.byKey(k);
            if (fe == null) continue;
            for (SpawnerInfo.Ench e : i.enchants) if (fe.matches(e.shortId(), e.label())) return e;
        }
        return i.best();
    }

    /** Панель рядом с контейнером (зовёт миксин в конце render). */
    public void renderOverlay(HandledScreen<?> screen, DrawContext ctx, int mx, int my, Slot hoveredSlot, int gx, int gy, int gw, int gh) {
        SpawnerPanel.render(screen, ctx, mx, my, hoveredSlot, gx, gy, gw, gh);
    }

    private static String cut(String s, int n) { return s.length() <= n ? s : s.substring(0, n); }

    // ---------------- дамп формата ----------------

    /** Пишет в файл всё, что известно о предмете под курсором (или о первых спавнерах в окне). */
    public void dump(Slot slot) {
        MinecraftClient mc = MinecraftClient.getInstance();
        StringBuilder sb = new StringBuilder("==== ").append(LocalDateTime.now()).append(" ====\n");
        int count = 0;
        if (slot != null && !slot.getStack().isEmpty()) {
            sb.append(describe(slot.getStack()));
            count++;
        } else if (mc.currentScreen instanceof HandledScreen<?> hs) {
            for (Slot s : hs.getScreenHandler().slots) {
                if (count >= 5) break;
                if (!s.getStack().isEmpty() && SpawnerInfo.looksLikeSpawner(s.getStack())) {
                    sb.append(describe(s.getStack()));
                    count++;
                }
            }
        }
        if (count == 0) {
            say(mc, "Наведите курсор на предмет и нажмите клавишу ещё раз");
            return;
        }
        Path file = FabricLoader.getInstance().getGameDir().resolve("kitkatvisuals_spawner_dump.txt");
        try {
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            say(mc, "Записано предметов: " + count + ". Файл: " + file.getFileName());
        } catch (IOException e) {
            say(mc, "Не удалось записать файл: " + e.getMessage());
        }
    }

    private static String describe(ItemStack st) {
        StringBuilder sb = new StringBuilder();
        sb.append("item: ").append(Registries.ITEM.getId(st.getItem())).append(" x").append(st.getCount()).append('\n');
        sb.append("name: ").append(st.getName().getString()).append('\n');
        sb.append("components: ").append(st.getComponents()).append('\n');
        SpawnerInfo i = SpawnerInfo.of(st);
        sb.append("parsed: ").append(i == null ? "nothing found" : "mob=" + i.mobId + " sword=" + i.swordName + " enchants=" + i.enchants).append("\n\n");
        return sb.toString();
    }

    private static void say(MinecraftClient mc, String msg) {
        mc.inGameHud.getChatHud().addMessage(Text.literal("[SpawnerSearch] ").formatted(Formatting.GOLD).append(Text.literal(msg).formatted(Formatting.GRAY)));
    }
}
