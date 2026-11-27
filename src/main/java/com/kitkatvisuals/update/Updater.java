package com.kitkatvisuals.update;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kitkatvisuals.KitKatVisualsClient;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Автообновление: в фоне раз в несколько часов смотрит последний релиз в GitHub, скачивает новый jar рядом со старым
 * (как *.jar.new), проверяет SHA-256 и что внутри наш мод, и подменяет старый jar при выходе из игры.
 * Запуск игры не тормозит (всё в фоновом потоке после старта); нет сети или что-то не так: тихо ничего не делаем.
 * Команды: /kkupdate (проверить сейчас), /kkupdate on|off.
 */
public final class Updater {
    private Updater() {}

    /** Репозиторий GitHub, из релизов которого берутся обновления (только отсюда и только по HTTPS). */
    private static final String REPO = "ha55ha55ha55ha55ha55ha55kika-ai/SwingAnimations";
    private static final String MOD_ID = "kitkatvisuals";
    private static final long CHECK_EVERY_MS = 10L * 60 * 1000;
    private static final long LOOP_EVERY_MS = 10L * 60 * 1000;
    private static final long MAX_JAR_BYTES = 64L * 1024 * 1024;
    private static final String UA = "KitKatVisuals-Updater/1.0 (Minecraft client mod)";
    private static final Pattern SHA = Pattern.compile("sha256:\\s*([0-9a-fA-F]{64})");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final AtomicBoolean BUSY = new AtomicBoolean();

    private static boolean swapRegistered;
    private static volatile String readyVersion;
    private static volatile boolean shown;

    public enum Result { DISABLED, DEV_BUILD, WAIT, UP_TO_DATE, READY, NO_RELEASE, FAILED, BUSY }

