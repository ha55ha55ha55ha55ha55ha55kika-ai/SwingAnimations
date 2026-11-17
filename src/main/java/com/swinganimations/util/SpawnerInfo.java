package com.swinganimations.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Достаёт из предмета-спавнера, что внутри: моб, меч и его зачарования. Формат данных зависит от сервера, поэтому разбор
 * терпимый: ищет в custom_data, block_entity_data и lore; понимает NBT, JSON и Base64 (сжатый и обычный NBT).
 * Если на вашем сервере данные лежат иначе, запишите предмет клавишей «Spawner dump» и подстройте разбор по файлу.
 */
public final class SpawnerInfo {
    public static final SpawnerInfo NONE = new SpawnerInfo(null, null, null, new ArrayList<>());

    /** Показываемое имя моба (например «Зомби») и его id (minecraft:zombie), либо null. */
    public final String mobName;
    public final String mobId;
    /** Показываемое имя меча (например «Алмазный меч»), либо null. */
    public final String swordName;
    public final List<Ench> enchants;

    public record Ench(String id, int level) {
        public String shortId() {
            int i = id.indexOf(':');
            return i >= 0 ? id.substring(i + 1) : id;
        }

        public Text text() {
            Identifier ident = Identifier.tryParse(id);
            String key = ident == null ? "enchantment.minecraft." + shortId() : "enchantment." + ident.getNamespace() + "." + ident.getPath();
            Text lvl = level >= 1 && level <= 10 ? Text.translatable("enchantment.level." + level) : Text.literal(String.valueOf(level));
            return Text.translatable(key).append(" ").append(lvl);
        }
    }

    private SpawnerInfo(String mobName, String mobId, String swordName, List<Ench> enchants) {
        this.mobName = mobName;
        this.mobId = mobId;
        this.swordName = swordName;
        this.enchants = enchants;
    }

    public boolean isEmpty() { return mobName == null && swordName == null; }

    public int level(String enchantPath) {
        int best = 0;
        for (Ench e : enchants) if (e.shortId().equals(enchantPath)) best = Math.max(best, e.level());
        return best;
    }

    public int maxLevel() {
        int best = 0;
        for (Ench e : enchants) best = Math.max(best, e.level());
        return best;
    }

    public Ench best() {
        Ench b = null;
        for (Ench e : enchants) if (b == null || e.level() > b.level()) b = e;
        return b;
    }

    // ---------------- кэш ----------------

    private static final Map<ItemStack, SpawnerInfo> CACHE = new IdentityHashMap<>();

