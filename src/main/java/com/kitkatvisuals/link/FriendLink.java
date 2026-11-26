package com.kitkatvisuals.link;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kitkatvisuals.KitKatVisualsClient;
import com.kitkatvisuals.event.EventBus;
import com.kitkatvisuals.event.NotificationEvent;
import com.kitkatvisuals.module.ClientLinkModule;
import com.kitkatvisuals.module.FriendMarksModule;
import com.kitkatvisuals.module.ModuleManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Друзья + /pronoob + общие метки поверх HTTP-реле (swing_relay.py v3).
 * Раз в ~3 с шлёт на реле POST /sync (где я, сервер, измерение, координаты) и получает друзей, входящие запросы, метки.
 * Весь сетевой код идёт в отдельном потоке; в главный поток результат возвращается через MinecraftClient.execute.
 */
public final class FriendLink {
    private FriendLink() {}

    // ---------------------------------------------------------------- модели
    public record Friend(UUID id, String name, boolean online, String server, String anarchy) {}
    public record Request(UUID from, String name, long expiresAt) {}
    public record Mark(String id, UUID owner, String ownerName, String server, String dim,
                       double x, double y, double z, String label, boolean mine, String anarchy) {}
    public record Online(String name, UUID id, String server, String dim, boolean coords, int x, int y, int z, String anarchy) {}

    private record Resp(int status, JsonElement body) {}

    // ---------------------------------------------------------------- настройки
    private static final long SYNC_MS = 3000;
    private static final int REQ_TTL_S = 120;          // должно совпадать с REQ_TTL в реле
    private static final double MARK_RANGE = 300.0;    // дальность луча для метки
    private static final int[] PALETTE = {0xFF5555, 0x55FF55, 0x55FFFF, 0xFFAA00, 0xFF55FF, 0x5599FF, 0xFFFF55, 0xFFFFFF};