    private static final class Cfg {
        boolean enabled = true;
        long last_check;
        String last_version = "";
        String known_latest = "";
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> flushNotice(client)));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> registerCommand(dispatcher));
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(10_000); // не мешаем запуску игры
            } catch (InterruptedException e) {
                return;
            }
            boolean first = true;
            while (true) {
                run(false, first); // при запуске игры проверяем всегда, без ожидания
                first = false;
                try {
                    Thread.sleep(LOOP_EVERY_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "KitKatVisuals-Updater");
        t.setDaemon(true);
        t.start();
    }

    // ---------- команда ----------

    private static void registerCommand(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(literal("kkupdate")
                .executes(ctx -> {
                    checkNow();
                    return 1;
                })
                .then(literal("on").executes(ctx -> {
                    setEnabled(true);
                    return 1;
                }))
                .then(literal("off").executes(ctx -> {
                    setEnabled(false);
                    return 1;
                })));
    }

    private static void setEnabled(boolean on) {
        Cfg c = loadCfg();
        c.enabled = on;
        saveCfg(c);
        say(on ? "kitkatvisuals.update.on" : "kitkatvisuals.update.off");
    }

    private static void checkNow() {
        say("kitkatvisuals.update.checking");
        Thread t = new Thread(() -> {
            Result r = run(true);
            switch (r) {
                case UP_TO_DATE -> say("kitkatvisuals.update.latest", currentVersion());
                case READY -> say("kitkatvisuals.update.ready", readyVersion);
                case NO_RELEASE -> say("kitkatvisuals.update.no_release");
                case DEV_BUILD -> say("kitkatvisuals.update.dev");
                case BUSY -> say("kitkatvisuals.update.busy");
                default -> say("kitkatvisuals.update.failed");
            }
        }, "KitKatVisuals-Updater-Manual");
        t.setDaemon(true);
        t.start();
    }

    private static void say(String key, Object... args) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.sendMessage(Text.translatable(key, args), false);
        });
    }

    private static void flushNotice(MinecraftClient mc) {
        String v = readyVersion;
        if (v == null || shown || mc.player == null) return;
        shown = true;
        mc.player.sendMessage(Text.translatable("kitkatvisuals.update.ready", v), false);
    }

    // ---------- проверка и загрузка ----------

    public static Result run(boolean force) {
        return run(force, force);
    }

    public static Result run(boolean force, boolean ignoreWait) {
        if (!BUSY.compareAndSet(false, true)) return Result.BUSY;
        try {
            return check(force, ignoreWait);
        } catch (Exception e) {
            KitKatVisualsClient.LOGGER.warn("Updater: {}", e.toString());
            return Result.FAILED;
        } finally {
            BUSY.set(false);
        }
    }

    private static Result check(boolean force, boolean ignoreWait) throws Exception {
        Cfg cfg = loadCfg();
        if (!cfg.enabled && !force) return Result.DISABLED;
        Path jar = ownJar();
        if (jar == null) return Result.DEV_BUILD;
        String current = currentVersion();
        // мод только что обновился или запущен впервые: проверяем сразу, без ожидания
        if (!current.equals(cfg.last_version)) {
            cfg.last_version = current;
            cfg.last_check = 0;
            cfg.known_latest = "";
            saveCfg(cfg);
        }
        Path pending = jar.resolveSibling(jar.getFileName() + ".new");
        Path part = jar.resolveSibling(jar.getFileName() + ".part");
        Files.deleteIfExists(part);

        // скачано раньше, но не установлено: запоминаем, но всё равно смотрим, нет ли версии ещё новее
        String pendingVer = null;
        if (Files.isRegularFile(pending)) {
            String v = readVersion(pending);
            if (v != null && compare(v, current) > 0) pendingVer = v;
            else Files.deleteIfExists(pending);
        }
        String have = pendingVer != null ? pendingVer : current; // самая новая версия, что уже есть у игрока

        // ждём только если мы точно знаем, что новее ничего нет
        boolean throttled = !ignoreWait && System.currentTimeMillis() - cfg.last_check < CHECK_EVERY_MS
                && compare(cfg.known_latest, have) <= 0;
        if (throttled) {
            if (pendingVer != null) {
                announce(pendingVer, jar, pending);
                return Result.READY;
            }
            return Result.WAIT;
        }

        JsonObject rel = fetchLatest();
        if (rel == null) {
            if (pendingVer != null) {
                announce(pendingVer, jar, pending);
                return Result.READY;
            }
            return Result.NO_RELEASE;
        }
        cfg.last_check = System.currentTimeMillis();

        String tag = str(rel, "tag_name");
        String latest = tag.startsWith("v") ? tag.substring(1) : tag;
        cfg.known_latest = latest;
        saveCfg(cfg);
        if (latest.isEmpty() || compare(latest, have) <= 0) {
            if (pendingVer != null) {
                announce(pendingVer, jar, pending);
                return Result.READY;
            }
            return Result.UP_TO_DATE;
        }

        String url = null;
        if (rel.get("assets") != null && rel.get("assets").isJsonArray()) {
            for (JsonElement e : rel.getAsJsonArray("assets")) {
                if (!e.isJsonObject()) continue;
                String name = str(e.getAsJsonObject(), "name");
                if (name.endsWith(".jar") && !name.contains("sources") && !name.contains("dev")) {
                    url = str(e.getAsJsonObject(), "browser_download_url");
                    break;
                }
            }
        }
        if (url == null || !url.startsWith("https://github.com/" + REPO + "/releases/download/")) {
            throw new IOException("В релизе нет подходящего jar: " + url);
        }
        Matcher m = SHA.matcher(str(rel, "body"));
        if (!m.find()) throw new IOException("В описании релиза нет sha256");
        String sha = m.group(1);

        HttpURLConnection c = open(url, false);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            if (c.getContentLengthLong() > MAX_JAR_BYTES) throw new IOException("Файл слишком большой");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(part)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_JAR_BYTES) throw new IOException("Файл слишком большой");
                    md.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
            if (!HexFormat.of().formatHex(md.digest()).equalsIgnoreCase(sha)) throw new IOException("SHA-256 не совпал");
        } catch (Exception e) {
            Files.deleteIfExists(part);
            throw e;
        } finally {
            c.disconnect();
        }

        String v = readVersion(part);
        if (v == null || compare(v, current) <= 0) {
            Files.deleteIfExists(part);
            throw new IOException("Скачанный файл не является новой версией мода");
        }
        Files.move(part, pending, StandardCopyOption.REPLACE_EXISTING);
        announce(v, jar, pending);
        return Result.READY;
    }

    /** Самый новый релиз по номеру версии (а не по флагу latest): игрок всегда прыгает сразу на последнюю версию. */
    private static JsonObject fetchLatest() throws IOException {
        HttpURLConnection c = open("https://api.github.com/repos/" + REPO + "/releases?per_page=30", true);
        try {
            int code = c.getResponseCode();
            if (code == 404) return null;
            if (code != 200) throw new IOException("GitHub API: HTTP " + code);
            try (Reader r = new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8)) {
                JsonObject best = null;
                String bestVer = "";
                for (JsonElement e : JsonParser.parseReader(r).getAsJsonArray()) {
                    if (!e.isJsonObject()) continue;
                    JsonObject o = e.getAsJsonObject();
                    if (o.has("draft") && o.get("draft").getAsBoolean()) continue;
                    if (o.has("prerelease") && o.get("prerelease").getAsBoolean()) continue;
                    String tag = str(o, "tag_name");
                    String ver = tag.startsWith("v") ? tag.substring(1) : tag;
                    if (ver.isEmpty()) continue;
                    if (best == null || compare(ver, bestVer) > 0) {
                        best = o;
                        bestVer = ver;
                    }
                }
                return best;
            }
        } finally {
            c.disconnect();
        }
    }

    /** Открывает HTTPS-соединение с ручными редиректами (GitHub отдаёт файлы через другой домен). Код ответа проверяет вызывающий. */
    private static HttpURLConnection open(String url, boolean json) throws IOException {
        String cur = url;
        for (int hop = 0; hop < 6; hop++) {
            if (!cur.startsWith("https://")) throw new IOException("Разрешён только HTTPS: " + cur);
            HttpURLConnection c = (HttpURLConnection) URI.create(cur).toURL().openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(6000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", UA);
            if (json) c.setRequestProperty("Accept", "application/vnd.github+json");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("Редирект без адреса");
                cur = URI.create(cur).resolve(loc).toString();
                continue;
            }
            return c;
        }
        throw new IOException("Слишком много редиректов");
    }

    // ---------- установка ----------

    private static void announce(String version, Path jar, Path pending) {
        if (version.equals(readyVersion)) return; // уже сообщили
        readyVersion = version;
        shown = false;
        registerSwap(jar, pending);
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> flushNotice(mc));
    }

    private static synchronized void registerSwap(Path jar, Path pending) {
        if (swapRegistered) return;
        swapRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> applyOnExit(jar, pending), "KitKatVisuals-Swap"));
    }

    /** При выходе из игры: сразу подменяем jar (Linux/macOS); на Windows файл занят, поэтому это делает отдельный процесс после выхода. */
    private static void applyOnExit(Path jar, Path pending) {
        try {
            if (!Files.isRegularFile(pending)) return;
            try {
                Files.move(pending, jar, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException | RuntimeException locked) {
                // jar занят этим процессом: делегируем помощнику
            }
            Path helper = Path.of(System.getProperty("java.io.tmpdir"), "kitkatvisuals-swap.jar");
            Files.copy(jar, helper, StandardCopyOption.REPLACE_EXISTING);
            Path bin = Path.of(System.getProperty("java.home"), "bin");
            Path javaw = bin.resolve("javaw.exe");
            Path java = Files.isRegularFile(javaw) ? javaw : bin.resolve(System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
            new ProcessBuilder(java.toString(), "-cp", helper.toString(), SwapHelper.class.getName(),
                    String.valueOf(ProcessHandle.current().pid()), pending.toString(), jar.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (Exception ignored) {
            // не удалось: файл .new останется, при следующем запуске попробуем снова
        }
    }

    // ---------- вспомогательное ----------

    /** Путь к jar этого мода; null в среде разработки (там это папка). */
    private static Path ownJar() {
        try {
            ModContainer c = FabricLoader.getInstance().getModContainer(MOD_ID).orElse(null);
            if (c == null || c.getOrigin().getKind() != ModOrigin.Kind.PATH) return null;
            for (Path p : c.getOrigin().getPaths()) {
                if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")) return p;
            }
        } catch (RuntimeException ignored) {
            // нет — значит нет
        }
        return null;
    }

    private static String currentVersion() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0");
    }

    /** Версия мода из fabric.mod.json внутри jar; null, если это не наш мод или файл битый. */
    private static String readVersion(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry e = z.getEntry("fabric.mod.json");
            if (e == null) return null;
            try (Reader r = new InputStreamReader(z.getInputStream(e), StandardCharsets.UTF_8)) {
                JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
                if (!MOD_ID.equals(str(o, "id"))) return null;
                String v = str(o, "version");
                return v.isEmpty() ? null : v;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    /** Сравнение номеров вида 1.0.15: больше 0, если a новее b. */
    static int compare(String a, String b) {
        int[] x = nums(a);
        int[] y = nums(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0;
            int q = i < y.length ? y[i] : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int[] nums(String v) {
        String s = v == null ? "" : v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        for (char stop : new char[]{'-', '+'}) {
            int cut = s.indexOf(stop);
            if (cut >= 0) s = s.substring(0, cut);
        }
        String[] parts = s.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static Path cfgPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("kitkatvisuals_update.json");
    }

    private static Cfg loadCfg() {
        try (Reader r = Files.newBufferedReader(cfgPath(), StandardCharsets.UTF_8)) {
            Cfg c = GSON.fromJson(r, Cfg.class);
            return c == null ? new Cfg() : c;
        } catch (Exception e) {
            return new Cfg();
        }
    }

    private static void saveCfg(Cfg c) {
        try {
            Files.createDirectories(cfgPath().getParent());
            Files.writeString(cfgPath(), GSON.toJson(c), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) {
            // не критично
        }
    }
}
