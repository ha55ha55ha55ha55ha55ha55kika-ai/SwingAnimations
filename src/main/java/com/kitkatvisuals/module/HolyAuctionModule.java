package com.kitkatvisuals.module;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Аукцион HolyWorld: курс коинов и цены лотов в коинах, подсветка лотов и яиц дракона, индикатор КД особого компаса.
 * Идеи функций взяты из MaksaiAuctionHW (MIT, автор MaksaiDev; сам мод является портом Auction Integrated от shawyrma),
 * код написан заново под KitKatVisuals. Данные о золотых спавнерах сюда намеренно не входят: за них отвечает SpawnerSearch.
 * Ничего не обходит и не отправляет на сервер: только показывает то, что уже есть в предметах и на экране.
 */
public final class HolyAuctionModule extends Module {
    private static final String RATE_URL = "https://api.holyworld.me/v1/coins-trades?limit=1";
    private static final long RATE_EVERY_MS = 15_000L;
    private static final String NUM = "[0-9](?:[0-9\\s\\u00a0\\u202f.,'_])*";
    private static final Pattern NUMBER = Pattern.compile(NUM);
    private static final Pattern COIN_SUFFIX = Pattern.compile("\\(\\s*(" + NUM + ")", Pattern.UNICODE_CASE);
    private static final Pattern PRICE_VALUE = Pattern.compile("(?iu)(?:цена|стоимость|купить|покупка|продать|продажа|ставку\\s+в|price|cost|buy|sell)\\D{0,32}?(" + NUM + ")");
    private static final Pattern DURATION_PART = Pattern.compile("([0-9]+)\\s*(д(?:\\.|н|ень|ня|ней)?|day|days|ч(?:\\.|ас|аса|асов)?|h|hour|hours|м(?:\\.|ин|инута|минуты|минут)?|min|minute|minutes|с(?:\\.|ек|екунда|екунды|екунд)?|sec|second|seconds)", Pattern.UNICODE_CASE | Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOCK = Pattern.compile("(\\d{1,3}):(\\d{2})(?::(\\d{2}))?");
    private static final Pattern SUFFIX = Pattern.compile("^\\s*(млрд|млн|к{1,3}|k{1,3}|м|m|b)(?![\\p{L}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern RATE_JSON = Pattern.compile("\"rate\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);

    private final Setting.Bool coins = add(new Setting.Bool("coins", "Prices in coins", true));
    private final Setting.Bool compass = add(new Setting.Bool("compass", "Special compass cooldown", true));
    private final Setting.Bool lots = add(new Setting.Bool("lots", "Highlight lots (expiring, bid, outbid)", true));
    private final Setting.Bool cheapest = add(new Setting.Bool("cheapest", "Highlight cheapest lot", true));
    private final Setting.Bool allLots = add(new Setting.Bool("all_lots", "Tint all other lots", false));
    private final Setting.Bool eggs = add(new Setting.Bool("eggs", "Highlight dragon eggs", true));
    private final Setting.Num eggLimit = add(new Setting.Num("egg_limit", "Eggs: many from", 750f, 100f, 5000f, 50f));

    private static volatile double rate;
    private static volatile long lastRateTry;
    private static final AtomicBoolean FETCHING = new AtomicBoolean();

    private final Map<Slot, Integer> fills = new HashMap<>();
    private final Map<Slot, Boolean> pulsing = new HashMap<>();
    private long lastScan;
    private Screen lastScreen;

    public HolyAuctionModule() {
        super("holy_auction", "HolyAuction", "hud");
        ItemTooltipCallback.EVENT.register((stack, ctx, type, lines) -> {
            if (!isEnabled()) return;
            try {
                onTooltip(stack, lines);
            } catch (Throwable ignored) {
                // подсказка не должна ломать интерфейс
            }
        });
    }

    // ------------------------------------------------------------------ экран

    private static String title() {
        Screen s = MinecraftClient.getInstance().currentScreen;
        return s == null ? "" : s.getTitle().getString().toLowerCase(Locale.ROOT);
    }

    private static boolean auctionScreen() {
        Screen s = MinecraftClient.getInstance().currentScreen;
        if (!(s instanceof HandledScreen)) return false;
        String t = title();
        return t.contains("аукцион") || t.contains("торг") || t.contains("став") || t.contains("auction") || t.contains("trade") || t.contains("bid");
    }

    private static boolean tradeScreen() {
        String t = title();
        return t.contains("торг") || t.contains("trade");
    }

    // ------------------------------------------------------------------ курс

    private static void ensureRate() {
        long now = System.currentTimeMillis();
        if (now - lastRateTry < RATE_EVERY_MS || !FETCHING.compareAndSet(false, true)) return;
        lastRateTry = now;
        Thread t = new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) URI.create(RATE_URL + "&_=" + System.currentTimeMillis()).toURL().openConnection(Proxy.NO_PROXY);
                c.setConnectTimeout(2500);
                c.setReadTimeout(2500);
                c.setUseCaches(false);
                c.setRequestProperty("Accept", "application/json");
                c.setRequestProperty("User-Agent", "KitKatVisuals/HolyAuction");
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    char[] buf = new char[2048];
                    int n;
                    while ((n = r.read(buf)) > 0 && sb.length() < 200_000) sb.append(buf, 0, n);
                }
                Matcher m = RATE_JSON.matcher(sb);
                double sum = 0;
                int cnt = 0;
                while (m.find()) {
                    double v = Double.parseDouble(m.group(1));
                    if (v > 0 && !Double.isNaN(v) && !Double.isInfinite(v)) {
                        sum += v;
                        cnt++;
                    }
                }
                if (cnt > 0) rate = sum / cnt;
            } catch (Exception ignored) {
                // нет сети: остаётся прошлый курс
            } finally {
                if (c != null) c.disconnect();
                FETCHING.set(false);
            }
        }, "KitKatVisuals-CoinRate");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ подсказка

    private void onTooltip(ItemStack stack, List<Text> lines) {
        if (stack == null || stack.isEmpty() || lines.isEmpty()) return;
        if (compass.value && isSpecialCompass(stack)) {
            lines.set(0, stack.getName().copy().append(compassStatus(stack)));
        }
        if (!coins.value) return;
        boolean auction = auctionScreen();
        if (!auction && !(MinecraftClient.getInstance().currentScreen instanceof HandledScreen)) return;
        if (auction) {
            ensureRate();
            if (excluded(stack)) return;
            Lot lot = parse(lines, stack.getCount());
            if (lot.isLot()) addLotLines(lines, lot);
        } else {
            addRegularLines(stack, lines);
        }
    }

    private static boolean excluded(ItemStack st) {
        String name = st.getName().getString().toLowerCase(Locale.ROOT);
        boolean turtle = (name.contains("черепаш") || name.contains("turtle")) && (name.contains("мощ") || name.contains("сил") || name.contains("power") || name.contains("strength"));
        return turtle;
    }

    private void addLotLines(List<Text> lines, Lot lot) {
        double r = lot.rate();
        if (lot.price <= 0 || (r <= 0 && lot.coinSuffix <= 0)) return;
        lines.add(Text.literal(""));
        if (r > 0) lines.add(Text.literal("Курс: " + compact(Math.round(r))).formatted(Formatting.GRAY));
        lines.add(Text.literal("Коины: " + coinText(lot.coins())).formatted(Formatting.GOLD));
        if (lot.stack > 0 && r > 0) {
            lines.add(Text.literal("Стак: " + compact(lot.stack) + " / " + coinText(Math.round(lot.stack / r))).formatted(Formatting.YELLOW));
        }
    }

    private void addRegularLines(ItemStack stack, List<Text> lines) {
        boolean priced = false;
        for (Text t : lines) if (PRICE_VALUE.matcher(t.getString()).find()) priced = true;
        if (!priced) return;
        ensureRate();
        double r = rate;
        if (r <= 0 || Double.isNaN(r) || Double.isInfinite(r)) return;
        long stackPrice = 0;
        List<Text> orig = new ArrayList<>(lines);
        for (int i = 0; i < orig.size(); i++) {
            String raw = orig.get(i).getString();
            if (!PRICE_VALUE.matcher(raw).find() || hasCoinWord(raw)) continue;
            long price = priceOf(raw);
            if (price <= 0) continue;
            lines.set(i, orig.get(i).copy().append(Text.literal(" (" + coinText(Math.round(price / r)) + ")").formatted(Formatting.GOLD)));
            if (unitLine(raw) || stackPrice <= 0) stackPrice = stackPriceOf(raw, price, stack.getCount());
        }
        if (stackPrice > 0) {
            lines.add(Text.literal("Цена за стак: " + fmt(stackPrice) + " (" + coinText(Math.round(stackPrice / r)) + ")").formatted(Formatting.YELLOW));
        }
    }

    // ------------------------------------------------------------------ лот

    private static final class Lot {
        long price;
        long stack;
        long coinSuffix;
        long course;
        int seconds;
        boolean outbid;
        boolean bid;
        boolean noBid;
        boolean seller;

        boolean isLot() { return price > 0 || stack > 0 || seconds > 0 || seller; }

        double rate() { return HolyAuctionModule.rate > 0 ? HolyAuctionModule.rate : course; }

        long coins() {
            double r = rate();
            if (price <= 0) return 0;
            if (r <= 0) return coinSuffix;
            long calc = Math.max(1, Math.round(price / r));
            if (coinSuffix <= 0) return calc;
            long tol = Math.max(2, Math.round(calc * 0.15));
            return Math.abs(calc - coinSuffix) <= tol ? coinSuffix : calc;
        }
    }

    private static Lot parse(List<Text> tip, int count) {
        Lot lot = new Lot();
        for (Text t : tip) {
            String line = t.getString();
            String low = line.toLowerCase(Locale.ROOT);
            if (low.contains("текущая цена")) {
                lot.price = moneyAfterColon(line);
                lot.coinSuffix = Math.max(lot.coinSuffix, coinSuffix(line));
            } else if (low.contains("цена за 64")) {
                lot.stack = moneyAfterColon(line);
            } else if (lot.price <= 0 && PRICE_VALUE.matcher(line).find()) {
                lot.price = priceOf(line);
                lot.coinSuffix = Math.max(lot.coinSuffix, coinSuffix(line));
            } else if (isDuration(low) || hasDuration(line)) {
                lot.seconds = seconds(line);
            } else if (low.contains("продавец")) {
                lot.seller = true;
            } else if (low.contains("курс")) {
                lot.course = moneyAfterColon(line);
            }
            lot.outbid |= low.contains("перебит") || low.contains("отставк") || low.contains("outbid");
            lot.noBid |= low.contains("без став") || low.contains("нет став");
            lot.bid |= low.contains("ставк") || low.contains("bid");
        }
        if (lot.stack <= 0 && lot.price > 0 && count > 0) lot.stack = Math.max(1, Math.round(lot.price * (64.0 / count)));
        return lot;
    }

    private static boolean isDuration(String low) {
        return low.contains("истекает") || low.contains("остал") || low.contains("до конц") || low.contains("окончан")
                || low.contains("заверш") || low.contains("time left") || low.contains("expires");
    }

    private static boolean hasDuration(String line) {
        if (CLOCK.matcher(line).find()) return true;
        Matcher m = DURATION_PART.matcher(line.toLowerCase(Locale.ROOT));
        int parts = 0;
        while (m.find()) parts++;
        return parts >= 2;
    }

    private static int seconds(String line) {
        int colon = Math.max(line.indexOf(':'), line.indexOf('：'));
        String part = colon >= 0 && colon + 1 < line.length() ? line.substring(colon + 1) : line;
        Matcher clock = CLOCK.matcher(part);
        if (clock.find()) {
            long a = digits(clock.group(1));
            long b = digits(clock.group(2));
            long total = clock.group(3) == null ? a * 60 + b : a * 3600 + b * 60 + digits(clock.group(3));
            return (int) Math.min(Integer.MAX_VALUE, total);
        }
        Matcher m = DURATION_PART.matcher(part.toLowerCase(Locale.ROOT));
        long total = 0;
        while (m.find()) {
            long v = digits(m.group(1));
            String u = m.group(2);
            if (u.startsWith("д") || u.startsWith("day")) total += v * 86400;
            else if (u.startsWith("ч") || u.equals("h") || u.startsWith("hour")) total += v * 3600;
            else if (u.startsWith("м") || u.startsWith("min")) total += v * 60;
            else total += v;
        }
        if (total > 0) return (int) Math.min(Integer.MAX_VALUE, total);
        return (int) Math.min(Integer.MAX_VALUE, numAfterColon(line));
    }

    /** Число из строки с учётом сокращений: 200кк = 200 000 000, 1.5к = 1500, 3 ккк = 3 000 000 000. Без сокращения: обычное число с пробелами. */
    private static long amount(String text, Matcher m) {
        String num = m.group();
        Matcher sfx = SUFFIX.matcher(text.substring(m.end()));
        if (sfx.find()) {
            String u = sfx.group(1).toLowerCase(Locale.ROOT);
            double mult = u.equals("млрд") || u.equals("b") || u.equals("ккк") || u.equals("kkk") ? 1e9
                    : u.equals("млн") || u.equals("м") || u.equals("m") || u.equals("кк") || u.equals("kk") ? 1e6 : 1e3;
            try {
                double v = Double.parseDouble(num.replaceAll("[\\s\\u00a0\\u202f']", "").replace(',', '.'));
                return Math.round(v * mult);
            } catch (NumberFormatException ignored) {
                // разберём как обычное число
            }
        }
        return digits(num);
    }

    private static long amountAt(String text, String num, int end) {
        Matcher n = NUMBER.matcher(num);
        if (!n.find()) return 0;
        Matcher sfx = SUFFIX.matcher(text.substring(end));
        if (!sfx.find()) return digits(num);
        String u = sfx.group(1).toLowerCase(Locale.ROOT);
        double mult = u.equals("млрд") || u.equals("b") || u.equals("ккк") || u.equals("kkk") ? 1e9
                : u.equals("млн") || u.equals("м") || u.equals("m") || u.equals("кк") || u.equals("kk") ? 1e6 : 1e3;
        try {
            return Math.round(Double.parseDouble(num.replaceAll("[\\s\\u00a0\\u202f']", "").replace(',', '.')) * mult);
        } catch (NumberFormatException e) {
            return digits(num);
        }
    }

    /** Короткая запись денег: 300 000 = 300к, 200 000 000 = 200кк. */
    private static String compact(long v) {
        if (v >= 1_000_000_000L) return trim(v / 1e9) + "ккк";
        if (v >= 1_000_000L) return trim(v / 1e6) + "кк";
        if (v >= 1_000L) return trim(v / 1e3) + "к";
        return Long.toString(v);
    }

    private static String trim(double d) {
        String t = String.format(Locale.ROOT, d >= 100 ? "%.0f" : "%.1f", d);
        return t.endsWith(".0") ? t.substring(0, t.length() - 2) : t;
    }

    private static long digits(String s) {
        try {
            return Long.parseLong(s.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long numAfterColon(String line) {
        int colon = Math.max(line.indexOf(':'), line.indexOf('：'));
        String part = colon >= 0 && colon + 1 < line.length() ? line.substring(colon + 1) : line;
        Matcher m = NUMBER.matcher(part);
        return m.find() ? digits(m.group()) : 0;
    }

    private static long moneyAfterColon(String line) {
        int colon = Math.max(line.indexOf(':'), line.indexOf('：'));
        String part = colon >= 0 && colon + 1 < line.length() ? line.substring(colon + 1) : line;
        Matcher m = NUMBER.matcher(part);
        return m.find() ? amount(part, m) : 0;
    }

    private static long priceOf(String line) {
        int colon = Math.max(line.indexOf(':'), line.indexOf('：'));
        if (colon >= 0 && colon + 1 < line.length()) {
            String part = line.substring(colon + 1);
            Matcher m = NUMBER.matcher(part);
            return m.find() ? amount(part, m) : 0;
        }
        Matcher m = PRICE_VALUE.matcher(line);
        return m.find() ? amountAt(line, m.group(1), m.end(1)) : 0;
    }

    private static long coinSuffix(String line) {
        Matcher m = COIN_SUFFIX.matcher(line);
        return m.find() ? digits(m.group(1)) : 0;
    }

    private static boolean hasCoinWord(String line) {
        String low = line.toLowerCase(Locale.ROOT);
        return low.contains("coin") || low.contains("коин") || line.contains("|?|");
    }

    private static boolean unitLine(String line) {
        String low = line.toLowerCase(Locale.ROOT);
        return low.contains("за 1 ед") || low.contains("за ед") || low.contains("за 1 шт") || low.contains("за шт");
    }

    private static long stackPriceOf(String line, long price, int count) {
        if (unitLine(line)) return Math.max(1, price * 64);
        String low = line.toLowerCase(Locale.ROOT);
        if (low.contains("за 64") || low.contains("за стак") || low.contains("за стек")) return price;
        return count > 0 && count != 64 ? Math.max(1, Math.round(price * (64.0 / count))) : price;
    }

    private static String fmt(long v) {
        return String.format(Locale.ROOT, "%,d", v).replace(',', ' ');
    }

    private static String coinText(double v) {
        if (v <= 0 || Double.isNaN(v) || Double.isInfinite(v)) return "-";
        if (v >= 100) return fmt(Math.round(v));
        return v >= 10 ? String.format(Locale.ROOT, "%.1f", v) : String.format(Locale.ROOT, "%.2f", v);
    }

    // ------------------------------------------------------------------ особый компас

    private static NbtCompound nbt(ItemStack st) {
        try {
            NbtComponent c = st.get(DataComponentTypes.CUSTOM_DATA);
            if (c != null) return c.copyNbt();
        } catch (Throwable ignored) {
            // нет данных
        }
        return new NbtCompound();
    }

    /** Особый компас HolyWorld: по названию или по метке в данных предмета (радар регионов/спавнеров). */
    public static boolean isSpecialCompass(ItemStack st) {
        if (st == null || st.isEmpty()) return false;
        String name = st.getName().getString().toLowerCase(Locale.ROOT);
        if (name.contains("особ") && name.contains("компас")) return true;
        NbtCompound n = nbt(st);
        if (n.isEmpty()) return false;
        if (n.contains("kringeItems")) {
            String type = n.getCompound("kringeItems").getString("type");
            if (type.equalsIgnoreCase("RegionRadar") || type.equalsIgnoreCase("SpawnerRadar")) return true;
        }
        return n.contains("region-radar") && n.getCompound("region-radar").contains("target.world");
    }

    /** Время окончания КД (мс) из данных компаса или null, если сервер его там не пишет. */
    private static Long delayEnd(NbtCompound n) {
        if (n == null || n.isEmpty()) return null;
        for (String k : new String[]{"delay", "daily", "dailyReset", "cooldown", "reset", "time"}) {
            if (!n.contains(k)) continue;
            long v = n.getLong(k);
            if (v <= 0) v = digits(n.getString(k));
            if (v > 0) {
                long nowSec = System.currentTimeMillis() / 1000L;
                if (v < 10_000_000_000L && v > nowSec - 86_400L && v < nowSec + 2_592_000L) v *= 1000L;
                return v;
            }
        }
        for (String k : n.getKeys()) {
            NbtElement e = n.get(k);
            if (e instanceof NbtCompound c) {
                Long v = delayEnd(c);
                if (v != null) return v;
            }
        }
        return null;
    }

    private static float vanillaProgress(ItemStack st) {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player == null ? 0f : mc.player.getItemCooldownManager().getCooldownProgress(st, 0f);
    }

    /** Есть ли КД: у особого компаса HolyWorld в данных 8 полей при КД и 4 без него, иначе смотрим обычный КД предмета. */
    private static boolean onCooldown(ItemStack st) {
        NbtCompound n = nbt(st);
        if (!n.isEmpty()) {
            int c = n.getKeys().size();
            if (c == 8) return true;
            if (c == 4) return false;
        }
        return vanillaProgress(st) > 0f;
    }

    private static long remainingSeconds(ItemStack st) {
        Long end = delayEnd(nbt(st));
        if (end == null) return 0;
        long ms = end - System.currentTimeMillis();
        return ms <= 0 ? 0 : (ms + 999L) / 1000L;
    }

    private static Text compassStatus(ItemStack st) {
        long sec = remainingSeconds(st);
        if (sec > 0) {
            return Text.literal(" [КД: " + duration(sec) + "]").formatted(sec < 7200 ? Formatting.YELLOW : Formatting.RED);
        }
        if (onCooldown(st)) {
            int pct = Math.round(vanillaProgress(st) * 100f);
            return Text.literal(pct > 0 ? " [КД: " + pct + "%]" : " [КД: есть]").formatted(Formatting.RED);
        }
        return Text.literal(" [КД: нет]").formatted(Formatting.GREEN);
    }

    private static String duration(long sec) {
        long h = sec / 3600;
        long m = sec % 3600 / 60;
        long s = sec % 60;
        if (h > 0) return h + "ч " + String.format(Locale.ROOT, "%02d", m) + "м";
        if (m > 0) return m + "м " + String.format(Locale.ROOT, "%02d", s) + "с";
        return s + "с";
    }

    /** Цвет подсветки слота компаса: зелёный, если КД нет; жёлтый, если осталось меньше двух часов; красный в остальных случаях. */
    private static int compassColor(ItemStack st) {
        long sec = remainingSeconds(st);
        if (sec == 0 && !onCooldown(st)) return 0x00FF00;
        return sec > 0 && sec < 7200 ? 0xFFFF00 : 0xFF0000;
    }

    // ------------------------------------------------------------------ подсветка слотов

    private int eggCount(ItemStack st) {
        NbtCompound n = nbt(st);
        return n.isEmpty() ? -1 : findInt(n, "gs_total_eggs");
    }

    private static int findInt(NbtCompound n, String key) {
        if (n.contains(key)) return n.getInt(key);
        for (String k : n.getKeys()) {
            NbtElement e = n.get(k);
            if (e instanceof NbtCompound c) {
                int v = findInt(c, key);
                if (v >= 0) return v;
            }
        }
        return -1;
    }

    private static List<Text> loreOf(ItemStack st) {
        LoreComponent lore = st.get(DataComponentTypes.LORE);
        return lore == null ? new ArrayList<>() : new ArrayList<>(lore.lines());
    }

    private void scan(HandledScreen<?> hs) {
        fills.clear();
        pulsing.clear();
        if (!auctionScreen() || tradeScreen()) return;
        ensureRate();
        Map<Slot, Lot> found = new HashMap<>();
        Map<Item, Double> best = new HashMap<>();
        for (Slot slot : hs.getScreenHandler().slots) {
            if (slot.inventory instanceof PlayerInventory) continue;
            ItemStack st = slot.getStack();
            if (st.isEmpty() || isSpecialCompass(st) || excluded(st)) continue;
            Lot lot = parse(loreOf(st), st.getCount());
            if (!lot.isLot()) continue;
            found.put(slot, lot);
            if (lot.price > 0 && st.getCount() > 0) {
                double unit = (double) lot.price / st.getCount();
                best.merge(st.getItem(), unit, Math::min);
            }
        }
        for (Map.Entry<Slot, Lot> e : found.entrySet()) {
            Slot slot = e.getKey();
            Lot lot = e.getValue();
            ItemStack st = slot.getStack();
            int egg = eggCount(st);
            if (egg >= 0) {
                if (eggs.value) {
                    fills.put(slot, egg < (int) eggLimit.value ? 0xFF55B7 : 0xB65CFF);
                    pulsing.put(slot, true);
                }
                continue;
            }
            boolean cheap = cheapest.value && lot.price > 0 && st.getCount() > 0
                    && (double) lot.price / st.getCount() <= best.getOrDefault(st.getItem(), Double.MAX_VALUE)
                    && countSameItem(found, st.getItem()) > 1;
            if (cheap) {
                fills.put(slot, 0x35E75B);
                pulsing.put(slot, true);
            } else if (lots.value && lot.seconds > 0 && lot.seconds <= 20) {
                fills.put(slot, 0xFFAA00);
            } else if (lots.value && lot.outbid) {
                fills.put(slot, 0xE04040);
            } else if (lots.value && lot.bid && !lot.noBid) {
                fills.put(slot, 0x48E05E);
            } else if (allLots.value) {
                fills.put(slot, 0x30CFFF);
            }
        }
    }

    private static int countSameItem(Map<Slot, Lot> found, Item item) {
        int n = 0;
        for (Slot s : found.keySet()) if (s.getStack().getItem() == item) n++;
        return n;
    }

    /** Вызывается перед отрисовкой предмета в слоте: подкрашивает слот. */
    public void fillSlot(DrawContext ctx, Slot slot) {
        ItemStack st = slot.getStack();
        if (st.isEmpty()) return;
        if (compass.value && isSpecialCompass(st)) {
            ctx.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, 0x66000000 | compassColor(st));
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!(mc.currentScreen instanceof HandledScreen<?> hs)) return;
        long now = System.currentTimeMillis();
        if (hs != lastScreen || now - lastScan > 400) {
            lastScreen = hs;
            lastScan = now;
            scan(hs);
        }
        Integer c = fills.get(slot);
        if (c == null) return;
        int alpha = Boolean.TRUE.equals(pulsing.get(slot)) ? (now / 260L % 2L == 0L ? 130 : 61) : 88;
        ctx.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, (alpha << 24) | (c & 0xFFFFFF));
    }
}
