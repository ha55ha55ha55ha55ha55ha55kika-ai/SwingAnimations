package com.swinganimations.module;

import com.swinganimations.util.SpawnerInfo;
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

/**
 * SpawnerSearch: в меню аукциона (и в любом контейнере) показывает, что лежит внутри золотого спавнера:
 * какой моб, какой меч и какие на мече зачарования. Показывается в подсказке предмета, короткой подписью на самом слоте
 * и подсветкой слотов по условиям (моб, минимальная острота). Только чтение данных предмета, ничего не отправляется на сервер.
 * Если на вашем сервере данные лежат в непривычном виде, клавиша «Spawner dump» записывает предмет под курсором в файл.
 */
public final class SpawnerSearchModule extends Module {
    private static final String[] MOBS = {"Any", "Zombie", "Skeleton", "Spider", "Cave Spider", "Creeper", "Enderman", "Blaze", "Pig",
            "Cow", "Iron Golem", "Witch", "Slime", "Magma Cube", "Wither Skeleton", "Zombified Piglin", "Guardian"};

    private final Setting.Bool tooltip = add(new Setting.Bool("tooltip", "Show in item tooltip", true));
    private final Setting.Bool badge = add(new Setting.Bool("badge", "Short label on the slot", true));
    private final Setting.Bool onlyAuction = add(new Setting.Bool("only_auction", "Only in auction menus", false));
    private final Setting.Bool highlight = add(new Setting.Bool("highlight", "Highlight matching spawners", false));
    private final Setting.Mode highlightMob = add(new Setting.Mode("highlight_mob", "Highlight mob", MOBS)
            .<Setting.Mode>visibleWhen(() -> this.highlight.value));
    private final Setting.Num minSharpness = add(new Setting.Num("min_sharpness", "Highlight min. Sharpness", 0f, 0f, 7f, 1f)
            .<Setting.Num>visibleWhen(() -> this.highlight.value));

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

    private boolean inScope() {
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
        if (i.swordName != null) {
            lines.add(Text.literal(" Меч: ").formatted(Formatting.GRAY).append(Text.literal(i.swordName).formatted(Formatting.AQUA)));
            if (i.enchants.isEmpty()) lines.add(Text.literal("  без зачарований").formatted(Formatting.DARK_GRAY));
            for (SpawnerInfo.Ench e : i.enchants) lines.add(Text.literal("  ").append(e.text().copy().formatted(Formatting.LIGHT_PURPLE)));
        }
    }

    // ---------------- слот: подпись и подсветка ----------------

    private boolean matches(SpawnerInfo i) {
        boolean any = false;
        if (!highlightMob.is("Any")) {
            any = true;
            String want = "minecraft:" + highlightMob.get().toLowerCase(Locale.ROOT).replace(' ', '_');
            if (i.mobId == null || !i.mobId.equals(want)) return false;
        }
        if (minSharpness.value > 0f) {
            any = true;
            if (i.level("sharpness") < (int) minSharpness.value) return false;
        }
        return any;
    }

    /** Вызывается миксином после отрисовки слота. */
    public void drawSlot(DrawContext ctx, Slot slot) {
        if (!inScope() || (!badge.value && !highlight.value)) return;
        SpawnerInfo i = SpawnerInfo.of(slot.getStack());
        if (i == null) return;
        if (highlight.value && matches(i)) {
            int x = slot.x, y = slot.y;
            ctx.fill(x, y, x + 16, y + 16, 0x44FFD24A);
            ctx.fill(x, y, x + 16, y + 1, 0xFFFFD24A);
            ctx.fill(x, y + 15, x + 16, y + 16, 0xFFFFD24A);
            ctx.fill(x, y, x + 1, y + 16, 0xFFFFD24A);
            ctx.fill(x + 15, y, x + 16, y + 16, 0xFFFFD24A);
        }
        if (!badge.value) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        ctx.fill(slot.x, slot.y + 6, slot.x + 16, slot.y + 16, 0xA0000000);
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(slot.x + 1f, slot.y + 7f, 300f);
        m.scale(0.5f, 0.5f, 1f);
        if (i.mobName != null) ctx.drawText(mc.textRenderer, cut(i.mobName, 5), 0, 0, 0xFFFFE066, false);
        SpawnerInfo.Ench e = i.best();
        if (e != null) {
            String name = e.text().getString();
            int sp = name.lastIndexOf(' ');
            String base = sp > 0 ? name.substring(0, sp) : name;
            String extra = i.enchants.size() > 1 ? "+" + (i.enchants.size() - 1) : "";
            ctx.drawText(mc.textRenderer, cut(base, 3) + e.level() + extra, 0, 9, 0xFFB388FF, false);
        } else if (i.swordName != null) {
            ctx.drawText(mc.textRenderer, "меч", 0, 9, 0xFF7FDBFF, false);
        }
        m.pop();
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
        Path file = FabricLoader.getInstance().getGameDir().resolve("swinganimations_spawner_dump.txt");
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
