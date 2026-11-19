package com.kitkatvisuals.module;

import com.kitkatvisuals.api.HolyWorldApi;
import com.kitkatvisuals.util.AnarchyDetector;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AnarchyFinder (HolyWorld): находит анархию с наименьшим онлайном. Публичного API с онлайном по анархиям нет,
 * поэтому данные берутся из меню выбора анархии на самом сервере: каждый предмет-анархия содержит номер в названии
 * и онлайн в описании (lore). Модуль открывает меню командой (настройка «Menu command»), читает все анархии,
 * подсвечивает самую пустую (кроме вашей текущей) и, если включено, сам нажимает на неё.
 * Только чтение предметов и один клик по слоту, как это сделал бы игрок. Если формат меню окажется другим,
 * модуль один раз записывает содержимое меню в файл kitkatvisuals_anarchy_dump.txt.
 */
public final class AnarchyFinderModule extends Module {
    /** Меню считается меню анархий, если в нём распознано не меньше стольких анархий с онлайном. */
    private static final int MIN_ENTRIES = 4;
    private static final long WAIT_MS = 8000L;

    private static final Pattern NUM = Pattern.compile("(?<!\\d)(\\d{1,2})(?!\\d)");
    private static final Pattern PAGE = Pattern.compile("(?i)(стр|страниц|page|назад|вперёд|вперед|next|back)");
    private static final Pattern ONLINE_A = Pattern.compile("(?i)(?:онлайн|online|игроков|игроки|игрок|players|играют)\\D{0,12}(\\d{1,5})");
    private static final Pattern ONLINE_B = Pattern.compile("(?i)(\\d{1,5})\\s*(?:/\\s*\\d+\\s*)?(?:игрок\\p{L}*|чел\\p{L}*|players?|online|онлайн)");

    private final Setting.Mode command = add(new Setting.Mode("command", "Menu command", "an", "anarchy", "anarchies", "lite", "servers"));
    private final Setting.Bool autoJoin = add(new Setting.Bool("auto_join", "Join automatically", true));
    private final Setting.Bool badge = add(new Setting.Bool("badge", "Show online on slots", true));
    private final Setting.Bool find = add(new Setting.Bool("find", "Find least busy anarchy now", false));

    public record Entry(int anarchy, int online, int slotId) { }

    private List<Entry> entries = List.of();
    private Entry best;
    private long lastScan;
    private long armedAt;
    private long armedUntil;
    private boolean menuSeen;
    private boolean joined;
    private boolean dumped;
    private int lastSig;
    private int stable;

    public AnarchyFinderModule() {
        super("anarchy_finder", "AnarchyFinder", "player");
    }

    @Override
    public void onDisable() {
        armedUntil = 0L;
        entries = List.of();
        best = null;
    }

    // ---------------- запуск ----------------

    /** Клавиша / настройка: найти самую пустую анархию. В уже открытом меню анархий действует сразу, иначе открывает меню командой. */
    public void run(MinecraftClient mc) {
        if (mc.player == null || mc.player.networkHandler == null) return;
        if (!isEnabled()) setEnabled(true);
        if (mc.currentScreen instanceof HandledScreen<?> hs) {
            scan(mc, hs);
            if (best != null) {
                finish(mc, hs);
                return;
            }
        }
        long now = System.currentTimeMillis();
        armedAt = now;
        armedUntil = now + WAIT_MS;
        menuSeen = false;
        joined = false;
        dumped = false;
        stable = 0;
        lastSig = 0;
        mc.player.networkHandler.sendChatCommand(command.get());
    }

    @Override
    public void tick(MinecraftClient mc) {
        if (find.value) {
            find.value = false;
            run(mc);
        }
        long now = System.currentTimeMillis();
        if (armedUntil != 0L && now >= armedUntil) {
            armedUntil = 0L;
            if (!joined) {
                if (!menuSeen) say(mc, "Меню не открылось командой /" + command.get() + ". Выберите другую команду в настройках модуля.");
                else say(mc, "Меню открылось, но анархии с онлайном не распознаны. Содержимое записано в kitkatvisuals_anarchy_dump.txt");
            }
        }
        if (!(mc.currentScreen instanceof HandledScreen<?> hs)) {
            entries = List.of();
            best = null;
            return;
        }
        if (now - lastScan < 100L) return;
        lastScan = now;
        scan(mc, hs);
        if (armedUntil == 0L) return;
        menuSeen = true;
        if (best != null) {
            int sig = signature();
            stable = sig == lastSig ? stable + 1 : 0;
            lastSig = sig;
            if (stable >= 2) finish(mc, hs); // меню успело заполниться и не меняется
        } else if (!dumped && now - armedAt > 2500L) {
            dumped = true;
            dump(hs);
        }
    }

    /** Результат найден: сообщаем и (если включено) нажимаем на слот. */
    private void finish(MinecraftClient mc, HandledScreen<?> hs) {
        Entry e = best;
        armedUntil = 0L;
        if (e == null) return;
        if (autoJoin.value && mc.interactionManager != null && mc.player != null) {
            mc.interactionManager.clickSlot(hs.getScreenHandler().syncId, e.slotId(), 0, SlotActionType.PICKUP, mc.player);
            joined = true;
            say(mc, "Анархия " + e.anarchy() + ": онлайн " + e.online() + ". Захожу.");
        } else {
            say(mc, "Меньше всего онлайна на анархии " + e.anarchy() + " (" + e.online() + "). Она подсвечена в меню.");
        }
    }

