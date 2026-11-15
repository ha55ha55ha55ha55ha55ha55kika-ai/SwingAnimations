package com.swinganimations.link;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.swinganimations.SwingAnimationsClient;
import com.swinganimations.module.ClientLinkModule;
import com.swinganimations.module.ModuleManager;
import com.swinganimations.music.MusicPlayer;
import com.swinganimations.music.Track;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Реестр игроков «с нашим клиентом» и того, что они слушают. Два транспорта (можно оба сразу):
 * 1) канал swinganimations:link через плагин-реле на сервере (extras/SwingLinkRelay);
 * 2) HTTP-реле (extras/swing_relay.py), работает на любом сервере, где плагин поставить нельзя.
 * Только клиент; никакого геймплея. Всё, что пришло по сети, считается недоверенным: тексты чистятся и обрезаются,
 * а запись принимается только для игроков, которые реально есть в списке игроков сервера.
 */
public final class ClientLink {
    public static final Identifier ICON_FONT = Identifier.of("swinganimations", "icons");
    /** Значок логотипа (глиф из шрифта icons). */
    public static final Text BADGE = Text.literal("\uE000").setStyle(Style.EMPTY.withFont(ICON_FONT));

    public static final int STATE_NONE = 0;
    public static final int STATE_PLAYING = 1;
    public static final int STATE_PAUSED = 2;

    private static final long TTL_MS = 100_000L;
    private static final long HEARTBEAT_MS = 30_000L;
    private static final int MAX_TEXT = 80;

    /** Запись об игроке: неизменяемая, заменяется целиком. */
    public record Peer(UUID id, String name, int state, String title, String artist, long seen) {
        public boolean listening() { return state != STATE_NONE && !title.isEmpty(); }
    }

    private static final Map<UUID, Peer> PEERS = new ConcurrentHashMap<>();
    private static volatile ScheduledExecutorService http;
    private static volatile String lastSentKey = "";
    private static volatile long lastSentAt;
    private static volatile boolean helloSent;
    private static volatile boolean httpBusy;
    private static volatile int curState;
    private static volatile String curTitle = "";
    private static volatile String curArtist = "";

    private ClientLink() {}

    // ---------------- инициализация ----------------

    public static void init() {
        try {
            PayloadTypeRegistry.playC2S().register(LinkPayloads.Up.ID, LinkPayloads.Up.CODEC);
            PayloadTypeRegistry.playS2C().register(LinkPayloads.Down.ID, LinkPayloads.Down.CODEC);
            ClientPlayNetworking.registerGlobalReceiver(LinkPayloads.Down.ID, (payload, ctx) -> {
                UUID id = payload.sender();
                byte[] body = payload.body();
                ctx.client().execute(() -> accept(id, body));
            });
            ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
            ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        } catch (Throwable t) {
            SwingAnimationsClient.LOGGER.warn("ClientLink: канал недоступен ({})", t.toString());
        }
    }

    private static void reset() {
        PEERS.clear();
        helloSent = false;
        lastSentKey = "";
        lastSentAt = 0L;
    }

    // ---------------- запросы ----------------

    private static ClientLinkModule module() {
        ClientLinkModule m = ModuleManager.clientLink;
        return m != null && m.isEnabled() ? m : null;
    }

