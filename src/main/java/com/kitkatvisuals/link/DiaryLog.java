package com.kitkatvisuals.link;

import com.google.gson.JsonObject;
import com.kitkatvisuals.KitKatVisualsClient;
import com.kitkatvisuals.module.ClientLinkModule;
import com.kitkatvisuals.module.ModuleManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Команда "/l <текст>" на нашем сервере — личный дневник игрока (своя игровая механика, не логин).
 * Когда игрок отправляет эту команду, копия текста уходит в лог на хостинге (то же реле, что и ClientLink,
 * путь /diary), чтобы дневники разных игроков собирались в одном месте. Команда в игре работает как раньше,
 * это только дополнительная копия в лог. Используются тот же relay_url/relay_token, что заданы в модуле
 * ClientLink (ClickGUI их не показывает, но конфиг общий: config/kitkatvisuals_modules.json).
 */
public final class DiaryLog {
    /** Имя команды без ведущего слеша: "/l текст" -> ClientPlayNetworkHandler.sendChatCommand("l текст"). */
    private static final String COMMAND = "l";
    private static final int MAX_TEXT = 400;

    private static volatile ScheduledExecutorService exec;
    private static volatile boolean warnedAuth;

    private DiaryLog() {}

    /** Вызывается из ChatCommandMixin на каждую отправленную команду; сам решает, наша ли это команда. */
    public static void maybeLog(String command) {
        String text = stripPrefix(command);
        if (text == null || text.isEmpty()) return; // не /l, либо /l без текста

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        ClientLinkModule m = ModuleManager.clientLink;
        if (m == null) return;
        String url = m.relayUrl();
        if (url.isEmpty()) return;
        String token = m.relayToken();

        String server = serverId(mc);
        if (server.isEmpty()) return; // не на сервере из списка (одиночная игра / LAN) — слать некуда и незачем
        UUID uuid = mc.player.getUuid();
        String name = mc.player.getName().getString();
        String cleanText = clean(text);
        if (cleanText.isEmpty()) return;

        ScheduledExecutorService ex = exec;
        if (ex == null) {
            ex = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "KitKatDiary-Http");
                t.setDaemon(true);
                return t;
            });
            exec = ex;
        }
        ex.execute(() -> send(mc, url, token, server, uuid, name, cleanText));
    }

    private static void send(MinecraftClient mc, String url, String token, String server, UUID uuid, String name, String text) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("server", server);
            o.addProperty("uuid", uuid.toString());
            o.addProperty("name", name);
            o.addProperty("text", text);
            ClientLink.postDiary(url + "/diary", o.toString(), token);
        } catch (Throwable t) {
            KitKatVisualsClient.LOGGER.debug("DiaryLog: не отправлено ({})", t.toString());
            if (t.getMessage() != null && t.getMessage().contains("HTTP 403") && !warnedAuth) {
                warnedAuth = true;
                mc.execute(() -> mc.inGameHud.setOverlayMessage(
                        Text.literal("Дневник: реле отклонило токен (403), проверьте relay_token"), false));
            }
        }
    }

    /**
     * "l текст" / "L   текст" -> "текст"; "lock", "locate" и т.п. -> null (это не наша команда, просто
     * совпадает первая буква). "l" без текста -> "" (пустую строку отбрасывает вызывающий код).
     */
    private static String stripPrefix(String command) {
        if (command == null) return null;
        String c = command.stripLeading();
        int len = COMMAND.length();
        if (c.length() < len || !c.regionMatches(true, 0, COMMAND, 0, len)) return null;
        if (c.length() > len && !Character.isWhitespace(c.charAt(len))) return null; // "locate" и т.д.
        return c.substring(Math.min(len, c.length())).stripLeading();
    }

    private static String clean(String s) {
        StringBuilder b = new StringBuilder(Math.min(s.length(), MAX_TEXT));
        for (int i = 0; i < s.length() && b.length() < MAX_TEXT; i++) {
            char c = s.charAt(i);
            if (c < 32 || c == '\u00A7' || c == 127) continue; // управляющие символы и форматирование Minecraft (§)
            b.append(c);
        }
        return b.toString().trim();
    }

    private static String serverId(MinecraftClient mc) {
        ServerInfo si = mc.getCurrentServerEntry();
        return si == null ? "" : ClientLink.normalizeServer(si.address);
    }
}