    public static KeyBinding markKey;
    public static KeyBinding unmarkKey;
    public static KeyBinding acceptKey;
    public static KeyBinding denyKey;

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "KitKatFriends-Http");
        t.setDaemon(true);
        return t;
    });

    private static volatile List<Friend> friends = List.of();
    private static volatile List<Request> requests = List.of();
    private static volatile List<Mark> marks = List.of();
    private static volatile List<Online> online = List.of();

    private static volatile long nextSyncAt = 0;
    private static volatile boolean syncBusy = false;
    private static volatile boolean warnedAuth = false;
    private static volatile boolean warnedDown = false;
    private static volatile boolean privacyNoticeShown = false;
    /** Ник (в нижнем регистре) -> когда ответили; чтобы запрос не "воскрес" из-за синка, который был в полёте. */
    private static final Map<String, Long> handled = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------- инициализация
    /** Вызвать один раз из KitKatVisualsClient.onInitializeClient (после KeyBinds.register()). */
    public static void init() {
        markKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.kitkatvisuals.friend_mark", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "category.kitkatvisuals"));
        unmarkKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.kitkatvisuals.friend_unmark", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "category.kitkatvisuals"));
        acceptKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.kitkatvisuals.friend_accept", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_Y, "category.kitkatvisuals"));
        denyKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.kitkatvisuals.friend_deny", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, "category.kitkatvisuals"));

        ClientTickEvents.END_CLIENT_TICK.register(FriendLink::tick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> FriendCommands.register(dispatcher));
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> FriendHud.render(ctx));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> nextSyncAt = 0);
        FriendChat.init();
    }

    // ---------------------------------------------------------------- доступ для HUD / модуля / команд
    public static List<Friend> friends() { return friends; }
    public static List<Online> onlineCache() { return online; }

    /** Не просроченные запросы в друзья, старые первыми. */
    public static List<Request> pendingRequests() {
        long now = System.currentTimeMillis();
        List<Request> out = new ArrayList<>();
        for (Request r : requests) if (r.expiresAt() > now) out.add(r);
        return out;
    }

    public static float requestFraction(Request r) {
        return Math.max(0f, Math.min(1f, (r.expiresAt() - System.currentTimeMillis()) / (REQ_TTL_S * 1000f)));
    }

    /** Метки той же сети серверов, того же измерения и (если известна) той же анархии: свои и друзей. */
    public static List<Mark> marksHere(MinecraftClient c) {
        if (c.world == null) return List.of();
        String server = serverKey(c);
        String dim = dimKey(c);
        if (server.isEmpty()) return List.of();
        String anarchy = com.kitkatvisuals.util.AnarchyDetector.label(c);
        List<Mark> out = new ArrayList<>();
        for (Mark m : marks) {
            boolean sameNet = Network.same(m.server(), server);
            boolean sameDim = m.dim().isEmpty() || m.dim().equals(dim);
            // координаты имеют смысл только в одном мире: если анархия известна с обеих сторон, она должна совпасть
            boolean sameWorld = m.anarchy().isEmpty() || anarchy.isEmpty() || m.anarchy().equalsIgnoreCase(anarchy);
            if (sameNet && sameDim && sameWorld) out.add(m);
        }
        return out;
    }

    public static int colorFor(String name) {
        return PALETTE[Math.floorMod(name.toLowerCase(Locale.ROOT).hashCode(), PALETTE.length)];
    }

    public static List<String> suggestOnline() {
        List<String> out = new ArrayList<>();
        for (Online o : online) out.add(o.name());
        if (online.isEmpty()) refreshOnlineSilently();
        return out;
    }

    public static List<String> suggestRequests() {
        List<String> out = new ArrayList<>();
        for (Request r : pendingRequests()) out.add(r.name());
        return out;
    }

    public static List<String> suggestFriends() {
        List<String> out = new ArrayList<>();
        for (Friend f : friends) out.add(f.name());
        return out;
    }

    // ---------------------------------------------------------------- тик
    private static void tick(MinecraftClient c) {
        boolean accept = false, deny = false;
        while (acceptKey.wasPressed()) accept = true;
        while (denyKey.wasPressed()) deny = true;
        boolean mark = false, unmark = false;
        while (markKey.wasPressed()) mark = true;
        while (unmarkKey.wasPressed()) unmark = true;

        if (c.player == null || c.world == null || c.getNetworkHandler() == null) return;

        List<Request> pending = pendingRequests();
        if (!pending.isEmpty()) {
            if (accept) respond(c, pending.get(0).name(), true);
            else if (deny) respond(c, pending.get(0).name(), false);
        }
        if (mark) placeMark(c, takeMarkLabel());
        if (unmark) clearMarks(c);

        if (!linkReady()) return;
        long now = System.currentTimeMillis();
        if (now < nextSyncAt || syncBusy) return;
        JsonObject body = presence(c, true);
        if (body == null) return;
        body.addProperty("chat_ack", FriendChat.lastSeq());   // подтверждаем реле, что сообщения чата до этого номера получены
        nextSyncAt = now + SYNC_MS + ThreadLocalRandom.current().nextInt(400);
        syncBusy = true;
        EXEC.execute(() -> {
            try {
                handleSync(c, send("POST", "/sync", body));
            } catch (Exception e) {
                if (!warnedDown) {
                    warnedDown = true;
                    KitKatVisualsClient.LOGGER.warn("FriendLink: реле недоступно ({})", e.toString());
                }
            } finally {
                syncBusy = false;
            }
        });
    }

    /** Реле настроено, ClientLink включён, HTTP-транспорт разрешён и игрок не скрывает, что он с клиентом. */
    public static boolean linkReady() {
        ClientLinkModule m = ModuleManager.clientLink;
        return m != null && m.sharing() && m.useHttp();
    }

    // ---------------------------------------------------------------- идентификация
    private static UUID myId(MinecraftClient c) {
        try {
            UUID id = c.getSession().getUuidOrNull();      // id аккаунта: одинаковый на всех серверах
            if (id != null) return id;
        } catch (Throwable ignored) {
        }
        return c.player != null ? c.player.getUuid() : null;
    }

    private static String myName(MinecraftClient c) {
        return c.getSession().getUsername();
    }

    public static String serverKey(MinecraftClient c) {
        ServerInfo si = c.getCurrentServerEntry();
        if (si == null || si.address == null || si.address.isBlank()) return "";
        String n = ClientLink.normalizeServer(si.address);
        return n == null ? "" : n;
    }

    public static String dimKey(MinecraftClient c) {
        return c.world == null ? "" : c.world.getRegistryKey().getValue().toString();
    }

    private static JsonObject identity(MinecraftClient c) {
        UUID id = myId(c);
        if (id == null) return null;
        JsonObject o = new JsonObject();
        o.addProperty("uuid", id.toString());
        o.addProperty("name", myName(c));
        return o;
    }

    private static JsonObject presence(MinecraftClient c, boolean withCoords) {
        String server = serverKey(c);
        if (server.isEmpty() || c.player == null) return null;
        JsonObject o = identity(c);
        if (o == null) return null;
        o.addProperty("server", server);
        o.addProperty("dim", dimKey(c));
        o.addProperty("anarchy", com.kitkatvisuals.util.AnarchyDetector.label(c));
        FriendMarksModule fm = ModuleManager.friendMarks;
        boolean share = withCoords && (fm == null || fm.shareCoords.value);
        o.addProperty("share", share);
        if (share) {
            o.addProperty("x", c.player.getX());
            o.addProperty("y", c.player.getY());
            o.addProperty("z", c.player.getZ());
        }
        return o;
    }

    // ---------------------------------------------------------------- HTTP
    private static Resp send(String method, String path, JsonObject body) throws Exception {
        ClientLinkModule m = ModuleManager.clientLink;
        String base = m.relayUrl();
        if (base == null || base.isBlank()) throw new IllegalStateException("relay_url пуст");
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String token = m.relayToken() == null ? "" : m.relayToken();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(6))
                .header("X-KitKat-Token", token)
                .header("User-Agent", "KitKatVisuals-Friends/1.0");
        if ("POST".equals(method)) {
            b.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        } else {
            b.GET();
        }
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String text = r.body();
        JsonElement parsed;
        try {
            parsed = (text == null || text.isBlank()) ? new JsonObject() : JsonParser.parseString(text);
        } catch (Exception e) {
            parsed = new JsonObject();
        }
        return new Resp(r.statusCode(), parsed);
    }

    /** Отправка сообщения другу (для FriendChat). done получает HTTP-код; -1 реле недоступно, -2 реле не настроено. Вызывается НЕ в главном потоке. */
    static void chatSend(MinecraftClient c, UUID to, String text, java.util.function.IntConsumer done) {
        if (!linkReady()) {
            done.accept(-2);
            return;
        }
        JsonObject b = identity(c);
        if (b == null) {
            done.accept(-1);
            return;
        }
        b.addProperty("to", to.toString());
        b.addProperty("text", text);
        EXEC.execute(() -> {
            int code;
            try {
                code = send("POST", "/chat/send", b).status();
            } catch (Exception e) {
                KitKatVisualsClient.LOGGER.warn("FriendLink /chat/send: {}", e.toString());
                code = -1;
            }
            done.accept(code);
        });
    }

    private static void async(MinecraftClient c, String method, String path, JsonObject body, Consumer<Resp> then) {
        EXEC.execute(() -> {
            try {
                Resp r = send(method, path, body);
                c.execute(() -> then.accept(r));
            } catch (Exception e) {
                KitKatVisualsClient.LOGGER.warn("FriendLink {} {}: {}", method, path, e.toString());
                c.execute(() -> say(c, Text.literal("Реле недоступно, попробуй позже.").formatted(Formatting.RED)));
            }
        });
    }

    private static boolean ensureReady(MinecraftClient c) {
        if (linkReady()) return true;
        say(c, Text.literal("Включи модуль ClientLink (транспорт: HTTP-реле) и «Показывать, что я с клиентом».").formatted(Formatting.RED));
        return false;
    }

    // ---------------------------------------------------------------- разбор /sync
    private static JsonArray arr(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static double num(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsDouble() : 0;
    }

    private static boolean bool(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    private static void handleSync(MinecraftClient c, Resp r) {
        if (r.status() == 403) {
            if (!warnedAuth) {
                warnedAuth = true;
                KitKatVisualsClient.LOGGER.warn("FriendLink: реле отклонило токен (403), проверьте relay_token");
            }
            return;
        }
        if (r.status() != 200 || !r.body().isJsonObject()) return;
        warnedDown = false;
        warnedAuth = false;
        try {
            JsonObject o = r.body().getAsJsonObject();
            long now = System.currentTimeMillis();
            UUID me = myId(c);

            List<Friend> fl = new ArrayList<>();
            for (JsonElement e : arr(o, "friends")) {
                if (!e.isJsonObject()) continue;
                JsonObject f = e.getAsJsonObject();
                fl.add(new Friend(UUID.fromString(str(f, "uuid")), str(f, "name"), bool(f, "online"), str(f, "server"), str(f, "anarchy")));
            }

            handled.values().removeIf(t -> now - t > 10_000);
            List<String> known = new ArrayList<>();
            for (Request q : requests) known.add(q.name().toLowerCase(Locale.ROOT));
            List<Request> rl = new ArrayList<>();
            List<String> fresh = new ArrayList<>();
            for (JsonElement e : arr(o, "requests")) {
                if (!e.isJsonObject()) continue;
                JsonObject q = e.getAsJsonObject();
                String name = str(q, "name");
                String low = name.toLowerCase(Locale.ROOT);
                if (handled.containsKey(low)) continue;
                long expires = now + (long) Math.max(0, REQ_TTL_S - (int) num(q, "age")) * 1000L;
                rl.add(new Request(UUID.fromString(str(q, "uuid")), name, expires));
                if (!known.contains(low)) fresh.add(name);
            }

            List<Mark> ml = new ArrayList<>();
            for (JsonElement e : arr(o, "marks")) {
                if (!e.isJsonObject()) continue;
                JsonObject m = e.getAsJsonObject();
                UUID owner = UUID.fromString(str(m, "owner"));
                ml.add(new Mark(str(m, "id"), owner, str(m, "owner_name"), str(m, "server"), str(m, "dim"),
                        num(m, "x"), num(m, "y"), num(m, "z"), str(m, "label"), owner.equals(me), str(m, "anarchy")));
            }

            friends = List.copyOf(fl);
            requests = List.copyOf(rl);
            marks = List.copyOf(ml);
            FriendChat.onSync(c, arr(o, "chat"));

            List<JsonObject> notes = new ArrayList<>();
            for (JsonElement e : arr(o, "notices")) if (e.isJsonObject()) notes.add(e.getAsJsonObject());

            boolean notice = !privacyNoticeShown;
            privacyNoticeShown = true;
            c.execute(() -> {
                for (String n : fresh) onNewRequest(c, n);
                for (JsonObject n : notes) onNotice(c, str(n, "type"), str(n, "name"));
                FriendMarksModule fm = ModuleManager.friendMarks;
                if (notice && (fm == null || fm.shareCoords.value)) {
                    say(c, Text.literal("Твои координаты видны другим игрокам с модом (/pronoob). "
                            + "Отключить: ClickGUI → Метки друзей → «Делиться координатами».").formatted(Formatting.GRAY));
                }
            });
        } catch (Exception e) {
            KitKatVisualsClient.LOGGER.warn("FriendLink: не разобрал ответ реле ({})", e.toString());
        }
    }

    private static void onNewRequest(MinecraftClient c, String name) {
        EventBus.post(new NotificationEvent("Запрос в друзья", name + " хочет добавить тебя в друзья", 0x9D6BFF));
        MutableText accept = Text.literal("[Принять]").setStyle(Style.EMPTY.withColor(Formatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/friend accept " + name))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("/friend accept " + name))));
        MutableText deny = Text.literal("[Отклонить]").setStyle(Style.EMPTY.withColor(Formatting.RED)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/friend deny " + name))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("/friend deny " + name))));
        say(c, Text.literal(name).formatted(Formatting.WHITE)
                .append(Text.literal(" хочет дружить. ").formatted(Formatting.GRAY))
                .append(accept).append(Text.literal(" ")).append(deny));
    }

    private static void onNotice(MinecraftClient c, String type, String name) {
        switch (type) {
            case "accepted" -> {
                EventBus.post(new NotificationEvent("Друзья", name + " принял(а) запрос", 0x55FF55));
                say(c, Text.literal(name + " принял(а) твой запрос. Теперь вы друзья!").formatted(Formatting.GREEN));
            }
            case "denied" -> say(c, Text.literal(name + " отклонил(а) твой запрос в друзья.").formatted(Formatting.GRAY));
            case "removed" -> say(c, Text.literal(name + " убрал(а) тебя из друзей.").formatted(Formatting.GRAY));
            default -> { }
        }
    }

    // ---------------------------------------------------------------- действия (команды / клавиши)
    public static void sendRequest(MinecraftClient c, String target) {
        if (!ensureReady(c)) return;
        JsonObject b = identity(c);
        if (b == null) return;
        b.addProperty("target", target);
        async(c, "POST", "/friend/request", b, r -> {
            JsonObject o = r.body().isJsonObject() ? r.body().getAsJsonObject() : new JsonObject();
            String status = str(o, "status");
            String name = str(o, "name").isEmpty() ? target : str(o, "name");
            if (r.status() == 200 && "sent".equals(status)) {
                say(c, Text.literal("Запрос отправлен игроку " + name + ". Ждём ответа (2 мин).").formatted(Formatting.GREEN));
            } else if (r.status() == 200 && "accepted".equals(status)) {
                nextSyncAt = 0;
                say(c, Text.literal("Вы с " + name + " теперь друзья!").formatted(Formatting.GREEN));
            } else if (r.status() == 404) {
                say(c, Text.literal(target + " сейчас не с визуалом (или у него старая версия мода). "
                        + "Запрос можно отправить только игроку с модом.").formatted(Formatting.RED));
            } else if ("self".equals(status)) {
                say(c, Text.literal("Нельзя добавить самого себя.").formatted(Formatting.RED));
            } else if ("already".equals(status)) {
                say(c, Text.literal(name + " уже у тебя в друзьях.").formatted(Formatting.YELLOW));
            } else if ("full".equals(status)) {
                say(c, Text.literal("Список друзей заполнен.").formatted(Formatting.RED));
            } else if (r.status() == 429) {
                say(c, Text.literal("Слишком много запросов, подожди немного.").formatted(Formatting.RED));
            } else {
                say(c, Text.literal("Ошибка реле (HTTP " + r.status() + ").").formatted(Formatting.RED));
            }
        });
    }

    /** from == null: ответить на самый свежий запрос. */
    public static void respond(MinecraftClient c, String from, boolean accept) {
        if (!ensureReady(c)) return;
        JsonObject b = identity(c);
        if (b == null) return;
        b.addProperty("from", from == null ? "" : from);
        b.addProperty("accept", accept);
        if (from != null) {
            handled.put(from.toLowerCase(Locale.ROOT), System.currentTimeMillis());
            final String low = from.toLowerCase(Locale.ROOT);
            List<Request> left = new ArrayList<>();
            for (Request q : requests) if (!q.name().equalsIgnoreCase(low)) left.add(q);
            requests = List.copyOf(left);                       // HUD пропадает сразу
        }
        async(c, "POST", "/friend/respond", b, r -> {
            JsonObject o = r.body().isJsonObject() ? r.body().getAsJsonObject() : new JsonObject();
            String status = str(o, "status");
            String name = str(o, "name");
            if (r.status() == 200 && "accepted".equals(status)) {
                nextSyncAt = 0;
                say(c, Text.literal("Вы с " + name + " теперь друзья!").formatted(Formatting.GREEN));
            } else if (r.status() == 200) {
                say(c, Text.literal("Запрос от " + name + " отклонён.").formatted(Formatting.GRAY));
            } else if (r.status() == 404) {
                say(c, Text.literal("Нет активного запроса" + (from == null ? "" : " от " + from) + " (возможно, истёк).").formatted(Formatting.YELLOW));
            } else if ("full".equals(status)) {
                say(c, Text.literal("Список друзей заполнен.").formatted(Formatting.RED));
            } else {
                say(c, Text.literal("Ошибка реле (HTTP " + r.status() + ").").formatted(Formatting.RED));
            }
        });
    }

    public static void remove(MinecraftClient c, String target) {
        if (!ensureReady(c)) return;
        JsonObject b = identity(c);
        if (b == null) return;
        b.addProperty("target", target);
        async(c, "POST", "/friend/remove", b, r -> {
            if (r.status() == 200) {
                nextSyncAt = 0;
                say(c, Text.literal(target + " убран из друзей.").formatted(Formatting.GRAY));
            } else if (r.status() == 404) {
                say(c, Text.literal(target + " не в твоём списке друзей.").formatted(Formatting.YELLOW));
            } else {
                say(c, Text.literal("Ошибка реле (HTTP " + r.status() + ").").formatted(Formatting.RED));
            }
        });
    }

    public static void showFriends(MinecraftClient c) {
        List<Friend> fl = friends;
        if (fl.isEmpty()) {
            say(c, Text.literal("Друзей пока нет. /friend add <ник>").formatted(Formatting.GRAY));
            return;
        }
        say(c, Text.literal("Друзья (" + fl.size() + "):").formatted(Formatting.WHITE));
        for (Friend f : fl) {
            MutableText line = Text.literal(" " + (f.online() ? "● " : "○ ")).formatted(f.online() ? Formatting.GREEN : Formatting.DARK_GRAY)
                    .append(Text.literal(f.name()).formatted(f.online() ? Formatting.WHITE : Formatting.GRAY));
            if (f.online() && !f.server().isEmpty()) line.append(Text.literal("  " + f.server()).formatted(Formatting.DARK_AQUA));
            c.inGameHud.getChatHud().addMessage(line);
        }
    }

    // ---------------------------------------------------------------- /pronoob
    public static void showPronoob(MinecraftClient c) {
        if (!ensureReady(c)) return;
        EXEC.execute(() -> {
            try {
                Resp r = send("GET", "/online", null);
                if (r.status() != 200 || !r.body().isJsonArray()) {
                    c.execute(() -> say(c, Text.literal("Реле вернуло ошибку (HTTP " + r.status() + ").").formatted(Formatting.RED)));
                    return;
                }
                List<Online> list = parseOnline(r.body().getAsJsonArray());
                online = List.copyOf(list);
                c.execute(() -> printPronoob(c, list));
            } catch (Exception e) {
                KitKatVisualsClient.LOGGER.warn("FriendLink /online: {}", e.toString());
                c.execute(() -> say(c, Text.literal("Реле недоступно, попробуй позже.").formatted(Formatting.RED)));
            }
        });
    }

    private static void refreshOnlineSilently() {
        if (!linkReady()) return;
        EXEC.execute(() -> {
            try {
                Resp r = send("GET", "/online", null);
                if (r.status() == 200 && r.body().isJsonArray()) online = List.copyOf(parseOnline(r.body().getAsJsonArray()));
            } catch (Exception ignored) {
            }
        });
    }

    private static List<Online> parseOnline(JsonArray a) {
        List<Online> list = new ArrayList<>();
        for (JsonElement e : a) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            try {
                boolean coords = bool(o, "coords");
                list.add(new Online(str(o, "name"), UUID.fromString(str(o, "uuid")), str(o, "server"), str(o, "dim"), coords,
                        (int) num(o, "x"), (int) num(o, "y"), (int) num(o, "z"), str(o, "anarchy")));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return list;
    }

    private static String dimName(String dim) {
        return switch (dim) {
            case "minecraft:overworld" -> "Верхний мир";
            case "minecraft:the_nether" -> "Незер";
            case "minecraft:the_end" -> "Энд";
            case "" -> "?";
            default -> dim;
        };
    }

    private static void printPronoob(MinecraftClient c, List<Online> list) {
        if (list.isEmpty()) {
            say(c, Text.literal("Сейчас с визуалом никого нет.").formatted(Formatting.GRAY));
            return;
        }
        UUID me = myId(c);
        Map<String, List<Online>> byServer = new LinkedHashMap<>();
        for (Online o : list) byServer.computeIfAbsent(o.server(), k -> new ArrayList<>()).add(o);
        say(c, Text.literal("С визуалом сейчас: " + list.size() + " (серверов: " + byServer.size() + ")").formatted(Formatting.WHITE));
        for (Map.Entry<String, List<Online>> en : byServer.entrySet()) {
            c.inGameHud.getChatHud().addMessage(Text.literal("» " + en.getKey()).formatted(Formatting.AQUA)
                    .append(Text.literal("  (" + en.getValue().size() + ")").formatted(Formatting.DARK_GRAY)));
            for (Online o : en.getValue()) {
                boolean isMe = o.id().equals(me);
                boolean isFriend = false;
                for (Friend f : friends) if (f.id().equals(o.id())) isFriend = true;
                MutableText line = Text.literal("   " + o.name()).formatted(isMe ? Formatting.YELLOW : isFriend ? Formatting.GREEN : Formatting.WHITE);
                if (isMe) line.append(Text.literal(" (ты)").formatted(Formatting.GRAY));
                String an = o.anarchy();
                if (an.isEmpty() && isMe) an = com.kitkatvisuals.util.AnarchyDetector.label(c);   // про себя знаем и без реле
                line.append(an.isEmpty() ? Text.literal("  анархия: ?").formatted(Formatting.DARK_GRAY)
                                         : Text.literal("  " + an).formatted(Formatting.LIGHT_PURPLE));
                if (o.coords()) {
                    String xyz = o.x() + " " + o.y() + " " + o.z();
                    line.append(Text.literal("  " + xyz).setStyle(Style.EMPTY.withColor(Formatting.GOLD)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, xyz))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Нажми, чтобы скопировать")))));
                    line.append(Text.literal("  " + dimName(o.dim())).formatted(Formatting.DARK_GRAY));
                } else {
                    line.append(Text.literal("  координаты скрыты").formatted(Formatting.DARK_GRAY));
                }
                c.inGameHud.getChatHud().addMessage(line);
            }
        }
    }

    // ---------------------------------------------------------------- метки
    /** Название для следующей метки по клавише: вкладка «Друзья» кладёт сюда текст поля «Название метки». */
    public static volatile String pendingMarkLabel = "";
    /** true: название только что ушло в метку, поле на вкладке нужно очистить. */
    public static volatile boolean markLabelUsed = false;

    static String takeMarkLabel() {
        String t = pendingMarkLabel == null ? "" : pendingMarkLabel.strip();
        if (!t.isEmpty()) {
            pendingMarkLabel = "";
            markLabelUsed = true;
        }
        return t;
    }

    public static void placeMark(MinecraftClient c, String label) {
        FriendMarksModule fm = ModuleManager.friendMarks;
        if (fm == null || !fm.isEnabled()) {
            say(c, Text.literal("Включи модуль «Метки друзей» в ClickGUI.").formatted(Formatting.RED));
            return;
        }
        if (!ensureReady(c) || c.player == null || c.world == null) return;
        HitResult hit = c.player.raycast(MARK_RANGE, 1.0f, false);
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            say(c, Text.literal("Наведись на блок, чтобы поставить метку.").formatted(Formatting.YELLOW));
            return;
        }
        Vec3d p = hit.getPos();
        JsonObject b = presence(c, false);
        if (b == null) {
            say(c, Text.literal("Метки работают только на сервере.").formatted(Formatting.RED));
            return;
        }
        b.addProperty("x", p.x);
        b.addProperty("y", p.y);
        b.addProperty("z", p.z);
        b.addProperty("label", label == null ? "" : label);
        async(c, "POST", "/mark", b, r -> {
            if (r.status() == 200) {
                nextSyncAt = 0;      // сразу обновим, чтобы метка появилась без задержки
                say(c, Text.literal(friends.isEmpty() ? "Метка поставлена (друзей пока нет, её видишь только ты)."
                        : "Метка поставлена: её видят твои друзья.").formatted(Formatting.GREEN));
            } else {
                say(c, Text.literal("Не удалось поставить метку (HTTP " + r.status() + ").").formatted(Formatting.RED));
            }
        });
    }

    public static void clearMarks(MinecraftClient c) {
        if (!ensureReady(c)) return;
        JsonObject b = identity(c);
        if (b == null) return;
        async(c, "POST", "/mark/clear", b, r -> {
            if (r.status() == 200) {
                nextSyncAt = 0;
                say(c, Text.literal("Твои метки убраны.").formatted(Formatting.GRAY));
            }
        });
    }

    // ---------------------------------------------------------------- чат
    /** Последняя реплика мода про друзей: вкладка «Друзья» показывает её в строке статуса. */
    public static volatile String lastSay = "";
    public static volatile long lastSayAt = 0;

    public static void say(MinecraftClient c, Text t) {
        lastSay = t.getString();
        lastSayAt = System.currentTimeMillis();
        c.execute(() -> {
            if (c.inGameHud == null) return;
            c.inGameHud.getChatHud().addMessage(Text.literal("[KitKat] ").formatted(Formatting.LIGHT_PURPLE).append(t));
        });
    }
}
