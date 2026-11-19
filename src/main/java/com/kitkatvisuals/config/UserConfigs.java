package com.kitkatvisuals.config;

import com.kitkatvisuals.KitKatVisualsClient;
import com.kitkatvisuals.module.ModuleManager;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Именованные конфиги модулей: config/kitkatvisuals/configs/&lt;имя&gt;.json.
 * Дисковые операции вызываются только по действию пользователя (не каждый кадр).
 */
public final class UserConfigs {
    public record Entry(String name, long modified) { }

    private static String active = "";
    private static boolean activeLoaded;

    private UserConfigs() {}

    private static Path root() {
        return FabricLoader.getInstance().getConfigDir().resolve("kitkatvisuals");
    }

    public static Path folder() {
        Path d = root().resolve("configs");
        try {
            Files.createDirectories(d);
        } catch (IOException ignored) {
            // не критично: ниже операции сами сообщат об ошибке
        }
        return d;
    }

    /** Убирает из имени всё, что нельзя в имени файла. Пустая строка: имя непригодно. */
    public static String sanitize(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").replace("..", "").trim();
        if (s.length() > 32) s = s.substring(0, 32).trim();
        return s;
    }

    private static Path fileFor(String name) {
        String n = sanitize(name);
        if (n.isEmpty()) return null;
        Path dir = folder().toAbsolutePath().normalize();
        Path f = dir.resolve(n + ".json").normalize();
        return f.startsWith(dir) ? f : null;
    }

    public static List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        try (Stream<Path> st = Files.list(folder())) {
            st.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                String fn = p.getFileName().toString();
                long t = 0L;
                try {
                    t = Files.getLastModifiedTime(p).toMillis();
                } catch (IOException ignored) {
                    // оставим 0
                }
                out.add(new Entry(fn.substring(0, fn.length() - 5), t));
            });
        } catch (IOException e) {
            KitKatVisualsClient.LOGGER.warn("Не удалось прочитать список конфигов: {}", e.toString());
        }
        out.sort(Comparator.comparing(e -> e.name().toLowerCase()));
        return out;
    }

    /** Сохраняет (или перезаписывает) конфиг с текущими настройками модулей. */
    public static boolean save(String name) {
        Path f = fileFor(name);
        if (f == null) return false;
        try {
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, ModuleManager.exportJson(), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            setActive(sanitize(name));
            return true;
        } catch (IOException | RuntimeException e) {
            KitKatVisualsClient.LOGGER.warn("Не удалось сохранить конфиг: {}", e.toString());
            return false;
        }
    }

    public static boolean load(String name) {
        Path f = fileFor(name);
        if (f == null || !Files.isRegularFile(f)) return false;
        try {
            boolean ok = ModuleManager.importJson(Files.readString(f, StandardCharsets.UTF_8));
            if (ok) setActive(sanitize(name));
            return ok;
        } catch (IOException | RuntimeException e) {
            KitKatVisualsClient.LOGGER.warn("Не удалось прочитать конфиг: {}", e.toString());
            return false;
        }
    }

    public static boolean delete(String name) {
        Path f = fileFor(name);
        if (f == null) return false;
        try {
            boolean ok = Files.deleteIfExists(f);
            if (ok && sanitize(name).equals(active())) setActive("");
            return ok;
        } catch (IOException e) {
            KitKatVisualsClient.LOGGER.warn("Не удалось удалить конфиг: {}", e.toString());
            return false;
        }
    }

    // ---------------- активный конфиг ----------------

    private static Path activeFile() {
        return root().resolve("active_config.txt");
    }

    public static String active() {
        if (!activeLoaded) {
            activeLoaded = true;
            try {
                Path p = activeFile();
                if (Files.isRegularFile(p)) active = Files.readString(p, StandardCharsets.UTF_8).trim();
            } catch (IOException ignored) {
                active = "";
            }
        }
        return active;
    }

    private static void setActive(String name) {
        active = name == null ? "" : name;
        activeLoaded = true;
        try {
            Files.createDirectories(root());
            Files.writeString(activeFile(), active, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // не критично
        }
    }
}
