package com.kitkatvisuals.link;

import java.util.Locale;
import java.util.Set;

/**
 * «Сеть» серверов по адресу: mc.holyworld.me и neo.holyworld.ru это один проект, поэтому ключ у них один: "holyworld".
 * Нужен, чтобы косметика и метки друзей работали, даже если игроки заходят на разные адреса одной сети.
 * Алгоритм обязан совпадать с net_key() в kitkat_relay.py. IP-адреса (с портом) не склеиваются.
 */
public final class Network {
    private Network() {}

    private static final Set<String> SLD = Set.of("co", "com", "org", "net", "gov", "edu", "ac");

    public static String key(String address) {
        if (address == null) return "";
        String h = address.toLowerCase(Locale.ROOT).trim();
        if (h.isEmpty() || h.startsWith("[")) return h;
        String host = h.matches(".*:\\d+$") ? h.substring(0, h.lastIndexOf(':')) : h;
        if (host.matches("[0-9.]+")) return h;
        String[] raw = host.split("\\.");
        int n = 0;
        String[] p = new String[raw.length];
        for (String x : raw) if (!x.isEmpty()) p[n++] = x;
        if (n >= 3 && p[n - 1].length() == 2 && SLD.contains(p[n - 2])) return p[n - 3];
        if (n >= 2) return p[n - 2];
        return host;
    }

    public static boolean same(String a, String b) {
        return key(a).equals(key(b));
    }
}
