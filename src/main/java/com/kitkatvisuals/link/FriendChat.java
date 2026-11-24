package com.kitkatvisuals.link;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kitkatvisuals.KitKatVisualsClient;
import com.kitkatvisuals.event.EventBus;
import com.kitkatvisuals.event.NotificationEvent;
import com.kitkatvisuals.gui.ChatScreen;
import com.kitkatvisuals.util.AnarchyDetector;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Чат между друзьями. Реле (swing_relay.py v4) работает как почтовый ящик: сообщение уходит POST /chat/send,
 * а получатель забирает его в обычном /sync (поле "chat") и подтверждает приём через chat_ack.
 * История и счётчики непрочитанного лежат у клиента в config/kitkatvisuals_chat.json.
 * Всё, что трогает историю, выполняется в главном потоке.
 */
public final class FriendChat {
    private FriendChat() {}

    public record Msg(boolean mine, String text, long ts) {}

    private record Incoming(long seq, UUID from, String name, String text, long ts) {}

    public static final int MAX_LEN = 300;
    private static final int MAX_HISTORY = 300;

    public static KeyBinding openKey;
    public static KeyBinding helpKey;
    /** Открывает ClickGUI на вкладке «Друзья». Назначается в «Управлении» или кнопкой на самой вкладке. */
    public static KeyBinding friendsKey;
    /** Строка статуса для экрана чата (ошибка отправки и т.п.). Пустая, если всё хорошо. */
    public static String status = "";

    private static final Map<UUID, List<Msg>> HISTORY = new HashMap<>();
    private static final Map<UUID, Integer> UNREAD = new HashMap<>();
    private static volatile long lastSeq = 0;
    private static UUID viewing = null;
    private static boolean dirty = false;
    private static long saveAt = 0;

