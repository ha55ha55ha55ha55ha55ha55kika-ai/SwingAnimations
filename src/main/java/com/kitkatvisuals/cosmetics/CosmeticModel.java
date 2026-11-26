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
    public static final String[] IDS = {"fallen_angel_wings", "angel_halo", "dragon_wings", "backpack", "diamond_sword_back"};
    private static List<CosmeticModel> all;

    public final String id;
    public final Identifier texture;
    public final Identifier glow;
    public final float ax, ay, az;
    /** Положение и размер по умолчанию. */
    public final float defX, defY, defZ, defScale;
    /** Квады в позе покоя (для анимированной модели: поза в момент 0). */
    public final float[] quads;
    /** Кости с анимацией (поворот/масштаб по ключевым кадрам); null у статичной косметики. */
    public final CosmeticPose.Bone[] bones;
    public final float animLen;
    public final boolean animLoop;
    private float[] scratch;

    private CosmeticModel(String id, Identifier texture, Identifier glow, float[] anchor, float[] def, float[] quads,
                          CosmeticPose.Bone[] bones, float animLen, boolean animLoop) {
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
        this.bones = bones;
        this.animLen = animLen;
        this.animLoop = animLoop;
    }

    public boolean animated() {
        return bones != null;
    }

    /** Поза в момент t (секунды) в общий буфер модели: пользоваться сразу (эмитить квады), не хранить. Только для потока рендера. */
    public float[] pose(float t) {
        if (scratch == null) scratch = new float[quads.length];
        CosmeticPose.pose(bones, animLen, animLoop, t, scratch);
        return scratch;
    }

    /** Поза в новый массив (для отложенной отрисовки, например в гардеробе). */
    public float[] poseCopy(float t) {
        float[] out = new float[quads.length];
        CosmeticPose.pose(bones, animLen, animLoop, t, out);
        return out;
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
                CosmeticPose.Bone[] bones = null;
                float animLen = 1f;
                boolean animLoop = true;
                float[] quads;
                if (o.has("bones")) {                               // анимированная модель: квады лежат в костях
                    JsonArray ba = o.getAsJsonArray("bones");
                    bones = new CosmeticPose.Bone[ba.size()];
                    for (int i = 0; i < bones.length; i++) {
                        JsonObject b = ba.get(i).getAsJsonObject();
                        JsonArray bq = b.getAsJsonArray("q");
                        float[] qq = new float[bq.size()];
                        for (int k = 0; k < qq.length; k++) qq[k] = bq.get(k).getAsFloat();
                        bones[i] = new CosmeticPose.Bone(b.get("n").getAsString(), b.get("p").getAsInt(),
                                floats(b.getAsJsonArray("pv"), 3), floats(b.getAsJsonArray("r"), 3), qq,
                                keys(b, "ar"), b.has("ir") && "c".equals(b.get("ir").getAsString()),
                                keys(b, "as"), b.has("is") && "c".equals(b.get("is").getAsString()));
                    }
                    if (o.has("len")) animLen = Math.max(0.05f, o.get("len").getAsFloat());
                    if (o.has("loop")) animLoop = o.get("loop").getAsBoolean();
                    quads = new float[CosmeticPose.totalFloats(bones)];
                    CosmeticPose.pose(bones, animLen, animLoop, 0f, quads);
                } else {
                    JsonArray q = o.getAsJsonArray("q");
                    quads = new float[q.size()];
                    for (int i = 0; i < quads.length; i++) quads[i] = q.get(i).getAsFloat();
                }
                String tex = o.get("texture").getAsString();
                String glow = o.has("glow") ? o.get("glow").getAsString() : null;
                return new CosmeticModel(id,
                        Identifier.of(KitKatVisualsClient.MOD_ID, "textures/cosmetics/" + tex + ".png"),
                        glow == null ? null : Identifier.of(KitKatVisualsClient.MOD_ID, "textures/cosmetics/" + glow + ".png"),
                        anchor, def, quads, bones, animLen, animLoop);
            }
        } catch (Exception e) {
            KitKatVisualsClient.LOGGER.error("Не удалось загрузить косметику {}", id, e);
            return null;
        }
    }

    /** Ключевые кадры канала: [[время, x, y, z], ...] или null. */
    private static float[][] keys(JsonObject b, String name) {
        if (!b.has(name)) return null;
        JsonArray a = b.getAsJsonArray(name);
        float[][] k = new float[a.size()][];
        for (int i = 0; i < k.length; i++) k[i] = floats(a.get(i).getAsJsonArray(), 4);
        return k;
    }

    private static float[] floats(JsonArray a, int n) {
        float[] f = new float[n];
        for (int i = 0; i < n && i < a.size(); i++) f[i] = a.get(i).getAsFloat();
        return f;
    }
}
