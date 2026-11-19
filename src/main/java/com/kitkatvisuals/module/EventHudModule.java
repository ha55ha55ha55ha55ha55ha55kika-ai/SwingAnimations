package com.kitkatvisuals.module;

import com.kitkatvisuals.KitKatVisualsClient;
import com.kitkatvisuals.api.HolyWorldApi;
import com.kitkatvisuals.gui.Hud;
import com.kitkatvisuals.gui.HudLayout;
import com.kitkatvisuals.gui.Ui;
import com.kitkatvisuals.render.Batch;
import com.kitkatvisuals.render.Projection;
import com.kitkatvisuals.util.Rarity;
import com.kitkatvisuals.util.Spring;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EventHud: список активных ивентов HolyWorld (сверху по 6 строк, остальное листается клавишей «Следующая страница»).
 * Фильтр по номеру анархии (0 = все сервера). Координаты берутся из чата в момент объявления ивента: мод ставит метку
 * (маяк в мире и подпись с расстоянием). Формат строки с координатами заранее неизвестен, поэтому парсер терпимый
 * и пишет подходящие строки в лог с префиксом «EventHud chat:», чтобы их можно было прислать и уточнить.
 */
public final class EventHudModule extends Module implements WaypointSource, com.kitkatvisuals.gui.AnarchyPicker {
    private static final int ROWS = 6;
    private static final Pattern XYZ = Pattern.compile("(?i)x\\s*[:=]?\\s*(-?\\d+)\\D{0,8}?y\\s*[:=]?\\s*(-?\\d+)\\D{0,8}?z\\s*[:=]?\\s*(-?\\d+)");
    private static final Pattern XZ = Pattern.compile("(?i)x\\s*[:=]?\\s*(-?\\d+)\\D{0,12}?z\\s*[:=]?\\s*(-?\\d+)");
    private static final Pattern THREE = Pattern.compile("(-?\\d{1,7})\\D{1,12}?(-?\\d{1,3})\\D{1,12}?(-?\\d{1,7})");
    private static final Pattern TWO = Pattern.compile("(-?\\d{2,7})\\D{1,12}?(-?\\d{2,7})");
    /** Сильный признак объявления ивента или координат. */
    private static final Pattern STRONG = Pattern.compile("(?i)ивент|event|коорд|coord|кординат|локаци|появил|начал|спавн");
    /** Сообщения о смерти (в том числе «вы погибли на координатах ...») никогда не считаются ивентом. */
    private static final Pattern DEATH = Pattern.compile("(?i)погиб|умер|смерт|скончал|убит|убил|воскрес|возрод|died|death|slain|you were killed|respawn");
    /** Названия типов ивентов HolyWorld (на случай, если в строке нет слова «ивент»). */
    private static final Pattern NAMES = Pattern.compile("(?i)лихорад|корабл|груз|кубик|посылк|шахт|тыпо|полян|контейнер|крепост");

    private final Setting.Num server = add(new Setting.Num("server", "Anarchy number (0 = all)", 0f, 0f, (float) HolyWorldApi.MAX_ANARCHY, 1f));
    private final Setting.Num rows = add(new Setting.Num("rows", "Rows", 6f, 3f, 10f, 1f));
    private final Setting.Bool navCard = add(new Setting.Bool("nav_card", "Navigation card", true));
    private final Setting.Bool keepMarkers = add(new Setting.Bool("keep_markers", "Keep markers while module is off", true));
    private final Setting.Bool autoMarker = add(new Setting.Bool("auto_marker", "Auto marker from chat", true));
    private final Setting.Num markerTtl = add(new Setting.Num("marker_ttl", "Marker lifetime (min)", 10f, 1f, 60f, 1f));
    private final Setting.Bool autoCommand = add(new Setting.Bool("auto_command", "Auto /event to get coordinates", true));
    private final Setting.Num commandCooldown = add(new Setting.Num("command_cooldown", "/event cooldown (s)", 45f, 15f, 300f, 5f));
    private final Setting.Bool askNow = add(new Setting.Bool("ask_now", "Send /event now", false));
    private final Setting.Bool debugChat = add(new Setting.Bool("debug_chat", "Log chat lines with coords", true));
    private final Setting.Bool openSearch = add(new Setting.Bool("search", "Open anarchy search", false));
    private final Setting.Bool openFilter = add(new Setting.Bool("filter", "Open event filter", false));
    // Скрытые и когда-либо виденные названия ивентов («имя|имя»): правятся в окне фильтра, в ClickGUI не показываются.
    private final Setting.Str hiddenEvents = add(new Setting.Str("hidden_events", "Hidden events", ""));
    private final Setting.Str knownEvents = add(new Setting.Str("known_events", "Known events", ""));
    // Редкости: скрытые и виденные пары «ивент#редкость» (правятся в окне фильтра, в ClickGUI не показываются).
    private final Setting.Str hiddenRares = add(new Setting.Str("hidden_rares", "Hidden rarities", ""));
    private final Setting.Str knownRares = add(new Setting.Str("known_rares", "Known rarities", ""));
    private final Setting.Bool testMarker = add(new Setting.Bool("test_marker", "Test marker (8 blocks ahead)", false));
    private final Setting.Num interval = add(new Setting.Num("interval", "Update interval (s)", 15f, 10f, 60f, 1f));

    private final HudLayout.Win win = HudLayout.win("event_hud");
    private int page;
    private final Map<String, Long> firstSeen = new HashMap<>();
    private final Map<String, Spring> rowAnim = new HashMap<>();
    private final Map<Item, ItemStack> stacks = new HashMap<>();
    private boolean baseline;
    private long trackedAt;
    private final List<Marker> markers = new ArrayList<>();
    private final float[] scr = new float[2];
    /** Ивенты, для которых /event уже отправлялся (чтобы не спамить сервер). */
    private final Set<String> asked = new HashSet<>();
    private long lastCommandAt;
    private long nextAskCheck;
    private long expectUntil;
    private int replyLog;