    // ---------------- чтение меню ----------------

    private void scan(MinecraftClient mc, HandledScreen<?> hs) {
        List<Entry> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (Slot s : hs.getScreenHandler().slots) {
            if (s.inventory instanceof PlayerInventory) continue;
            ItemStack st = s.getStack();
            if (st.isEmpty()) continue;
            int n = anarchyNumber(st.getName().getString());
            if (n <= 0) continue;
            int online = online(st);
            if (online < 0) continue;
            if (!seen.add(n)) continue;
            out.add(new Entry(n, online, s.id));
        }
        entries = out.size() >= MIN_ENTRIES ? out : List.of();
        best = pick(mc, entries);
    }

    /** Анархия с наименьшим онлайном, кроме текущей (если она известна). При равенстве берётся меньший номер. */
    private static Entry pick(MinecraftClient mc, List<Entry> list) {
        if (list.isEmpty()) return null;
        int current = AnarchyDetector.detect(mc);
        Entry b = null;
        for (Entry e : list) {
            if (e.anarchy() == current) continue;
            if (b == null || e.online() < b.online() || (e.online() == b.online() && e.anarchy() < b.anarchy())) b = e;
        }
        return b;
    }

    private int signature() {
        int h = 17;
        for (Entry e : entries) h = h * 31 + e.anarchy() * 100003 + e.online();
        return h;
    }

    static String clean(String s) {
        return s == null ? "" : s.replaceAll("§.", "").trim();
    }

    private static int anarchyNumber(String rawName) {
        String name = clean(rawName);
        if (name.isEmpty() || PAGE.matcher(name).find()) return 0;
        Matcher m = NUM.matcher(name);
        if (!m.find()) return 0;
        int n = Integer.parseInt(m.group(1));
        return n >= 1 && n <= HolyWorldApi.MAX_ANARCHY ? n : 0;
    }

    /** Онлайн из описания предмета; -1, если в нём нет строки с онлайном. */
    private static int online(ItemStack st) {
        LoreComponent lore = st.get(DataComponentTypes.LORE);
        if (lore == null) return -1;
        for (Text line : lore.lines()) {
            String s = clean(line.getString());
            if (s.isEmpty()) continue;
            Matcher a = ONLINE_A.matcher(s);
            if (a.find()) return Integer.parseInt(a.group(1));
            Matcher b = ONLINE_B.matcher(s);
            if (b.find()) return Integer.parseInt(b.group(1));
        }
        return -1;
    }

    // ---------------- слот: подпись и подсветка ----------------

    /** Вызывается миксином после отрисовки слота. */
    public void drawSlot(DrawContext ctx, Slot slot) {
        if (entries.isEmpty()) return;
        Entry e = null;
        for (Entry x : entries) {
            if (x.slotId() == slot.id) {
                e = x;
                break;
            }
        }
        if (e == null) return;
        int x = slot.x, y = slot.y;
        boolean isBest = best != null && best.slotId() == slot.id;
        if (isBest) {
            ctx.fill(x, y, x + 16, y + 16, 0x446BE08B);
            ctx.fill(x, y, x + 16, y + 1, 0xFF6BE08B);
            ctx.fill(x, y + 15, x + 16, y + 16, 0xFF6BE08B);
            ctx.fill(x, y, x + 1, y + 16, 0xFF6BE08B);
            ctx.fill(x + 15, y, x + 16, y + 16, 0xFF6BE08B);
        }
        if (!badge.value) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        ctx.fill(x, y + 10, x + 16, y + 16, 0xA0000000);
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(x + 1f, y + 11f, 300f);
        m.scale(0.5f, 0.5f, 1f);
        ctx.drawText(mc.textRenderer, String.valueOf(e.online()), 0, 0, isBest ? 0xFF6BE08B : 0xFFE8EAF0, false);
        m.pop();
    }

    // ---------------- дамп формата ----------------

    private void dump(HandledScreen<?> hs) {
        StringBuilder sb = new StringBuilder("==== ").append(LocalDateTime.now()).append(" ====\n");
        sb.append("screen: ").append(hs.getTitle().getString()).append('\n');
        int count = 0;
        for (Slot s : hs.getScreenHandler().slots) {
            if (s.inventory instanceof PlayerInventory) continue;
            ItemStack st = s.getStack();
            if (st.isEmpty()) continue;
            if (count++ >= 60) break;
            sb.append("slot ").append(s.id).append(": ").append(st.getName().getString()).append(" x").append(st.getCount()).append('\n');
            LoreComponent lore = st.get(DataComponentTypes.LORE);
            if (lore != null) {
                for (Text line : lore.lines()) sb.append("    | ").append(line.getString()).append('\n');
            }
        }
        sb.append('\n');
        Path file = FabricLoader.getInstance().getGameDir().resolve("kitkatvisuals_anarchy_dump.txt");
        try {
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // не критично
        }
    }

    private static void say(MinecraftClient mc, String msg) {
        mc.inGameHud.getChatHud().addMessage(Text.literal("[AnarchyFinder] ").formatted(Formatting.GOLD).append(Text.literal(msg).formatted(Formatting.GRAY)));
    }
}