    public static boolean isLinked(UUID id) {
        if (id == null || module() == null) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && id.equals(mc.player.getUuid())) return module().sharing();
        Peer p = PEERS.get(id);
        return p != null && System.currentTimeMillis() - p.seen() < TTL_MS;
    }

    public static Peer peer(UUID id) {
        Peer p = PEERS.get(id);
        return p != null && System.currentTimeMillis() - p.seen() < TTL_MS ? p : null;
    }

    public static Iterable<Peer> peers() { return PEERS.values(); }

    /** Если в начале текста сообщения упомянут ник игрока с клиентом: возвращает true. */
    public static boolean mentionsLinked(String text) {
        if (text == null || text.isEmpty() || PEERS.isEmpty()) return false;
        String head = text.length() > 64 ? text.substring(0, 64) : text;
        long now = System.currentTimeMillis();
        for (Peer p : PEERS.values()) {
            if (p.name().isEmpty() || now - p.seen() >= TTL_MS) continue;
            if (containsWord(head, p.name())) return true;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientLinkModule m = module();
        return m != null && m.sharing() && mc.player != null && containsWord(head, mc.player.getName().getString());
    }

    private static boolean containsWord(String s, String w) {
        int from = 0;
        while (true) {
            int i = s.indexOf(w, from);
            if (i < 0) return false;
            boolean l = i == 0 || !isNameChar(s.charAt(i - 1));
            int e = i + w.length();
            boolean r = e >= s.length() || !isNameChar(s.charAt(e));
            if (l && r) return true;
            from = i + 1;
        }
    }

    private static boolean isNameChar(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }

    // ---------------- кодек тела ----------------

    private static String clean(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(Math.min(s.length(), MAX_TEXT));
        for (int i = 0; i < s.length() && b.length() < MAX_TEXT; i++) {
            char c = s.charAt(i);
            if (c < 32 || c == '\u00A7' || c == 127) continue;
            b.append(c);
        }
        return b.toString().trim();
    }

    static byte[] encode(int state, String title, String artist) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(96);
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(1);
        out.writeByte(state);
        out.writeUTF(clean(title));
        out.writeUTF(clean(artist));
        return bos.toByteArray();
    }

    private static void accept(UUID id, byte[] body) {
        ClientLinkModule m = module();
        if (m == null || body == null || body.length > 600) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || id.equals(mc.player.getUuid())) return;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
            if (in.readUnsignedByte() != 1) return;
            int state = in.readUnsignedByte();
            String title = clean(in.readUTF());
            String artist = clean(in.readUTF());
            store(id, "", state, title, artist);
        } catch (IOException ignored) {
            // битый пакет: игнорируем
        }
    }

    /** Общая точка записи (канал и HTTP): проверяет, что игрок есть в списке сервера. */
    private static void store(UUID id, String claimedName, int state, String title, String artist) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayNetworkHandler h = mc.getNetworkHandler();
        if (h == null) return;
        PlayerListEntry e = h.getPlayerListEntry(id);
        if (e == null) return;
        String name = e.getProfile().getName();
        if (!claimedName.isEmpty() && !claimedName.equalsIgnoreCase(name)) return;
        if (state < 0 || state > 2) state = 0;
        PEERS.put(id, new Peer(id, name, state, title, artist, System.currentTimeMillis()));
    }

    // ---------------- отправка (из тика модуля, раз в секунду) ----------------

    public static void tick(MinecraftClient mc, ClientLinkModule m) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        long now = System.currentTimeMillis();
        PEERS.values().removeIf(p -> now - p.seen() > TTL_MS * 2);

        int state = STATE_NONE;
        String title = "";
        String artist = "";
        if (m.sharingMusic()) {
            Track t = MusicPlayer.current();
            MusicPlayer.State st = MusicPlayer.state();
            if (t != null && (st == MusicPlayer.State.PLAYING || st == MusicPlayer.State.PAUSED)) {
                state = st == MusicPlayer.State.PLAYING ? STATE_PLAYING : STATE_PAUSED;
                title = t.title();
                artist = t.artist();
            }
        }
        curState = state;
        curTitle = title;
        curArtist = artist;
        String key = state + "|" + title + "|" + artist;
        boolean changed = !key.equals(lastSentKey);
        boolean beat = now - lastSentAt >= HEARTBEAT_MS;
        if (!m.sharing()) return;
        if (!changed && !beat && helloSent) return;

        boolean sent = false;
        if (m.useChannel() && ClientPlayNetworking.canSend(LinkPayloads.Up.ID)) {
            try {
                ClientPlayNetworking.send(new LinkPayloads.Up(encode(state, title, artist)));
                sent = true;
            } catch (Throwable t) {
                SwingAnimationsClient.LOGGER.debug("ClientLink: не отправлено ({})", t.toString());
            }
        }
        if (m.useHttp() && !m.relayUrl().isEmpty() && mc.getCurrentServerEntry() != null) {
            httpSync(mc, m, state, title, artist);
            sent = true;
        }
        if (sent) {
            helloSent = true;
            lastSentKey = key;
            lastSentAt = now;
        }
    }

    // ---------------- HTTP-реле ----------------

    private static String serverId(MinecraftClient mc) {
        ServerInfo si = mc.getCurrentServerEntry();
        return si == null ? "" : si.address.toLowerCase(Locale.ROOT).trim();
    }

    private static void httpSync(MinecraftClient mc, ClientLinkModule m, int state, String title, String artist) {
        if (httpBusy) return;
        httpBusy = true;
        String url = m.relayUrl();
        String token = m.relayToken();
        String server = serverId(mc);
        UUID me = mc.player.getUuid();
        String name = mc.player.getName().getString();
        ScheduledExecutorService ex = http;
        if (ex == null) {
            ex = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "SwingLink-Http");
                t.setDaemon(true);
                return t;
            });
            http = ex;
        }
        ex.execute(() -> {
            try {
                JsonObject o = new JsonObject();
                o.addProperty("server", server);
                o.addProperty("uuid", me.toString());
                o.addProperty("name", name);
                o.addProperty("state", state);
                o.addProperty("title", clean(title));
                o.addProperty("artist", clean(artist));
                post(url + "/hello", o.toString(), token);
                String json = get(url + "/peers?server=" + java.net.URLEncoder.encode(server, StandardCharsets.UTF_8), token);
                JsonElement root = JsonParser.parseString(json);
                if (root.isJsonArray()) {
                    JsonArray arr = root.getAsJsonArray();
                    int n = Math.min(arr.size(), 300);
                    for (int i = 0; i < n; i++) {
                        JsonObject p = arr.get(i).getAsJsonObject();
                        UUID id = UUID.fromString(p.get("uuid").getAsString());
                        if (id.equals(me)) continue;
                        String nm = p.has("name") ? p.get("name").getAsString() : "";
                        int st = p.has("state") ? p.get("state").getAsInt() : 0;
                        String tt = clean(p.has("title") ? p.get("title").getAsString() : "");
                        String ar = clean(p.has("artist") ? p.get("artist").getAsString() : "");
                        mc.execute(() -> store(id, nm, st, tt, ar));
                    }
                }
            } catch (Throwable t) {
                SwingAnimationsClient.LOGGER.debug("ClientLink HTTP: {}", t.toString());
            } finally {
                httpBusy = false;
            }
        });
    }

    /** Периодический опрос реле, даже когда своё состояние менять не нужно (чтобы видеть других). */
    public static void poll(MinecraftClient mc, ClientLinkModule m) {
        if (!m.useHttp() || m.relayUrl().isEmpty() || mc.getCurrentServerEntry() == null || mc.player == null) return;
        httpSync(mc, m, curState, curTitle, curArtist);
    }

    private static HttpURLConnection open(String url, String method, String token) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", "SwingAnimations-Link/1.0");
        if (!token.isEmpty()) c.setRequestProperty("X-Swing-Token", token);
        return c;
    }

    private static void post(String url, String json, String token) throws IOException {
        HttpURLConnection c = open(url, "POST", token);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = c.getOutputStream()) {
            os.write(data);
        }
        int code = c.getResponseCode();
        c.disconnect();
        if (code >= 400) throw new IOException("HTTP " + code);
    }

    private static String get(String url, String token) throws IOException {
        HttpURLConnection c = open(url, "GET", token);
        int code = c.getResponseCode();
        if (code >= 400) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        try (java.io.InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 256 * 1024) throw new IOException("Response too large");
            }
            return out.toString(StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    /** Значок + пробел перед текстом. */
    public static Text withBadge(Text original) {
        return Text.empty().append(BADGE).append(Text.literal(" ")).append(original);
    }
}