    private static final class Marker {
        final double x, y, z;
        final String name;
        final long created;
        /** Высота неизвестна (в чате только X и Z): луч рисуется на весь диапазон высот. */
        final boolean tall;

        Marker(double x, double y, double z, String name, long created, boolean tall) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.name = name;
            this.created = created;
            this.tall = tall;
        }
    }

    public EventHudModule() {
        super("event_hud", "EventHud", "hud");
        // Объявления бывают системными сообщениями, в панели над хотбаром или обычным чатом: слушаем все каналы.
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
            if (isEnabled()) onChat(msg.getString(), true);
        });
        ClientReceiveMessageEvents.CHAT.register((msg, signed, sender, params, ts) -> {
            if (isEnabled()) onChat(msg.getString(), false);
        });
    }

    @Override
    public void onEnable() {
        HolyWorldApi.start((int) interval.value);
    }

    @Override
    public void onDisable() {
        HolyWorldApi.stop();
        if (!keepMarkers.value) markers.clear(); // иначе метки доживают свой срок сами
        asked.clear();
    }

    /** Модуль выключили, но метки ещё не истекли: продолжаем показывать их (маяк, подписи, карточка, карта). */
    @Override
    public boolean keepsRunning() { return keepMarkers.value && !markers.isEmpty(); }

    @Override
    public void renderHudIdle(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (navCard.value) com.kitkatvisuals.gui.NavCard.show(ctx, waypoints(), tickDelta);
        if (mc.options.hudHidden) return;
        drawMarkerLabels(ctx, ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight());
    }

    // ---------------- фильтр ивентов ----------------

    private static final int MAX_KNOWN = 80;
    private String hiddenCsv = "";
    private Set<String> hiddenSet = new HashSet<>();
    private long nextLearn;

    private static String enc(String name) { return name == null ? "" : name.replace('|', '/').replace('#', '/').trim(); }

    private static String pair(String name, String rare) { return enc(name) + "#" + (rare == null ? "default" : rare.replace('|', '/').replace('#', '/')); }

    private static Set<String> parseNames(String csv) {
        Set<String> out = new java.util.TreeSet<>();
        if (csv == null) return out;
        for (String part : csv.split("\\|")) if (!part.isBlank()) out.add(part.trim());
        return out;
    }

    /** Скрытые названия; разбор строки кэшируется (вызывается на каждый ивент каждый кадр). */
    private Set<String> hidden() {
        String v = hiddenEvents.value == null ? "" : hiddenEvents.value;
        if (!v.equals(hiddenCsv)) {
            hiddenCsv = v;
            hiddenSet = parseNames(v);
        }
        return hiddenSet;
    }

    /** true: ивент скрыт фильтром (не показывается в списке, счётчиках анархий, метках и не вызывает авто /event). */
    public boolean isHidden(String name) { return hidden().contains(enc(name)); }

    public void toggleEvent(String name) {
        Set<String> h = new java.util.TreeSet<>(hidden());
        String n = enc(name);
        if (!h.remove(n)) h.add(n);
        hiddenEvents.value = String.join("|", h);
        page = 0;
        ModuleManager.save();
    }

    public void showAllEvents() {
        hiddenEvents.value = "";
        hiddenRares.value = "";
        page = 0;
        ModuleManager.save();
    }

    // ---------------- фильтр редкости (для каждого ивента свой) ----------------

    private String hiddenRaresCsv = "";
    private Set<String> hiddenRaresSet = new HashSet<>();

    private Set<String> hiddenRareSet() {
        String v = hiddenRares.value == null ? "" : hiddenRares.value;
        if (!v.equals(hiddenRaresCsv)) {
            hiddenRaresCsv = v;
            hiddenRaresSet = parseNames(v);
        }
        return hiddenRaresSet;
    }

    /** true: этот ивент именно с такой редкостью скрыт фильтром. */
    public boolean isRareHidden(String name, String rare) {
        Set<String> h = hiddenRareSet();
        return !h.isEmpty() && h.contains(pair(name, rare));
    }

    public void toggleRare(String name, String rare) {
        Set<String> h = new java.util.TreeSet<>(hiddenRareSet());
        String k = pair(name, rare);
        if (!h.remove(k)) h.add(k);
        hiddenRares.value = String.join("|", h);
        page = 0;
        ModuleManager.save();
    }

    /** Сколько редкостей этого ивента скрыто. */
    public int hiddenRareCount(String name) {
        String prefix = enc(name) + "#";
        int c = 0;
        for (String k : hiddenRareSet()) if (k.startsWith(prefix)) c++;
        return c;
    }

    /** Редкости ивента: когда-либо виденные (сохраняются) плюс идущие сейчас и участвующие в голосованиях; от низшей к высшей. */
    public List<String> knownRaresOf(String name) {
        String prefix = enc(name) + "#";
        Set<String> out = new java.util.TreeSet<>();
        for (String k : parseNames(knownRares.value)) if (k.startsWith(prefix)) out.add(k.substring(prefix.length()));
        for (String k : hiddenRareSet()) if (k.startsWith(prefix)) out.add(k.substring(prefix.length()));
        for (HolyWorldApi.Event e : HolyWorldApi.latest()) if (enc(e.name()).equals(enc(name))) out.add(e.rare());
        for (HolyWorldApi.Voting v : HolyWorldApi.votings()) {
            for (HolyWorldApi.Candidate c : v.candidates()) if (enc(c.name()).equals(enc(name))) out.add(c.rare());
        }
        List<String> list = new ArrayList<>(out);
        list.sort((a, b) -> {
            int d = Integer.compare(Rarity.rank(a), Rarity.rank(b));
            return d != 0 ? d : a.compareTo(b);
        });
        return list;
    }

    /** Сколько ивентов с этим названием и редкостью идёт сейчас (по всем анархиям, без учёта фильтра). */
    public int activeCount(String name, String rare) {
        String n = enc(name);
        int c = 0;
        for (HolyWorldApi.Event e : HolyWorldApi.latest()) {
            if (enc(e.name()).equals(n) && (e.rare() == null ? "default" : e.rare()).equals(rare)) c++;
        }
        return c;
    }

    public void hideAllEvents() {
        hiddenEvents.value = String.join("|", knownNames());
        page = 0;
        ModuleManager.save();
    }

    /** Все названия ивентов, которые мы видели (сохраняются), плюс идущие сейчас и скрытые. */
    public List<String> knownNames() {
        Set<String> out = parseNames(knownEvents.value);
        out.addAll(hidden());
        for (HolyWorldApi.Event e : HolyWorldApi.latest()) out.add(enc(e.name()));
        for (HolyWorldApi.Voting v : HolyWorldApi.votings()) {
            for (HolyWorldApi.Candidate c : v.candidates()) out.add(enc(c.name()));
        }
        return new ArrayList<>(out);
    }

    /** Редкость ивента по названию из текущего списка API (null, если сейчас такого нет). */
    public String rareOf(String name) {
        String n = enc(name);
        for (HolyWorldApi.Event e : HolyWorldApi.latest()) if (enc(e.name()).equals(n)) return e.rare();
        return null;
    }

    /** Сколько таких ивентов идёт сейчас (по всем анархиям, без учёта фильтра). */
    public int activeCount(String name) {
        String n = enc(name);
        int c = 0;
        for (HolyWorldApi.Event e : HolyWorldApi.latest()) if (enc(e.name()).equals(n)) c++;
        return c;
    }

    /** Запоминает новые названия, чтобы их можно было отфильтровать, даже когда ивента сейчас нет. */
    private void learn(long now) {
        if (now < nextLearn) return;
        nextLearn = now + 2000L;
        Set<String> known = parseNames(knownEvents.value);
        Set<String> rares = parseNames(knownRares.value);
        boolean changed = false;
        for (HolyWorldApi.Event e : HolyWorldApi.latest()) {
            changed |= learnOne(known, rares, e.name(), e.rare());
        }
        for (HolyWorldApi.Voting v : HolyWorldApi.votings()) {
            for (HolyWorldApi.Candidate c : v.candidates()) changed |= learnOne(known, rares, c.name(), c.rare());
        }
        if (changed) {
            knownEvents.value = String.join("|", known);
            knownRares.value = String.join("|", rares);
            ModuleManager.save();
        }
    }

    private static boolean learnOne(Set<String> known, Set<String> rares, String name, String rare) {
        String n = enc(name);
        if (n.isEmpty()) return false;
        boolean changed = false;
        if (known.size() < MAX_KNOWN && known.add(n)) changed = true;
        if (known.contains(n) && rares.size() < MAX_KNOWN * 4 && rares.add(pair(name, rare))) changed = true;
        return changed;
    }

    /** Окно фильтра: включает модуль, если он выключен. Возврат по Esc ведёт на экран, с которого его открыли. */
    public void openFilter() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!isEnabled()) setEnabled(true);
        if (mc.currentScreen instanceof com.kitkatvisuals.gui.EventFilterScreen) return;
        mc.setScreen(new com.kitkatvisuals.gui.EventFilterScreen(mc.currentScreen, this));
    }

    /** Список ивентов API без скрытых фильтром. */
    private List<HolyWorldApi.Event> events() {
        List<HolyWorldApi.Event> all = HolyWorldApi.latest();
        if (hidden().isEmpty() && hiddenRareSet().isEmpty()) return all;
        List<HolyWorldApi.Event> out = new ArrayList<>(all.size());
        for (HolyWorldApi.Event e : all) if (!isHidden(e.name()) && !isRareHidden(e.name(), e.rare())) out.add(e);
        return out;
    }

    public void nextPage() {
        page++;
    }

    /** Выбранная анархия (0 = все). */
    @Override
    public int selected() { return (int) server.value; }

    @Override
    public String searchTitle() { return "Поиск анархии"; }

    @Override
    public String legend() { return "есть ивент"; }

    @Override
    public String filterLabel() { return "Фильтр ивентов"; }

    @Override
    public void filterAction() { openFilter(); }

    @Override
    public void select(int n) {
        server.set(Math.max(0, Math.min(HolyWorldApi.MAX_ANARCHY, n)));
        page = 0;
        ModuleManager.save();
    }

    /** Экран поиска: включает модуль, если он выключен. */
    public void openSearch() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!isEnabled()) setEnabled(true);
        if (mc.currentScreen instanceof com.kitkatvisuals.gui.EventSearchScreen) return;
        mc.setScreen(new com.kitkatvisuals.gui.EventSearchScreen(mc.currentScreen, this));
    }

    @Override
    public void tick(MinecraftClient mc) {
        if (openSearch.value) {
            openSearch.value = false;
            openSearch();
        }
        if (openFilter.value) {
            openFilter.value = false;
            openFilter();
        }
        learn(System.currentTimeMillis());
        if (askNow.value) {
            askNow.value = false;
            sendEventCommand(mc, "manual");
        }
        if (autoCommand.value) autoAsk(mc);
        if (testMarker.value) {
            testMarker.value = false;
            if (mc.player != null) {
                var look = mc.player.getRotationVec(1f);
                addMarker(mc.player.getX() + look.x * 8, mc.player.getY(), mc.player.getZ() + look.z * 8, "Тест", false);
            }
        }
    }

    // ---------------- авто /event ----------------

    /** Анархия, на которой вы играете: определена по боковой панели, иначе выбранная вручную (0 = неизвестно). */
    private int effectiveAnarchy(MinecraftClient mc) {
        int d = com.kitkatvisuals.util.AnarchyDetector.detect(mc);
        return d > 0 ? d : (int) server.value;
    }

    private boolean hasMarker(String name) {
        for (Marker k : markers) if (k.name.equals(name)) return true;
        return false;
    }

    /**
     * Если на вашей анархии идёт ивент, который мы ещё не «спрашивали», отправляет серверу /event, чтобы получить
     * координаты (ответ разбирает onChat и ставит метку). Не чаще раза в «cooldown» секунд и один раз на ивент.
     */
    private void autoAsk(MinecraftClient mc) {
        long now = System.currentTimeMillis();
        if (now - lastCommandAt < (long) (commandCooldown.value * 1000L)) return;
        if (now < nextAskCheck) return; // проверку списка ивентов делаем раз в 2 с, не каждый тик
        nextAskCheck = now + 2000L;
        if (mc.getCurrentServerEntry() == null || mc.player == null || mc.player.getHealth() <= 0f) return;
        if (HolyWorldApi.updatedAt() == 0L) return;
        int a = effectiveAnarchy(mc);
        if (a <= 0) return;
        Set<String> all = new HashSet<>();
        boolean fresh = false;
        for (HolyWorldApi.Event e : events()) {
            String k = key(e);
            all.add(k);
            if (!HolyWorldApi.onAnarchy(e.server(), a)) continue;
            if (!asked.contains(k) && !hasMarker(e.name())) fresh = true;
        }
        asked.retainAll(all);
        if (!fresh) return;
        for (HolyWorldApi.Event e : events()) {
            if (HolyWorldApi.onAnarchy(e.server(), a)) asked.add(key(e));
        }
        sendEventCommand(mc, "auto, anarchy " + a);
    }

    private void sendEventCommand(MinecraftClient mc, String why) {
        if (mc.player == null || mc.player.networkHandler == null) return;
        long now = System.currentTimeMillis();
        lastCommandAt = now;
        expectUntil = now + 8000L;
        replyLog = 0;
        mc.player.networkHandler.sendChatCommand("event");
        if (debugChat.value) KitKatVisualsClient.LOGGER.info("EventHud: sent /event ({})", why);
    }

    /** Если на вашей анархии ровно один ивент, берём его название для метки из ответа без названия. */
    private String fallbackName(String name) {
        if (!"Ивент".equals(name)) return name;
        MinecraftClient mc = MinecraftClient.getInstance();
        int a = effectiveAnarchy(mc);
        String only = null;
        int count = 0;
        for (HolyWorldApi.Event e : events()) {
            if (a > 0 && HolyWorldApi.onAnarchy(e.server(), a)) {
                only = e.name();
                count++;
            }
        }
        return count == 1 ? only : name;
    }

    /** Число ивентов по анархиям 1..36; best[n] = лучший ранг редкости (0 нет, 1 обычный ... 4 легендарный). */
    @Override
    public int[] anarchyCounts(int[] best) {
        int[] counts = new int[HolyWorldApi.MAX_ANARCHY + 1];
        for (HolyWorldApi.Event e : events()) {
            int n = HolyWorldApi.number(e.server());
            if (n < 1 || n > HolyWorldApi.MAX_ANARCHY) continue;
            counts[n]++;
            best[n] = Math.max(best[n], rarityRank(e.rare()));
        }
        return counts;
    }

    public static int rarityRank(String rare) { return Rarity.rank(rare); }

    public static int rankColor(int rank) { return Rarity.color(rank); }

    // ---------------- чат -> метка ----------------

    /**
     * Разбор строки чата. Порядок: X Y Z с подписями, X и Z с подписями, три числа подряд, два числа подряд.
     * Метка ставится только если в строке есть слово про ивент или координаты (или название ивента из API).
     * Подходящие строки пишутся в лог: «EventHud chat:» (метка поставлена) и «EventHud chat?:» (признак есть, координаты не разобраны).
     */
    void onChat(String raw, boolean system) {
        if (raw == null || raw.length() > 600) return;
        boolean expecting = system && System.currentTimeMillis() < expectUntil;
        if (expecting && replyLog++ < 20 && debugChat.value) KitKatVisualsClient.LOGGER.info("EventHud reply: {}", raw);
        boolean named = mentionsKnownEvent(raw) || NAMES.matcher(raw).find();
        if (!named && DEATH.matcher(raw).find()) { // «убит», «смерть» и т. п. без названия ивента: это не объявление
            if (debugChat.value) KitKatVisualsClient.LOGGER.info("EventHud chat (death, skipped): {}", raw);
            return;
        }
        boolean strong = STRONG.matcher(raw).find();
        // Сразу после собственной смерти сервер присылает координаты места смерти: без названия ивента это не ивент.
        if (!named && com.kitkatvisuals.util.DeathTracker.sinceDeathMs() < 20_000L) {
            if (debugChat.value && strong) KitKatVisualsClient.LOGGER.info("EventHud chat (after death, skipped): {}", raw);
            return;
        }
        // Ответ на наш /event: строка с подписанными координатами подходит и без слов «ивент»/«координаты».
        boolean labeled = expecting && (XYZ.matcher(raw).find() || XZ.matcher(raw).find());
        if (!strong && !named && !labeled) return;
        // Обычный игровой чат пропускаем, если в нём нет названия ивента: иначе «ивент 100 200» от игрока ставил бы метку.
        if (!system && !named) return;
        double x, y, z;
        boolean tall = false;
        Matcher m = XYZ.matcher(raw);
        if (m.find()) {
            x = Integer.parseInt(m.group(1));
            y = Integer.parseInt(m.group(2));
            z = Integer.parseInt(m.group(3));
        } else if ((m = XZ.matcher(raw)).find()) {
            x = Integer.parseInt(m.group(1));
            z = Integer.parseInt(m.group(2));
            y = playerY();
            tall = true;
        } else if (strong && (m = THREE.matcher(raw)).find() && inRange(Integer.parseInt(m.group(2)))) {
            x = Integer.parseInt(m.group(1));
            y = Integer.parseInt(m.group(2));
            z = Integer.parseInt(m.group(3));
        } else if (strong && (m = TWO.matcher(raw)).find()) {
            x = Integer.parseInt(m.group(1));
            z = Integer.parseInt(m.group(2));
            y = playerY();
            tall = true;
        } else {
            if (debugChat.value) KitKatVisualsClient.LOGGER.info("EventHud chat?: {}", raw);
            return;
        }
        if (!inRange((int) y)) {
            if (debugChat.value) KitKatVisualsClient.LOGGER.info("EventHud chat?: {}", raw);
            return;
        }
        if (debugChat.value) KitKatVisualsClient.LOGGER.info("EventHud chat: {}", raw);
        if (!autoMarker.value) return;
        addMarker(x + 0.5, y, z + 0.5, fallbackName(currentEventName(raw)), tall);
    }

    private static boolean inRange(int y) { return y >= -64 && y <= 320; }

    private static double playerY() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null ? Math.floor(mc.player.getY()) : 64.0;
    }

    private static boolean mentionsKnownEvent(String raw) {
        for (HolyWorldApi.Event e : HolyWorldApi.latest()) {
            if (raw.contains(e.name())) return true;
        }
        return false;
    }

    private void addMarker(double x, double y, double z, String name, boolean tall) {
        if (isHidden(name) || rareFilteredOut(name)) return; // ивент скрыт фильтром: метку не ставим
        markers.removeIf(k -> k.name.equals(name));
        markers.add(new Marker(x, y, z, name, System.currentTimeMillis(), tall));
        while (markers.size() > 5) markers.remove(0);
    }

    /**
     * true: на вашей анархии (или, если она неизвестна, на любой) такой ивент сейчас идёт, но все его экземпляры
     * имеют скрытую фильтром редкость. Из чата редкость не узнать, поэтому смотрим список API.
     */
    private boolean rareFilteredOut(String name) {
        if (hiddenRareSet().isEmpty()) return false;
        int a = effectiveAnarchy(MinecraftClient.getInstance());
        String n = enc(name);
        int total = 0, shown = 0;
        for (HolyWorldApi.Event e : HolyWorldApi.latest()) {
            if (!enc(e.name()).equals(n)) continue;
            if (a > 0 && !HolyWorldApi.onAnarchy(e.server(), a)) continue;
            total++;
            if (!isRareHidden(e.name(), e.rare())) shown++;
        }
        return total > 0 && shown == 0;
    }

    /** Название берём из текущего списка API: если в строке упомянут ивент, то его, иначе «Ивент». */
    private static String currentEventName(String raw) {
        for (HolyWorldApi.Event e : HolyWorldApi.latest()) {
            if (raw.contains(e.name())) return e.name();
        }
        return "Ивент";
    }

    @Override
    public List<WaypointSource.Waypoint> waypoints() {
        long now = System.currentTimeMillis();
        long ttl = (long) (markerTtl.value * 60_000L);
        List<WaypointSource.Waypoint> out = new ArrayList<>();
        for (Marker k : markers) {
            if (now - k.created <= ttl) out.add(new WaypointSource.Waypoint(k.x, k.y, k.z, k.name, Ui.ACCENT & 0xFFFFFF, false));
        }
        return out;
    }

    @Override
    public boolean wantsWorldRender() { return !markers.isEmpty(); }

    @Override
    public void render(RenderContext c) {
        long now = c.now;
        long ttl = (long) (markerTtl.value * 60_000L);
        markers.removeIf(k -> now - k.created > ttl);
        for (Marker k : markers) drawBeacon(c, k, now);
    }

    /** Вертикальный луч света от точки вверх: снизу ярче, к верху растворяется, слегка пульсирует. */
    private static void drawBeacon(RenderContext c, Marker k, long now) {
        int rgb = Ui.ACCENT & 0xFFFFFF;
        float pulse = 0.8f + 0.2f * (float) Math.sin(now / 350.0);
        double h = k.tall ? 256 : 48;
        double base = k.tall ? k.y - 64 : k.y;
        double r = 0.22;
        Matrix4f m = c.m;
        Batch.begin(true, true);
        Batch b = Batch.triangles();
        for (int i = 0; i < 4; i++) {
            double a0 = i * Math.PI / 2, a1 = (i + 1) * Math.PI / 2;
            double x0 = k.x + Math.cos(a0) * r, z0 = k.z + Math.sin(a0) * r;
            double x1 = k.x + Math.cos(a1) * r, z1 = k.z + Math.sin(a1) * r;
            int lo = Batch.argb(rgb, 0.55f * pulse);
            int hi = Batch.argb(rgb, 0f);
            b.v(m, x0, base, z0, lo); b.v(m, x1, base, z1, lo); b.v(m, x1, base + h, z1, hi);
            b.v(m, x0, base, z0, lo); b.v(m, x1, base + h, z1, hi); b.v(m, x0, base + h, z0, hi);
        }
        b.flush();
        Batch.end();
    }

    // ---------------- HUD ----------------

    private static String key(HolyWorldApi.Event e) { return e.server() + "|" + e.id() + "|" + e.name(); }

    private ItemStack stack(Item item) { return stacks.computeIfAbsent(item, ItemStack::new); }

    /** Иконка по смыслу названия ивента; иначе по редкости. */
    private ItemStack iconFor(HolyWorldApi.Event e) { return iconFor(e.name(), e.rare()); }

    private ItemStack iconFor(String eventName, String rare) {
        String n = eventName.toLowerCase(Locale.ROOT);
        Item it;
        if (n.contains("посыл") || n.contains("груз") || n.contains("ящик") || n.contains("сундук") || n.contains("drop")) it = Items.CHEST;
        else if (n.contains("корабл") || n.contains("пират") || n.contains("ship")) it = Items.OAK_BOAT;
        else if (n.contains("золот") || n.contains("gold")) it = Items.GOLD_INGOT;
        else if (n.contains("алмаз") || n.contains("diamond")) it = Items.DIAMOND;
        else if (n.contains("изумруд") || n.contains("торг") || n.contains("emerald")) it = Items.EMERALD;
        else if (n.contains("метеор") || n.contains("комет") || n.contains("meteor")) it = Items.FIRE_CHARGE;
        else if (n.contains("босс") || n.contains("дракон") || n.contains("boss") || n.contains("dragon")) it = Items.DRAGON_HEAD;
        else if (n.contains("вулкан") || n.contains("лав") || n.contains("volcano")) it = Items.MAGMA_BLOCK;
        else if (n.contains("маяк") || n.contains("beacon")) it = Items.BEACON;
        else if (n.contains("осад") || n.contains("бой") || n.contains("битв") || n.contains("war")) it = Items.IRON_SWORD;
        else if (n.contains("шахт") || n.contains("руд") || n.contains("mine")) it = Items.IRON_PICKAXE;
        else if (n.contains("ферм") || n.contains("урож")) it = Items.WHEAT;
        else if (n.contains("рыб") || n.contains("fish")) it = Items.FISHING_ROD;
        else {
            it = switch (rarityRank(rare)) {
                case 4 -> Items.NETHER_STAR;
                case 3 -> Items.AMETHYST_SHARD;
                case 2 -> Items.HEART_OF_THE_SEA;
                default -> Items.COMPASS;
            };
        }
        return stack(it);
    }

    private static String fit(String s, int maxW) {
        if (Hud.w(s) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && Hud.w(t + "…") > maxW) t = t.substring(0, t.length() - 1);
        return t + "…";
    }

    /** Правая «таблетка» с текстом; возвращает левый край (для следующей слева). */
    private static int pill(DrawContext ctx, int rightX, int y, String text, int bg, int fg) {
        int pw = Hud.w(text) + 10;
        int px = rightX - pw;
        Ui.rect(ctx, px, y, pw, 12, 6, bg);
        Hud.text(ctx, text, px + 5, y + 2, fg);
        return px - 4;
    }

    /** Отмечаем, какие ивенты только что появились (первый ответ API считается «старым»). */
    private void track(long now) {
        long upd = HolyWorldApi.updatedAt();
        if (upd == 0L || upd == trackedAt) return;
        trackedAt = upd;
        Set<String> present = new HashSet<>();
        for (HolyWorldApi.Event e : HolyWorldApi.latest()) {
            String k = key(e);
            present.add(k);
            firstSeen.putIfAbsent(k, baseline ? now : 0L);
        }
        baseline = true;
        firstSeen.keySet().retainAll(present);
        rowAnim.keySet().retainAll(present);
    }

    @Override
    public void renderHud(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (navCard.value) com.kitkatvisuals.gui.NavCard.show(ctx, waypoints(), tickDelta);
        if (mc.options.hudHidden) return;
        int sw = ctx.getScaledWindowWidth();
        int sh = ctx.getScaledWindowHeight();
        long now = System.currentTimeMillis();
        track(now);
        drawMarkerLabels(ctx, sw, sh);

        List<HolyWorldApi.Event> all = filtered();
        // Нет ивентов: вместо пустой надписи показываем идущие голосования за следующий ивент (из /v1/votings).
        List<VRow> votes = all.isEmpty() ? votingRows() : List.of();
        boolean voteMode = !votes.isEmpty();
        boolean edit = HudLayout.editing();
        int perPage = Math.max(3, (int) rows.value);
        int count = voteMode ? votes.size() : all.size();
        int pages = Math.max(1, (count + perPage - 1) / perPage);
        if (page >= pages) page = 0;

        int rowH = 18;
        int x = 8;
        int y = 8 + 16 + 4 + 16 + 4 + 16 + 4;
        int w = 186;
        int h = 28 + perPage * (rowH + 3) - 3 + 20;
        MatrixStack m = ctx.getMatrices();
        HudLayout.push(ctx, win);
        Hud.panel(ctx, x, y, w, h);

        // шапка: иконка-звезда, заголовок, счётчик и выбранная анархия
        int bob = Math.round((float) Math.sin(now / 380.0) * 1.2f);
        m.push();
        m.translate(x + 7, y + 5 + bob, 0f);
        m.scale(0.75f, 0.75f, 1f);
        ctx.drawItem(stack(voteMode ? Items.BOOK : Items.NETHER_STAR), 0, 0);
        m.pop();
        Hud.text(ctx, voteMode ? "Голосование" : "Ивенты", x + 23, y + 7, Ui.TEXT);
        int rx = x + w - 8;
        rx = pill(ctx, rx, y + 5, String.valueOf(count), 0xAA000000 | (Ui.ACCENT & 0xFFFFFF), 0xFF101010);
        int sel = voteMode ? voteAnarchyShown(votes) : (int) server.value;
        rx = pill(ctx, rx, y + 5, sel > 0 ? "A" + sel : "Все", 0x66FFFFFF, Ui.TEXT);

        int ry = y + 24;
        if (count == 0) {
            String msg = edit ? "Пример: Золотая лихорадка" : "Сейчас ивентов нет";
            Hud.text(ctx, msg, x + w / 2 - Hud.w(msg) / 2, ry + 12, Ui.TEXT_DIM);
            if (!edit) {
                for (int i = 0; i < 3; i++) {
                    float t = (now / 700f + i * 0.33f) % 1f;
                    int zx = x + w / 2 + 34 + i * 7 + Math.round(t * 6);
                    int zy = ry + 8 - Math.round(t * 12);
                    Hud.text(ctx, "z", zx, zy, ((int) ((1f - t) * 200f) << 24) | 0xB4BECD);
                }
            }
        } else if (voteMode) {
            ctx.enableScissor(x, y + 22, x + w, y + h - 18);
            int from = page * perPage;
            drawVotes(ctx, votes, from, Math.min(votes.size(), from + perPage), x + 6, ry, w - 12, rowH, now);
            ctx.disableScissor();
        } else {
            ctx.enableScissor(x, y + 22, x + w, y + h - 18);
            int from = page * perPage;
            int to = Math.min(all.size(), from + perPage);
            int chipW = w - 12;
            for (int i = from; i < to; i++) {
                HolyWorldApi.Event e = all.get(i);
                String k = key(e);
                Spring sp = rowAnim.computeIfAbsent(k, kk -> new Spring(0f, 150f, 18f));
                sp.target = 1f;
                sp.update();
                int ox = Math.round((1f - sp.value) * 36f);
                int cx = x + 6 + ox;
                int rank = rarityRank(e.rare());
                int rc = rankColor(rank);
                int rgb = rc & 0xFFFFFF;

                Ui.rect(ctx, cx, ry, chipW, rowH, 6, 0x90171A2A);
                Ui.rect(ctx, cx, ry, chipW, rowH, 6, (rank >= 3 ? 0x30 : 0x16) << 24 | rgb);
                long seen = firstSeen.getOrDefault(k, 0L);
                long age = now - seen;
                boolean fresh = seen > 0L && age < 90_000L;
                if (seen > 0L && age < 6000L) {
                    Ui.rect(ctx, cx, ry, chipW, rowH, 6, ((int) (0x55 * (1f - age / 6000f)) << 24) | (Ui.ACCENT & 0xFFFFFF));
                }
                Ui.outline(ctx, cx, ry, chipW, rowH, 6, (rank >= 2 ? 0x77 : 0x30) << 24 | rgb);
                ctx.fill(cx + 1, ry + 4, cx + 3, ry + rowH - 4, 0xFF000000 | rgb);

                if (rank == 4) { // у легендарных по плашке бежит блик
                    ctx.enableScissor(cx, ry, cx + chipW, ry + rowH);
                    int bx = cx - 14 + (int) ((now / 18) % (chipW + 28));
                    for (int b = 0; b < 8; b++) ctx.fill(bx + b, ry, bx + b + 1, ry + rowH, ((int) (0x28 * (1f - Math.abs(b - 3.5f) / 4f)) << 24) | 0xFFE9A0);
                    ctx.disableScissor();
                }

                m.push();
                m.translate(cx + 8, ry + 3, 0f);
                m.scale(0.75f, 0.75f, 1f);
                ctx.drawItem(iconFor(e), 0, 0);
                m.pop();

                String tag = e.server().replace("LITE_NEW_ANARCHY_", "N").replace("LITE_ANARCHY_", "A");
                int left = pill(ctx, cx + chipW - 6, ry + 3, tag, e.server().contains("NEW") ? 0x99B65CFF : 0x66FFFFFF, Ui.TEXT);
                if (fresh) {
                    float pulse = 0.7f + 0.3f * (float) Math.sin(now / 160.0);
                    left = pill(ctx, left, ry + 3, "NEW", ((int) (0xFF * pulse) << 24) | (Ui.ACCENT & 0xFFFFFF), 0xFF101010);
                }
                String name = fit(e.name(), left - (cx + 24) - 2);
                Hud.text(ctx, name, cx + 24, ry + 5, rank >= 2 ? rc : Ui.TEXT);
                ry += rowH + 3;
            }
            ctx.disableScissor();
        }

        // низ: подсказка про клавишу поиска, точки страниц, свежесть данных
        int fy = y + h - 14;
        ctx.fill(x + 6, fy - 3, x + w - 6, fy - 2, Ui.SEP);
        int dx = x + 8;
        if (KeyBindsAccess.hasSearchKey()) {
            String hint = "[" + KeyBindsAccess.searchKeyName() + "] поиск";
            Hud.text(ctx, hint, dx, fy, Ui.TEXT_DIM);
            dx += Hud.w(hint) + 6;
        }
        if (pages > 1) {
            for (int p = 0; p < pages; p++) {
                Ui.rect(ctx, dx + p * 8, fy + 2, 5, 5, 2, p == page ? Ui.ACCENT : 0x44FFFFFF);
            }
        }
        long upd = HolyWorldApi.updatedAt();
        String st = upd == 0L ? "Загрузка" + ".".repeat((int) ((now / 400) % 4)) : "обновлено " + Math.max(0, (now - upd) / 1000L) + " с";
        Hud.textRight(ctx, st, x + w - 8, fy, upd == 0L ? Ui.TEXT_DIM : 0x889AA3B2);
        HudLayout.pop(ctx, win, x, y, w, h);
    }

    // ---------------- голосования ----------------

    /** Голосования старше этого возраста не показываем: данные устарели (опрос мог встать). */
    private static final long VOTE_STALE_MS = 120_000L;

    /** Строка голосования: вариант, его голоса и общее число голосов на сервере. */
    private record VRow(String tag, String name, String rare, int votes, int total, boolean leader, boolean filtered) { }

    private static String tag(String server) { return server.replace("LITE_NEW_ANARCHY_", "N").replace("LITE_ANARCHY_", "A"); }

    /** Анархия для голосований: выбранная вручную, иначе та, на которой вы играете (0 = неизвестно). */
    private int voteAnarchy() {
        int sel = (int) server.value;
        return sel > 0 ? sel : com.kitkatvisuals.util.AnarchyDetector.detect(MinecraftClient.getInstance());
    }

    /** Что писать в «таблетке» анархии, когда показаны голосования: если строки одной анархии, её номер, иначе «Все». */
    private static int voteAnarchyShown(List<VRow> rows) {
        String t = rows.get(0).tag();
        for (VRow r : rows) if (!r.tag().equals(t)) return 0;
        return t.length() > 1 ? numberOf(t) : 0;
    }

    private static int numberOf(String tag) {
        try {
            return Integer.parseInt(tag.substring(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Строки для HUD. Если на вашей анархии идёт голосование, все его варианты по убыванию голосов. Иначе, когда анархия
     * не выбрана вручную, по строке на каждое идущее голосование (лидер и его голоса). Скрытые фильтром варианты
     * не пропадают (голосование показывается целиком), а притушены.
     */
    private List<VRow> votingRows() {
        List<VRow> out = new ArrayList<>();
        if (!HolyWorldApi.votingsFresh(VOTE_STALE_MS)) return out;
        int a = voteAnarchy();
        List<HolyWorldApi.Voting> mine = new ArrayList<>();
        List<HolyWorldApi.Voting> every = new ArrayList<>();
        for (HolyWorldApi.Voting v : HolyWorldApi.votings()) {
            if (HolyWorldApi.number(v.server()) > HolyWorldApi.MAX_ANARCHY) continue;
            every.add(v);
            if (a > 0 && HolyWorldApi.onAnarchy(v.server(), a)) mine.add(v);
        }
        if (!mine.isEmpty()) {
            for (HolyWorldApi.Voting v : mine) {
                int total = v.total();
                boolean first = true;
                for (HolyWorldApi.Candidate c : v.candidates()) {
                    out.add(new VRow(tag(v.server()), c.name(), c.rare(), c.votes(), total, first && c.votes() > 0, voteHidden(c)));
                    first = false;
                }
            }
            return out;
        }
        if ((int) server.value > 0) return out; // выбрана конкретная анархия, а у неё голосования нет
        for (HolyWorldApi.Voting v : every) {
            HolyWorldApi.Candidate c = v.leader();
            if (c != null) out.add(new VRow(tag(v.server()), c.name(), c.rare(), c.votes(), v.total(), true, voteHidden(c)));
        }
        return out;
    }

    private boolean voteHidden(HolyWorldApi.Candidate c) {
        return isHidden(c.name()) || isRareHidden(c.name(), c.rare());
    }

    private void drawVotes(DrawContext ctx, List<VRow> rows, int from, int to, int cx, int ry, int chipW, int rowH, long now) {
        MatrixStack m = ctx.getMatrices();
        boolean sameTag = true;
        for (VRow r : rows) if (!r.tag().equals(rows.get(0).tag())) sameTag = false;
        for (int i = from; i < to; i++) {
            VRow r = rows.get(i);
            int rank = rarityRank(r.rare());
            int rc = rankColor(rank);
            int rgb = rc & 0xFFFFFF;
            Ui.rect(ctx, cx, ry, chipW, rowH, 6, 0x90171A2A);
            Ui.rect(ctx, cx, ry, chipW, rowH, 6, (rank >= 3 ? 0x30 : 0x16) << 24 | rgb);
            Ui.outline(ctx, cx, ry, chipW, rowH, 6, (r.leader() ? 0x77 : 0x30) << 24 | rgb);
            ctx.fill(cx + 1, ry + 4, cx + 3, ry + rowH - 4, 0xFF000000 | rgb);
            // полоса доли голосов вдоль нижнего края плашки
            float share = r.total() > 0 ? r.votes() / (float) r.total() : 0f;
            int bw = Math.round((chipW - 12) * share);
            if (bw > 0) ctx.fill(cx + 6, ry + rowH - 3, cx + 6 + bw, ry + rowH - 1, (r.leader() ? 0xFF : 0x88) << 24 | rgb);

            m.push();
            m.translate(cx + 8, ry + 3, 0f);
            m.scale(0.75f, 0.75f, 1f);
            ctx.drawItem(iconFor(r.name(), r.rare()), 0, 0);
            m.pop();

            int left = cx + chipW - 6;
            left = pill(ctx, left, ry + 3, String.valueOf(r.votes()),
                    r.leader() ? (0xAA000000 | (Ui.ACCENT & 0xFFFFFF)) : 0x66FFFFFF, r.leader() ? 0xFF101010 : Ui.TEXT);
            if (!sameTag) left = pill(ctx, left, ry + 3, r.tag(), 0x66FFFFFF, Ui.TEXT);
            int tc = rank >= 2 ? rc : Ui.TEXT;
            Hud.text(ctx, fit(r.name(), left - (cx + 24) - 2), cx + 24, ry + 5, r.filtered() ? Ui.fade(tc, 0.4f) : tc);
            ry += rowH + 3;
        }
    }

    /** Небольшой мостик к KeyBinds (чтобы модуль не зависел от порядка инициализации). */
    private static final class KeyBindsAccess {
        static boolean hasSearchKey() {
            return com.kitkatvisuals.input.KeyBinds.EVENT_SEARCH != null && !com.kitkatvisuals.input.KeyBinds.EVENT_SEARCH.isUnbound();
        }

        static String searchKeyName() {
            return com.kitkatvisuals.input.KeyBinds.EVENT_SEARCH.getBoundKeyLocalizedText().getString();
        }
    }

    private void drawMarkerLabels(DrawContext ctx, int sw, int sh) {
        if (!Projection.valid()) return;
        for (Marker k : markers) {
            if (!Projection.project(k.x, k.y + 2, k.z, scr, sw, sh)) continue;
            int px = Math.round(scr[0]);
            int py = Math.round(scr[1]);
            int d = Math.round((float) Projection.distance(k.x, k.y, k.z));
            String text = k.name + "  " + d + " м";
            int tw = Hud.w(text) + 16;
            Hud.chip(ctx, px - tw / 2, py - 22, tw, 16);
            Hud.text(ctx, text, px - tw / 2 + 8, py - 18, Ui.TEXT);
            ctx.fill(px - 1, py - 4, px + 1, py, Ui.ACCENT);
        }
    }

    private List<HolyWorldApi.Event> filtered() {
        if (HudLayout.editing() && HolyWorldApi.latest().isEmpty()) return List.of();
        List<HolyWorldApi.Event> out = new ArrayList<>();
        for (HolyWorldApi.Event e : events()) {
            int num = HolyWorldApi.number(e.server());
            if (num > HolyWorldApi.MAX_ANARCHY) continue; // анархий всего 36: остальные ключи API не показываем
            if (HolyWorldApi.onAnarchy(e.server(), (int) server.value)) out.add(e);
        }
        return out;
    }

    private static int rareColor(String rare) {
        if (rare == null) return Ui.TEXT;
        return switch (rare.toUpperCase(java.util.Locale.ROOT)) {
            case "LEGENDARY" -> 0xFFFFC93C;
            case "EPIC" -> 0xFFB65CFF;
            case "RARE" -> 0xFF5CB8FF;
            default -> Ui.TEXT;
        };
    }
}
