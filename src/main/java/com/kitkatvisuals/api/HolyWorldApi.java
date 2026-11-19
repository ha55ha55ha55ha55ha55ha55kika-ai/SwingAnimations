package com.kitkatvisuals.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kitkatvisuals.KitKatVisualsClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Публичный API HolyWorld (без токена). GET /v1/events: {"LITE_ANARCHY_16": [{instanceId, id, metadata: {displayName, rare}}], ...}.
 * GET /v1/votings: {"LITE_ANARCHY_46": [{instanceId, id, candidates: [{id, name, votes, metadata: {displayName, rare}}]}], ...}
 * (сервера без голосований в ответ не попадают). Запросы идут в фоне, игровой поток не ждёт. Ошибки сети оставляют прошлые данные.
 * Опрос общий для всех модулей: start() считает потребителей, stop() гасит опрос, когда последний потребитель ушёл.
 */
public final class HolyWorldApi {
    private static final String URL = "https://api.holyworld.me/v1/events";
    private static final String VOTINGS_URL = "https://api.holyworld.me/v1/votings";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public record Event(String server, String id, String name, String rare) { }

    /** Вариант в голосовании за ивент. */
    public record Candidate(String id, String name, int votes, String rare) { }

    /** Одно идущее голосование на сервере. Варианты уже отсортированы по голосам (больше первым). */
    public record Voting(String server, String id, List<Candidate> candidates) {
        public int total() {
            int t = 0;
            for (Candidate c : candidates) t += Math.max(0, c.votes());
            return t;
        }

        /** Лидер голосования (при равенстве первый из ответа); null, если вариантов нет. */
        public Candidate leader() { return candidates.isEmpty() ? null : candidates.get(0); }
    }

    private static volatile List<Event> latest = Collections.emptyList();
    private static volatile long updatedAt;
    private static volatile List<Voting> votings = Collections.emptyList();
    private static volatile long votingsAt;
    private static ScheduledExecutorService pool;
    private static int users;

    /** На лайте ровно 36 анархий (1–36). Всё, что выше, в списке сервера не существует. */
    public static final int MAX_ANARCHY = 36;

    private HolyWorldApi() {}

    /** Последний успешно полученный список (может быть пустым). */
    public static List<Event> latest() { return latest; }

    public static long updatedAt() { return updatedAt; }

    /** Идущие голосования (по всем серверам). Может быть пустым. */
    public static List<Voting> votings() { return votings; }

    /** true: список голосований получен недавно (не старше maxAgeMs), ему можно верить. */
    public static boolean votingsFresh(long maxAgeMs) {
        return votingsAt != 0L && System.currentTimeMillis() - votingsAt <= maxAgeMs;
    }

    /** Подключает потребителя и запускает опрос раз в intervalSec секунд (интервал берётся от первого потребителя). */
    public static synchronized void start(int intervalSec) {
        users++;
        if (pool != null) return;
        pool = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kitkatvisuals-holyworld");
            t.setDaemon(true);
            return t;
        });
        pool.scheduleAtFixedRate(HolyWorldApi::fetchSafe, 0, intervalSec, TimeUnit.SECONDS);
    }

    /** Отключает потребителя; опрос останавливается, когда их не осталось. */
    public static synchronized void stop() {
        if (users > 0) users--;
        if (users > 0) return;
        if (pool != null) pool.shutdownNow();
        pool = null;
    }

    private static void fetchSafe() {
        try {
            String body = get(URL);
            if (body != null) {
                latest = parse(body);
                updatedAt = System.currentTimeMillis();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Throwable t) {
            // сеть недоступна: оставляем прошлый список, игра не страдает
        }
        try { // голосования независимы от ивентов: сбой одного запроса не должен глушить другой
            String body = get(VOTINGS_URL);
            if (body != null) {
                votings = parseVotings(body);
                votingsAt = System.currentTimeMillis();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            // оставляем прошлые голосования; HUD сам перестанет их показывать, когда они устареют
        }
    }

    /** Тело ответа при коде 200, иначе null. */
    private static String get(String url) throws java.io.IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.statusCode() == 200 ? resp.body() : null;
    }

    /** Разбор /v1/votings без параметра server: объект {сервер: [голосования]}. */
    public static List<Voting> parseVotings(String json) {
        List<Voting> out = new ArrayList<>();
        JsonElement rootEl = JsonParser.parseString(json);
        if (!rootEl.isJsonObject()) return out;
        for (Map.Entry<String, JsonElement> e : new TreeMap<>(rootEl.getAsJsonObject().asMap()).entrySet()) {
            if (!e.getValue().isJsonArray()) continue;
            for (JsonElement el : e.getValue().getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                List<Candidate> list = new ArrayList<>();
                if (o.has("candidates") && o.get("candidates").isJsonArray()) {
                    for (JsonElement ce : o.getAsJsonArray("candidates")) {
                        if (!ce.isJsonObject()) continue;
                        JsonObject c = ce.getAsJsonObject();
                        String cid = str(c, "id", "?");
                        String name = str(c, "name", cid);
                        String rare = "default";
                        if (c.has("metadata") && c.get("metadata").isJsonObject()) {
                            JsonObject m = c.getAsJsonObject("metadata");
                            name = str(m, "displayName", name);
                            rare = str(m, "rare", "default");
                        }
                        int votes = 0;
                        if (c.has("votes") && c.get("votes").isJsonPrimitive()) {
                            try { votes = c.get("votes").getAsInt(); } catch (NumberFormatException ignored) { }
                        }
                        list.add(new Candidate(cid, name, votes, rare));
                    }
                }
                if (list.isEmpty()) continue;
                list.sort((a, b) -> Integer.compare(b.votes(), a.votes())); // List.sort стабильна: при равенстве порядок ответа
                out.add(new Voting(e.getKey(), str(o, "id", "?"), List.copyOf(list)));
            }
        }
        return out;
    }

    /** Разбор ответа. Отдельный метод, чтобы его можно было проверить на сохранённом JSON. */
    public static List<Event> parse(String json) {
        List<Event> out = new ArrayList<>();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        for (Map.Entry<String, JsonElement> e : new TreeMap<>(root.asMap()).entrySet()) {
            if (!e.getValue().isJsonArray()) continue;
            JsonArray arr = e.getValue().getAsJsonArray();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String id = str(o, "id", "?");
                String name = id;
                String rare = "default";
                if (o.has("metadata") && o.get("metadata").isJsonObject()) {
                    JsonObject m = o.getAsJsonObject("metadata");
                    name = str(m, "displayName", id);
                    rare = str(m, "rare", "default");
                }
                out.add(new Event(e.getKey(), id, name, rare));
            }
        }
        return out;
    }

    private static String str(JsonObject o, String k, String def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : def;
    }

    /** Номер анархии из ключа сервера (число после последнего «_»); -1, если номера нет. */
    public static int number(String server) {
        int i = server.lastIndexOf('_');
        if (i < 0 || i == server.length() - 1) return -1;
        try {
            return Integer.parseInt(server.substring(i + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Ключ сервера вида LITE_ANARCHY_17 или LITE_NEW_ANARCHY_17 заканчивается на _N. */
    public static boolean onAnarchy(String server, int n) {
        return n <= 0 || server.endsWith("_" + n);
    }
}
