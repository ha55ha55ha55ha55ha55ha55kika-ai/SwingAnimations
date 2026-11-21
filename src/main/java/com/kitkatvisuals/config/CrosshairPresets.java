package com.kitkatvisuals.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kitkatvisuals.KitKatVisualsClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Пресеты редактора прицела: config/kitkatvisuals/crosshairs/&lt;имя&gt;.json.
 * Диск трогается только по действию игрока (сохранить, загрузить, удалить, обновить список).
 */
public final class CrosshairPresets {
    /** Формат файла. grid: 15 строк по 15 символов, '1' = закрашенная клетка. */
    public static final class Data {
        public int version = 1;
        public String name = "";
        public List<String> grid = new ArrayList<>();
        public int pixelSize = 2;
        public int gap = 0;
        public String color = "#FFFFFF";
        public boolean outline = true;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private CrosshairPresets() {}

    public static Path folder() {
        Path d = FabricLoader.getInstance().getConfigDir().resolve("kitkatvisuals").resolve("crosshairs");
        try {
            Files.createDirectories(d);
        } catch (IOException ignored) {
            // ошибки будут видны при сохранении
        }
        return d;
    }

    private static Path fileFor(String name) {
        String n = UserConfigs.sanitize(name);
        if (n.isEmpty()) return null;
        Path dir = folder().toAbsolutePath().normalize();
        Path f = dir.resolve(n + ".json").normalize();
        return f.startsWith(dir) ? f : null;
    }

    public static List<String> list() {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(folder())) {
            s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(p -> {
                String n = p.getFileName().toString();
                out.add(n.substring(0, n.length() - 5));
            });
        } catch (IOException e) {
            KitKatVisualsClient.LOGGER.warn("Crosshair presets: список недоступен: {}", e.toString());
        }
        return out;
    }

    /** true, если сохранено. */
    public static boolean save(String name, Data d) {
        Path f = fileFor(name);
        if (f == null) return false;
        try {
            d.name = UserConfigs.sanitize(name);
            Files.writeString(f, GSON.toJson(d), StandardCharsets.UTF_8);
            return true;
        } catch (IOException | RuntimeException e) {
            KitKatVisualsClient.LOGGER.warn("Crosshair presets: не сохранить '{}': {}", name, e.toString());
            return false;
        }
    }

    /** null, если файла нет или он повреждён. */
    public static Data load(String name) {
        Path f = fileFor(name);
        if (f == null || !Files.isRegularFile(f)) return null;
        try {
            Data d = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Data.class);
            if (d == null || d.grid == null) return null;
            return d;
        } catch (IOException | RuntimeException e) {
            KitKatVisualsClient.LOGGER.warn("Crosshair presets: не прочитать '{}': {}", name, e.toString());
            return null;
        }
    }

    public static boolean delete(String name) {
        Path f = fileFor(name);
        try {
            return f != null && Files.deleteIfExists(f);
        } catch (IOException e) {
            return false;
        }
    }
}
