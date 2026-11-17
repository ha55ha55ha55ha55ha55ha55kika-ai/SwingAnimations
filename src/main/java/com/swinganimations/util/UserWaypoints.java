package com.swinganimations.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.swinganimations.SwingAnimationsClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Свои метки мини-карты. Хранятся по ключу «сервер | измерение» в config/swinganimations/waypoints.json,
 * на диск пишутся только при добавлении и удалении метки (не каждый кадр).
 */
public final class UserWaypoints {
    public static final class Pt {
        public String name = "";
        public double x, y, z;
        public int rgb = 0x4FC3F7;

        public Pt() {}

        public Pt(String name, double x, double y, double z, int rgb) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.rgb = rgb;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int[] PALETTE = {0x4FC3F7, 0xFFB74D, 0x81C784, 0xBA68C8, 0xF06292, 0xFFF176};
    private static final int MAX_PER_WORLD = 200;

    private static Map<String, List<Pt>> all;

    private UserWaypoints() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("swinganimations").resolve("waypoints.json");
    }

    private static Map<String, List<Pt>> data() {
        if (all != null) return all;
        all = new LinkedHashMap<>();
        Path p = file();
        if (!Files.isRegularFile(p)) return all;
        try {
            Type type = new TypeToken<Map<String, List<Pt>>>() { }.getType();
            Map<String, List<Pt>> in = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), type);
            if (in != null) all.putAll(in);
        } catch (IOException | RuntimeException e) {
            SwingAnimationsClient.LOGGER.warn("Не удалось прочитать метки мини-карты: {}", e.toString());
        }
        return all;
    }

    private static void save() {
        try {
            Path p = file();
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling("waypoints.json.tmp");
            Files.writeString(tmp, GSON.toJson(data()), StandardCharsets.UTF_8);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            SwingAnimationsClient.LOGGER.warn("Не удалось сохранить метки мини-карты: {}", e.toString());
        }
    }

    /** Ключ текущего мира: адрес сервера (или singleplayer) и измерение. */
    private static String key(MinecraftClient mc) {
        String server = mc.getCurrentServerEntry() != null && mc.getCurrentServerEntry().address != null
                ? mc.getCurrentServerEntry().address.toLowerCase() : "singleplayer";
        String dim = mc.world != null ? mc.world.getRegistryKey().getValue().toString() : "?";
        return server + "|" + dim;
    }

    /** Метки текущего мира (живой список: не изменять снаружи). */
    public static List<Pt> list(MinecraftClient mc) {
        List<Pt> l = data().get(key(mc));
        return l == null ? List.of() : l;
    }

    public static Pt add(MinecraftClient mc, String name, double x, double y, double z) {
        List<Pt> l = data().computeIfAbsent(key(mc), k -> new ArrayList<>());
        if (l.size() >= MAX_PER_WORLD) l.remove(0);
        Pt pt = new Pt(name, x, y, z, PALETTE[l.size() % PALETTE.length]);
        l.add(pt);
        save();
        return pt;
    }

    /** Удаляет ближайшую метку в радиусе maxDist блоков. Возвращает её или null. */
    public static Pt removeNearest(MinecraftClient mc, double x, double z, double maxDist) {
        List<Pt> l = data().get(key(mc));
        if (l == null || l.isEmpty()) return null;
        Pt best = null;
        double bd = maxDist * maxDist;
        for (Pt p : l) {
            double d = (p.x - x) * (p.x - x) + (p.z - z) * (p.z - z);
            if (d <= bd) {
                bd = d;
                best = p;
            }
        }
        if (best != null) {
            l.remove(best);
            save();
        }
        return best;
    }
}