    /** Данные спавнера в стеке (null, если это не спавнер или внутри ничего не нашли). Результат кэшируется по экземпляру стека. */
    public static SpawnerInfo of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        SpawnerInfo c = CACHE.get(stack);
        if (c == null) {
            if (CACHE.size() > 600) CACHE.clear();
            try {
                c = parse(stack);
            } catch (Throwable t) {
                c = NONE;
            }
            CACHE.put(stack, c);
        }
        return c == NONE ? null : c;
    }

    public static boolean looksLikeSpawner(ItemStack stack) {
        if (stack.isOf(Items.SPAWNER)) return true;
        String name = stack.getName().getString().toLowerCase(Locale.ROOT);
        return name.contains("спавнер") || name.contains("spawner");
    }

    // ---------------- разбор ----------------

    private static final class Acc {
        String mobId;
        String swordId;
        final List<Ench> enchants = new ArrayList<>();
        boolean swordFound;
    }

    private static SpawnerInfo parse(ItemStack stack) {
        if (!looksLikeSpawner(stack)) return NONE;
        Acc a = new Acc();
        NbtComponent custom = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (custom != null) scan(custom.copyNbt(), a, 0, "");
        NbtComponent bed = stack.get(DataComponentTypes.BLOCK_ENTITY_DATA);
        if (bed != null) scan(bed.copyNbt(), a, 0, "");
        NbtComponent ent = stack.get(DataComponentTypes.ENTITY_DATA);
        if (ent != null) scan(ent.copyNbt(), a, 0, "");
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore != null) scanLore(lore, a);

        String mobName = null;
        if (a.mobId != null) {
            Identifier id = Identifier.tryParse(a.mobId);
            if (id != null && Registries.ENTITY_TYPE.containsId(id)) mobName = Registries.ENTITY_TYPE.get(id).getName().getString();
        }
        String swordName = null;
        if (a.swordId != null) {
            Identifier id = Identifier.tryParse(a.swordId);
            if (id != null && Registries.ITEM.containsId(id)) swordName = Registries.ITEM.get(id).getName().getString();
        }
        if (mobName == null && swordName == null) return NONE;
        return new SpawnerInfo(mobName, a.mobId, swordName, a.enchants);
    }

    private static final Pattern LORE_MOB = Pattern.compile("(?i)(?:моб|mob|существо|тип)\\s*[:：]\\s*(.+)");
    private static final Pattern LORE_SWORD = Pattern.compile("(?i)(?:меч|sword)\\s*[:：]\\s*(.+)");

    private static void scanLore(LoreComponent lore, Acc a) {
        for (Text line : lore.lines()) {
            String s = line.getString().replaceAll("§.", "").trim();
            Matcher m = LORE_MOB.matcher(s);
            if (a.mobId == null && m.find()) a.mobId = matchEntityByName(m.group(1).trim());
            m = LORE_SWORD.matcher(s);
            if (a.swordId == null && m.find()) a.swordId = matchSwordByName(m.group(1).trim());
        }
    }

    private static String matchEntityByName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (EntityType<?> t : Registries.ENTITY_TYPE) {
            if (t.getSpawnGroup() == SpawnGroup.MISC) continue;
            if (t.getName().getString().toLowerCase(Locale.ROOT).equals(n)) return Registries.ENTITY_TYPE.getId(t).toString();
        }
        return normalizeEntity(name);
    }

    private static String matchSwordByName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (Item it : Registries.ITEM) {
            Identifier id = Registries.ITEM.getId(it);
            if (!id.getPath().endsWith("_sword")) continue;
            if (it.getName().getString().toLowerCase(Locale.ROOT).equals(n)) return id.toString();
        }
        return null;
    }

    /** Превращает «ZOMBIE», «zombie» или «minecraft:zombie» в id существа, если такое существо есть. */
    private static String normalizeEntity(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (!s.contains(":")) s = "minecraft:" + s;
        Identifier id = Identifier.tryParse(s);
        if (id == null || !Registries.ENTITY_TYPE.containsId(id)) return null;
        EntityType<?> t = Registries.ENTITY_TYPE.get(id);
        return t.getSpawnGroup() == SpawnGroup.MISC ? null : id.toString();
    }

    private static boolean mobKey(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.equals("id") || k.contains("mob") || k.contains("entity") || k.equals("type") || k.contains("spawn");
    }

    private static void scan(NbtElement el, Acc a, int depth, String key) {
        if (el == null || depth > 12) return;
        byte type = el.getType();
        if (type == NbtElement.COMPOUND_TYPE) {
            NbtCompound c = (NbtCompound) el;
            if (c.contains("id", NbtElement.STRING_TYPE)) {
                String id = c.getString("id");
                if (id.endsWith("_sword") && Identifier.tryParse(id) != null && Registries.ITEM.containsId(Identifier.tryParse(id))) {
                    if (!a.swordFound) {
                        a.swordFound = true;
                        a.swordId = id;
                        readEnchants(c, a);
                    }
                } else if (a.mobId == null && mobKey(key.isEmpty() ? "id" : key)) {
                    a.mobId = normalizeEntity(id);
                }
            }
            for (String k : c.getKeys()) {
                NbtElement child = c.get(k);
                if (child != null && child.getType() == NbtElement.STRING_TYPE) scanString(k, child.asString(), a, depth);
                else scan(child, a, depth + 1, k);
            }
        } else if (type == NbtElement.LIST_TYPE) {
            NbtList l = (NbtList) el;
            for (int i = 0; i < l.size(); i++) scan(l.get(i), a, depth + 1, key);
        } else if (type == NbtElement.STRING_TYPE) {
            scanString(key, el.asString(), a, depth);
        }
    }

    private static void scanString(String key, String v, Acc a, int depth) {
        if (v.isEmpty() || v.length() > 20000) return;
        String t = v.trim();
        if (a.mobId == null && mobKey(key)) a.mobId = normalizeEntity(t);
        if (!a.swordFound && t.endsWith("_sword")) {
            String id = t.contains(":") ? t : "minecraft:" + t;
            Identifier ident = Identifier.tryParse(id);
            if (ident != null && Registries.ITEM.containsId(ident)) {
                a.swordId = id;
                a.swordFound = true;
            }
        }
        if (t.startsWith("{") || t.startsWith("[")) {
            try {
                scanJson(JsonParser.parseString(t), a, depth + 1, key);
            } catch (Throwable ignored) {
                // не JSON
            }
        } else if (t.length() >= 24 && t.matches("[A-Za-z0-9+/=_-]+")) {
            try {
                byte[] raw = Base64.getMimeDecoder().decode(t.replace('-', '+').replace('_', '/'));
                NbtCompound nbt;
                try {
                    nbt = NbtIo.readCompressed(new ByteArrayInputStream(raw), NbtSizeTracker.ofUnlimitedBytes());
                } catch (Throwable e) {
                    nbt = NbtIo.readCompound(new DataInputStream(new ByteArrayInputStream(raw)));
                }
                scan(nbt, a, depth + 1, key);
            } catch (Throwable ignored) {
                // не Base64-NBT
            }
        }
    }

    private static void scanJson(JsonElement el, Acc a, int depth, String key) {
        if (el == null || depth > 12) return;
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            JsonElement idEl = o.get("id");
            if (idEl != null && idEl.isJsonPrimitive()) {
                String id = idEl.getAsString();
                if (id.endsWith("_sword") && !a.swordFound) {
                    String full = id.contains(":") ? id : "minecraft:" + id;
                    Identifier ident = Identifier.tryParse(full);
                    if (ident != null && Registries.ITEM.containsId(ident)) {
                        a.swordFound = true;
                        a.swordId = full;
                        readEnchantsJson(o, a);
                    }
                } else if (a.mobId == null && mobKey(key.isEmpty() ? "id" : key)) {
                    a.mobId = normalizeEntity(id);
                }
            }
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                JsonElement v = e.getValue();
                if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) scanString(e.getKey(), v.getAsString(), a, depth);
                else scanJson(v, a, depth + 1, e.getKey());
            }
        } else if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            for (JsonElement x : arr) scanJson(x, a, depth + 1, key);
        }
    }

    // ---------------- зачарования ----------------

    private static void readEnchants(NbtCompound item, Acc a) {
        NbtCompound comps = item.contains("components", NbtElement.COMPOUND_TYPE) ? item.getCompound("components") : null;
        if (comps != null && comps.contains("minecraft:enchantments", NbtElement.COMPOUND_TYPE)) {
            NbtCompound e = comps.getCompound("minecraft:enchantments");
            readEnchantMap(e.contains("levels", NbtElement.COMPOUND_TYPE) ? e.getCompound("levels") : e, a);
        }
        for (String holder : new String[]{"tag", "nbt"}) { // старый формат: tag.Enchantments = [{id, lvl}]
            if (item.contains(holder, NbtElement.COMPOUND_TYPE)) readEnchantList(item.getCompound(holder).getList("Enchantments", NbtElement.COMPOUND_TYPE), a);
        }
        readEnchantList(item.getList("Enchantments", NbtElement.COMPOUND_TYPE), a);
        if (item.contains("enchantments", NbtElement.COMPOUND_TYPE)) readEnchantMap(item.getCompound("enchantments"), a);
    }

    private static void readEnchantMap(NbtCompound map, Acc a) {
        for (String k : map.getKeys()) {
            NbtElement v = map.get(k);
            if (v == null) continue;
            int lvl;
            switch (v.getType()) {
                case NbtElement.INT_TYPE, NbtElement.SHORT_TYPE, NbtElement.BYTE_TYPE -> lvl = map.getInt(k);
                default -> { continue; }
            }
            addEnchant(a, k, lvl);
        }
    }

    private static void readEnchantList(NbtList list, Acc a) {
        for (int i = 0; i < list.size(); i++) {
            NbtCompound c = list.getCompound(i);
            addEnchant(a, c.getString("id"), c.contains("lvl") ? c.getInt("lvl") : c.getInt("level"));
        }
    }

    private static void readEnchantsJson(JsonObject item, Acc a) {
        JsonObject comps = item.has("components") && item.get("components").isJsonObject() ? item.getAsJsonObject("components") : item;
        for (String name : new String[]{"minecraft:enchantments", "enchantments", "Enchantments"}) {
            JsonElement e = comps.get(name);
            if (e == null) continue;
            if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                JsonObject map = o.has("levels") && o.get("levels").isJsonObject() ? o.getAsJsonObject("levels") : o;
                for (Map.Entry<String, JsonElement> en : map.entrySet()) {
                    if (en.getValue().isJsonPrimitive() && en.getValue().getAsJsonPrimitive().isNumber()) addEnchant(a, en.getKey(), en.getValue().getAsInt());
                }
            } else if (e.isJsonArray()) {
                for (JsonElement x : e.getAsJsonArray()) {
                    if (!x.isJsonObject()) continue;
                    JsonObject o = x.getAsJsonObject();
                    String id = o.has("id") ? o.get("id").getAsString() : "";
                    int lvl = o.has("lvl") ? o.get("lvl").getAsInt() : (o.has("level") ? o.get("level").getAsInt() : 0);
                    addEnchant(a, id, lvl);
                }
            }
        }
    }

    private static void addEnchant(Acc a, String id, int lvl) {
        if (id == null || id.isEmpty() || lvl <= 0) return;
        String full = id.contains(":") ? id : "minecraft:" + id.toLowerCase(Locale.ROOT);
        for (Ench e : a.enchants) if (e.id().equals(full)) return;
        a.enchants.add(new Ench(full, lvl));
    }
}