    // ---------------------------------------------------------------- инициализация
    /** Вызывается из FriendLink.init(). */
    public static void init() {
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.kitkatvisuals.chat_open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "category.kitkatvisuals"));
        helpKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.kitkatvisuals.chat_help", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "category.kitkatvisuals"));
        friendsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.kitkatvisuals.friends_open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "category.kitkatvisuals"));
        load();
        ClientTickEvents.END_CLIENT_TICK.register(FriendChat::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> save());
    }

    private static void tick(MinecraftClient c) {
        boolean open = false;
        while (openKey.wasPressed()) open = true;
        if (open && c.currentScreen == null && c.player != null) ChatScreen.open(c, null);
        boolean tab = false;
        while (friendsKey.wasPressed()) tab = true;
        if (tab && c.currentScreen == null && c.player != null) com.kitkatvisuals.gui.ClickGuiScreen.openFriends(c);
        boolean help = false;
        while (helpKey.wasPressed()) help = true;
        if (help && c.currentScreen == null && c.player != null) sendHelp(c);
        if (dirty && System.currentTimeMillis() >= saveAt) save();
    }

    // ---------------------------------------------------------------- доступ для экрана
    public static long lastSeq() { return lastSeq; }

    public static List<Msg> history(UUID id) {
        List<Msg> l = HISTORY.get(id);
        return l == null ? List.of() : l;
    }

    public static int unread(UUID id) { return UNREAD.getOrDefault(id, 0); }

    public static int totalUnread() {
        int n = 0;
        for (int v : UNREAD.values()) n += v;
        return n;
    }

    /** Какой диалог сейчас открыт на экране (null, если экран закрыт): в него новые сообщения не считаются непрочитанными. */
    public static void setViewing(UUID id) {
        viewing = id;
        if (id != null) markRead(id);
    }

    public static void markRead(UUID id) {
        if (UNREAD.remove(id) != null) touch();
    }

    // ---------------------------------------------------------------- приём (вызывается из FriendLink.handleSync в потоке HTTP)
    static void onSync(MinecraftClient c, JsonArray arr) {
        if (arr == null || arr.isEmpty()) return;
        List<Incoming> in = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (JsonElement e : arr) {
            try {
                if (!e.isJsonObject()) continue;
                JsonObject m = e.getAsJsonObject();
                in.add(new Incoming(m.get("seq").getAsLong(), UUID.fromString(m.get("from").getAsString()),
                        m.get("name").getAsString(), m.get("text").getAsString(),
                        now - 1000L * (m.has("age") ? m.get("age").getAsLong() : 0)));
            } catch (Exception ex) {
                KitKatVisualsClient.LOGGER.warn("FriendChat: битое сообщение от реле ({})", ex.toString());
            }
        }
        if (in.isEmpty()) return;
        c.execute(() -> apply(c, in));
    }

    private static void apply(MinecraftClient c, List<Incoming> in) {
        boolean ping = false;
        for (Incoming m : in) {
            if (m.seq() <= lastSeq) continue;                // уже получено (подтверждение ещё не дошло до реле)
            lastSeq = m.seq();
            add(m.from(), new Msg(false, m.text(), m.ts()));
            boolean seen = viewing != null && viewing.equals(m.from()) && c.currentScreen instanceof ChatScreen;
            if (!seen) {
                UNREAD.merge(m.from(), 1, Integer::sum);
                ping = true;
                String shown = m.text().length() > 80 ? m.text().substring(0, 80) + "…" : m.text();
                EventBus.post(new NotificationEvent(m.name(), shown, 0x9D6BFF));
                if (c.inGameHud != null) {
                    Style open = Style.EMPTY.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/kk " + m.name()));
                    c.inGameHud.getChatHud().addMessage(Text.literal("[KitKatGram] ").formatted(Formatting.LIGHT_PURPLE)
                            .append(Text.literal(m.name()).setStyle(open.withColor(Formatting.WHITE)))
                            .append(Text.literal(": " + shown).formatted(Formatting.GRAY)));
                }
            }
        }
        if (ping) {
            try {
                c.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.6f));
            } catch (Throwable ignored) {
            }
        }
        touch();
    }

    // ---------------------------------------------------------------- отправка
    public static void send(MinecraftClient c, FriendLink.Friend f, String raw, boolean echo) {
        String t = raw == null ? "" : raw.strip();
        if (t.isEmpty()) return;
        if (t.length() > MAX_LEN) t = t.substring(0, MAX_LEN);
        final String text = t;
        final UUID to = f.id();
        final String name = f.name();
        status = "";
        FriendLink.chatSend(c, to, text, code -> c.execute(() -> {
            if (code == 200) {
                add(to, new Msg(true, text, System.currentTimeMillis()));
                status = "";
                touch();
                if (echo) FriendLink.say(c, Text.literal("→ " + name + ": ").formatted(Formatting.GRAY)
                        .append(Text.literal(text).formatted(Formatting.WHITE)));
            } else {
                status = switch (code) {
                    case 403 -> "Вы больше не друзья";
                    case 404 -> "Реле старой версии: обнови swing_relay.py";
                    case 429 -> "Слишком быстро, подожди секунду";
                    case -2 -> "Включи ClientLink (транспорт: HTTP-реле)";
                    default -> "Реле недоступно, сообщение не отправлено";
                };
                FriendLink.say(c, Text.literal(status).formatted(Formatting.RED));
            }
        }));
    }

    /** Кнопка «Помощь»: шлёт друзьям, которые сейчас в сети, мой ник, сервер, анархию (если определилась) и координаты. */
    public static void sendHelp(MinecraftClient c) {
        String server = c.player == null || c.world == null ? "" : FriendLink.serverKey(c);
        if (server.isEmpty()) {
            FriendLink.say(c, Text.literal("Позвать на помощь можно только на сервере.").formatted(Formatting.RED));
            return;
        }
        List<FriendLink.Friend> targets = new ArrayList<>();
        for (FriendLink.Friend f : FriendLink.friends()) if (f.online()) targets.add(f);
        if (FriendLink.friends().isEmpty()) {
            FriendLink.say(c, Text.literal("У тебя пока нет друзей: /friend add <ник>").formatted(Formatting.RED));
            return;
        }
        if (targets.isEmpty()) {
            FriendLink.say(c, Text.literal("Никого из друзей сейчас нет в сети.").formatted(Formatting.RED));
            return;
        }
        String text = helpText(c, server);
        int n = Math.min(targets.size(), 8);          // лимит реле: 8 сообщений за 10 секунд
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < n; i++) {
            send(c, targets.get(i), text, false);
            if (i > 0) names.append(", ");
            names.append(targets.get(i).name());
        }
        FriendLink.say(c, Text.literal("Позвал на помощь: " + names).formatted(Formatting.GREEN));
    }

    private static String helpText(MinecraftClient c, String server) {
        StringBuilder sb = new StringBuilder("[HELP] Нужна помощь! Ник: ").append(c.getSession().getUsername());
        sb.append(" | Сервер: ").append(server);
        int anarchy = AnarchyDetector.detect(c);
        if (anarchy > 0) sb.append(" | Анархия: ").append(anarchy);
        String dim = FriendLink.dimKey(c);
        String dimName = switch (dim) {
            case "minecraft:overworld" -> "Верхний мир";
            case "minecraft:the_nether" -> "Незер";
            case "minecraft:the_end" -> "Энд";
            default -> dim;
        };
        sb.append(" | Координаты: ").append(c.player.getBlockX()).append(' ').append(c.player.getBlockY())
                .append(' ').append(c.player.getBlockZ()).append(" (").append(dimName).append(')');
        return sb.toString();
    }

    /** /kk <ник> <текст> */
    public static void quickSend(MinecraftClient c, String nick, String text) {
        FriendLink.Friend f = findFriend(nick);
        if (f == null) {
            FriendLink.say(c, Text.literal("Нет такого друга: " + nick).formatted(Formatting.RED));
            return;
        }
        send(c, f, text, true);
    }

    /** /kk <ник> и /kk: открыть чат (экран открывается на следующем тике, иначе его закроет сам чат майнкрафта). */
    public static void openLater(MinecraftClient c, String nick) {
        FriendLink.Friend f = nick == null ? null : findFriend(nick);
        if (nick != null && f == null) {
            FriendLink.say(c, Text.literal("Нет такого друга: " + nick).formatted(Formatting.RED));
            return;
        }
        UUID id = f == null ? null : f.id();
        c.send(() -> ChatScreen.open(c, id));
    }

    private static FriendLink.Friend findFriend(String nick) {
        String low = nick.toLowerCase(Locale.ROOT);
        for (FriendLink.Friend f : FriendLink.friends()) if (f.name().toLowerCase(Locale.ROOT).equals(low)) return f;
        return null;
    }

    // ---------------------------------------------------------------- история
    private static void add(UUID id, Msg m) {
        List<Msg> l = HISTORY.computeIfAbsent(id, k -> new ArrayList<>());
        l.add(m);
        while (l.size() > MAX_HISTORY) l.remove(0);
    }

    private static void touch() {
        dirty = true;
        saveAt = System.currentTimeMillis() + 2000;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("kitkatvisuals_chat.json");
    }

    private static void load() {
        try {
            Path p = file();
            if (!Files.isRegularFile(p)) return;
            JsonObject o = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("lastSeq")) lastSeq = o.get("lastSeq").getAsLong();
            JsonObject chats = o.has("chats") ? o.getAsJsonObject("chats") : new JsonObject();
            for (Map.Entry<String, JsonElement> e : chats.entrySet()) {
                List<Msg> list = new ArrayList<>();
                for (JsonElement me : e.getValue().getAsJsonArray()) {
                    JsonObject m = me.getAsJsonObject();
                    list.add(new Msg(m.get("m").getAsBoolean(), m.get("t").getAsString(), m.get("ts").getAsLong()));
                }
                HISTORY.put(UUID.fromString(e.getKey()), list);
            }
            JsonObject un = o.has("unread") ? o.getAsJsonObject("unread") : new JsonObject();
            for (Map.Entry<String, JsonElement> e : un.entrySet()) UNREAD.put(UUID.fromString(e.getKey()), e.getValue().getAsInt());
        } catch (Exception ex) {
            KitKatVisualsClient.LOGGER.warn("FriendChat: не прочитал историю чата ({})", ex.toString());
        }
    }

    private static void save() {
        dirty = false;
        try {
            JsonObject o = new JsonObject();
            o.addProperty("lastSeq", lastSeq);
            JsonObject chats = new JsonObject();
            for (Map.Entry<UUID, List<Msg>> e : HISTORY.entrySet()) {
                JsonArray arr = new JsonArray();
                for (Msg m : e.getValue()) {
                    JsonObject j = new JsonObject();
                    j.addProperty("m", m.mine());
                    j.addProperty("t", m.text());
                    j.addProperty("ts", m.ts());
                    arr.add(j);
                }
                chats.add(e.getKey().toString(), arr);
            }
            o.add("chats", chats);
            JsonObject un = new JsonObject();
            for (Map.Entry<UUID, Integer> e : UNREAD.entrySet()) un.addProperty(e.getKey().toString(), e.getValue());
            o.add("unread", un);
            Files.writeString(file(), o.toString(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            KitKatVisualsClient.LOGGER.warn("FriendChat: не сохранил историю чата ({})", ex.toString());
        }
    }
}
