package com.kitkatvisuals.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kitkatvisuals.KitKatVisualsClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.Resource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Косметический предмет: геометрия (квады в «блокбенчных» пикселях, перед игрока = -Z, вверх = +Y, +X = правая сторона игрока)
 * из assets/kitkatvisuals/cosmetics/<id>.json. Файлы получены конвертацией .bbmodel: повороты элементов уже применены,
 * UV нормализованы. quads: по 20 чисел на квад (4 вершины x, y, z, u, v).
 */
public final class CosmeticModel {
    /** Список косметики. Чтобы добавить новую, положите <id>.json и текстуры в assets и допишите id сюда. */
    public static final String[] IDS = {"fallen_angel_wings", "angel_halo"};
    private static List<CosmeticModel> all;

    public final String id;
    public final Identifier texture;
    public final Identifier glow;
    public final float ax, ay, az;
    /** Положение и размер по умолчанию. */
    public final float defX, defY, defZ, defScale;
    public final float[] quads;

    private CosmeticModel(String id, Identifier texture, Identifier glow, float[] anchor, float[] def, float[] quads) {
        this.id = id;
        this.texture = texture;
        this.glow = glow;
        this.ax = anchor[0];
        this.ay = anchor[1];
        this.az = anchor[2];
        this.defX = def[0];
        this.defY = def[1];
        this.defZ = def[2];
        this.defScale = def[3];
        this.quads = quads;
    }

    public String title() {
        return Text.translatableWithFallback("kitkatvisuals.cosmetic." + id, id).getString();
    }

    public static synchronized List<CosmeticModel> all() {
        if (all == null) {
            List<CosmeticModel> out = new ArrayList<>();
            for (String id : IDS) {
                CosmeticModel m = load(id);
                if (m != null) out.add(m);
            }
            all = out;
        }
        return all;
    }

    public static CosmeticModel byId(String id) {
        for (CosmeticModel m : all()) if (m.id.equals(id)) return m;
        return null;
    }

    private static CosmeticModel load(String id) {
        try {
            Optional<Resource> res = MinecraftClient.getInstance().getResourceManager()
                    .getResource(Identifier.of(KitKatVisualsClient.MOD_ID, "cosmetics/" + id + ".json"));
            if (res.isEmpty()) return null;
            try (BufferedReader r = res.get().getReader()) {
                JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
                float[] anchor = floats(o.getAsJsonArray("anchor"), 3);
                float[] def = new float[]{0f, 0f, 0f, 1f};
                if (o.has("def")) {
                    JsonObject d = o.getAsJsonObject("def");
                    if (d.has("ox")) def[0] = d.get("ox").getAsFloat();
                    if (d.has("oy")) def[1] = d.get("oy").getAsFloat();
                    if (d.has("oz")) def[2] = d.get("oz").getAsFloat();
                    if (d.has("scale")) def[3] = d.get("scale").getAsFloat();
                }
                JsonArray q = o.getAsJsonArray("q");
                float[] quads = new float[q.size()];
                for (int i = 0; i < quads.length; i++) quads[i] = q.get(i).getAsFloat();
                String tex = o.get("texture").getAsString();
                String glow = o.has("glow") ? o.get("glow").getAsString() : null;
                return new CosmeticModel(id,
                        Identifier.of(KitKatVisualsClient.MOD_ID, "textures/cosmetics/" + tex + ".png"),
                        glow == null ? null : Identifier.of(KitKatVisualsClient.MOD_ID, "textures/cosmetics/" + glow + ".png"),
                        anchor, def, quads);
            }
        } catch (Exception e) {
            KitKatVisualsClient.LOGGER.error("Не удалось загрузить косметику {}", id, e);
            return null;
        }
    }

    private static float[] floats(JsonArray a, int n) {
        float[] f = new float[n];
        for (int i = 0; i < n && i < a.size(); i++) f[i] = a.get(i).getAsFloat();
        return f;
    }
}
