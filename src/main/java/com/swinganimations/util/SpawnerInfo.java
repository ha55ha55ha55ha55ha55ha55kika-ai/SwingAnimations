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
import net.minecraft.client.resource.language.I18n;
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
    public static final SpawnerInfo NONE = new SpawnerInfo(null, null, null, null, new ArrayList<>());

    /** Показываемое имя моба (например «Зомби») и его id (minecraft:zombie), либо null. */
    public final String mobName;
    public final String mobId;
    /** Показываемое имя меча (например «Алмазный меч»), либо null. */
    public final String swordName;
    /** id меча (minecraft:netherite_sword), либо null. */
    public final String swordId;
    public final List<Ench> enchants;
    /** Статистика кастомного спавнера (формат HolyWorld): сколько мобов убито, сколько нужно, сколько яиц вставлено. */
    public long killsTotal;
    public long killsRemaining;
    public int eggs;

    /** label != null: серверное (нестандартное) зачарование, показывается под своим названием. */
    public record Ench(String id, int level, String label) {
        public Ench(String id, int level) { this(id, level, null); }

        public String shortId() {
            int i = id.indexOf(':');
            return i >= 0 ? id.substring(i + 1) : id;
        }

        public boolean custom() { return label != null; }

        /** Название без уровня. */
        public String name() {
            if (label != null) return label;
            Identifier ident = Identifier.tryParse(id);
            String key = ident == null ? "enchantment.minecraft." + shortId() : "enchantment." + ident.getNamespace() + "." + ident.getPath();
            if (I18n.hasTranslation(key)) return I18n.translate(key);
            String raw = shortId().replace('_', ' ');
            return raw.isEmpty() ? id : Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
        }

        public Text text() {
            Text lvl = level >= 1 && level <= 10 ? Text.translatable("enchantment.level." + level) : Text.literal(String.valueOf(level));
            return Text.literal(name()).append(" ").append(lvl);
        }
    }

    private SpawnerInfo(String mobName, String mobId, String swordName, String swordId, List<Ench> enchants) {
        this.mobName = mobName;
        this.mobId = mobId;
        this.swordName = swordName;
        this.swordId = swordId;
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
        /** Строки lore меча и самого спавнера: там у серверных зачарований лежат названия и уровни. */
        final List<String> swordLore = new ArrayList<>();
        final List<String> spawnerLore = new ArrayList<>();
        /** Уровни серверных зачарований, найденные в custom_data по ключам. */
        final Map<String, Integer> customLevels = new java.util.HashMap<>();
        long killsTotal;
        long killsRemaining;
        int eggs;
    }

    private static SpawnerInfo parse(ItemStack stack) {
        if (!looksLikeSpawner(stack)) return NONE;
        Acc a = new Acc();
        NbtComponent custom = stack.get(DataComponentTypes.CUSTOM_DATA);
        // Сначала точный формат спавнера HolyWorld (gs_sword_item): иначе общий поиск находит меч по полю type, но не его чары.
        if (custom != null) readHolyWorld(custom.copyNbt(), a);
        if (custom != null) scan(custom.copyNbt(), a, 0, "");
        NbtComponent bed = stack.get(DataComponentTypes.BLOCK_ENTITY_DATA);
        if (bed != null) scan(bed.copyNbt(), a, 0, "");
        NbtComponent ent = stack.get(DataComponentTypes.ENTITY_DATA);
        if (ent != null) scan(ent.copyNbt(), a, 0, "");
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore != null) scanLore(lore, a);

        applyCustom(a);

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
        SpawnerInfo info = new SpawnerInfo(mobName, a.mobId, swordName, swordName == null ? null : a.swordId, a.enchants);
        info.killsTotal = a.killsTotal;
        info.killsRemaining = a.killsRemaining;
        info.eggs = a.eggs;
        return info;
    }

    // ---------------- формат спавнера HolyWorld ----------------

    /**
     * Данные лежат в custom_data: gs_marker (признак), gs_sword_item (JSON-строка меча: type, name, enchants {id: уровень}),
     * gs_mob_type (например MAGMA_CUBE), gs_kills_total / gs_kills_remaining, gs_total_eggs.
     */
    private static void readHolyWorld(NbtCompound nbt, Acc a) {
        if (!nbt.contains("gs_marker") && !nbt.contains("gs_sword_item")) return;
        if (nbt.contains("gs_mob_type")) a.mobId = normalizeEntity(nbt.getString("gs_mob_type"));
        a.killsTotal = nbt.getLong("gs_kills_total");
        a.killsRemaining = nbt.getLong("gs_kills_remaining");
        a.eggs = nbt.getInt("gs_total_eggs");
        String raw = nbt.getString("gs_sword_item");
        if (raw == null || raw.isBlank()) return;
        JsonObject sword;
        try {
            sword = JsonParser.parseString(raw).getAsJsonObject();
        } catch (Throwable e) {
            return;
        }
        JsonElement type = sword.get("type");
        if (type != null && type.isJsonPrimitive()) {
            String t = type.getAsString().toLowerCase(Locale.ROOT);
            a.swordId = t.contains(":") ? t : "minecraft:" + t;
            a.swordFound = true;
        }
        JsonElement en = sword.get("enchants");
        if (en == null) return;
        if (en.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : en.getAsJsonObject().entrySet()) {
                try {
                    addEnchant(a, e.getKey(), e.getValue().getAsInt());
                } catch (Throwable ignored) {
                    // уровень не число: пропускаем это зачарование
                }
            }
        } else if (en.isJsonArray()) {
            for (JsonElement x : en.getAsJsonArray()) {
                if (!x.isJsonObject()) continue;
                JsonObject o = x.getAsJsonObject();
                try {
                    String id = o.has("id") ? o.get("id").getAsString() : "";
                    int lvl = o.has("lvl") ? o.get("lvl").getAsInt() : (o.has("level") ? o.get("level").getAsInt() : 1);
                    addEnchant(a, id, lvl);
                } catch (Throwable ignored) {
                    // пропускаем
                }
            }
        }
    }

    /** Названия серверных зачарований HolyWorld. Неизвестные «что-то-enchant-custom» показываются в читаемом виде. */
    private static final Map<String, String> HW_CUSTOM_NAMES = Map.of(
            "critical-enchant-custom", "Критический",
            "destroyer-enchant-custom", "Разрушитель",
            "rich-enchant-custom", "Богач",
            "mob-farmer-enchant", "Фармер");

    private static String prettyCustom(String path) {
        String p = path.replace("-enchant-custom", "").replace("-enchant", "").replace('-', ' ').replace('_', ' ').trim();
        return p.isEmpty() ? path : Character.toUpperCase(p.charAt(0)) + p.substring(1);
    }

    private static final Pattern LORE_MOB = Pattern.compile("(?i)(?:моб|mob|существо|тип)\\s*[:：]\\s*(.+)");
    private static final Pattern LORE_SWORD = Pattern.compile("(?i)(?:меч|sword)\\s*[:：]\\s*(.+)");

    private static void scanLore(LoreComponent lore, Acc a) {
        for (Text line : lore.lines()) {
            String s = line.getString().replaceAll("§.", "").trim();
            if (!s.isEmpty()) a.spawnerLore.add(s);
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
                        readSwordExtras(c, a);
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
                        readSwordExtrasJson(o, a);
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
        if (id == null || id.isBlank() || lvl <= 0) return;
        String raw = id.trim();
        int colon = raw.indexOf(':');
        String ns = colon >= 0 ? raw.substring(0, colon).toLowerCase(Locale.ROOT) : "minecraft";
        String path = (colon >= 0 ? raw.substring(colon + 1) : raw).toLowerCase(Locale.ROOT);
        if (path.equals("sweeping")) path = "sweeping_edge"; // старое название «Разящего клинка»
        String full = ns + ":" + path;
        for (Ench e : a.enchants) if (e.id().equals(full)) return;
        String label = null;
        for (SpawnerFilter.Entry ce : SpawnerFilter.customEntries()) {
            if (ce.matches(path, null)) {
                label = ce.label();
                break;
            }
        }
        // Серверные зачарования имеют дефис в id («critical-enchant-custom»), у ванильных только подчёркивания.
        if (label == null && path.indexOf('-') >= 0) {
            label = HW_CUSTOM_NAMES.get(path);
            if (label == null) label = prettyCustom(path);
        }
        a.enchants.add(new Ench(full, lvl, label));
    }

    // ---------------- серверные зачарования (lore, custom_data) ----------------

    private static final Pattern ROMAN_LINE = Pattern.compile("^([\\p{L}][\\p{L} '\\-]{2,30}?)\\s+(X|IX|VIII|VII|VI|IV|V|III|II|I)$");
    private static final Pattern LEVEL_AFTER = Pattern.compile("^\\s*[:\\-]?\\s*(X|IX|VIII|VII|VI|IV|V|III|II|I|\\d{1,2})\\b", Pattern.CASE_INSENSITIVE);

    private static int parseLevel(String s) {
        String t = s.trim().toUpperCase(Locale.ROOT);
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException ignored) {
            // римская цифра
        }
        return switch (t) {
            case "I" -> 1;
            case "II" -> 2;
            case "III" -> 3;
            case "IV" -> 4;
            case "V" -> 5;
            case "VI" -> 6;
            case "VII" -> 7;
            case "VIII" -> 8;
            case "IX" -> 9;
            case "X" -> 10;
            default -> 0;
        };
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("§.", "").trim();
    }

    /** Достаёт текст из lore/названия в любом виде: строка, JSON-компонент или NBT-компонент. */
    private static void gather(NbtElement el, StringBuilder sb, int depth) {
        if (el == null || depth > 6) return;
        byte t = el.getType();
        if (t == NbtElement.STRING_TYPE) {
            String v = el.asString();
            String tr = v.trim();
            if (tr.startsWith("{") || tr.startsWith("[") || tr.startsWith("\"")) {
                try {
                    gatherJson(JsonParser.parseString(tr), sb, depth + 1);
                    return;
                } catch (Throwable ignored) {
                    // обычная строка
                }
            }
            sb.append(v);
        } else if (t == NbtElement.COMPOUND_TYPE) {
            NbtCompound c = (NbtCompound) el;
            if (c.contains("text", NbtElement.STRING_TYPE)) sb.append(c.getString("text"));
            if (c.contains("extra")) gather(c.get("extra"), sb, depth + 1);
        } else if (t == NbtElement.LIST_TYPE) {
            NbtList l = (NbtList) el;
            for (int i = 0; i < l.size(); i++) gather(l.get(i), sb, depth + 1);
        }
    }

    private static void gatherJson(JsonElement el, StringBuilder sb, int depth) {
        if (el == null || depth > 6) return;
        if (el.isJsonPrimitive()) {
            sb.append(el.getAsString());
        } else if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            if (o.has("text") && o.get("text").isJsonPrimitive()) sb.append(o.get("text").getAsString());
            if (o.has("extra")) gatherJson(o.get("extra"), sb, depth + 1);
        } else if (el.isJsonArray()) {
            for (JsonElement x : el.getAsJsonArray()) gatherJson(x, sb, depth + 1);
        }
    }

    private static void addLoreLine(Acc a, NbtElement el) {
        StringBuilder sb = new StringBuilder();
        gather(el, sb, 0);
        String line = clean(sb.toString());
        if (!line.isEmpty()) a.swordLore.add(line);
    }

    private static void collectLore(NbtElement el, Acc a) {
        if (el == null) return;
        if (el.getType() == NbtElement.LIST_TYPE) {
            NbtList l = (NbtList) el;
            for (int i = 0; i < l.size(); i++) addLoreLine(a, l.get(i));
        } else {
            addLoreLine(a, el);
        }
    }

    private static void readSwordExtras(NbtCompound item, Acc a) {
        NbtCompound comps = item.contains("components", NbtElement.COMPOUND_TYPE) ? item.getCompound("components") : null;
        if (comps != null) {
            collectLore(comps.get("minecraft:lore"), a);
            collectLore(comps.get("lore"), a);
            collectLore(comps.get("minecraft:custom_name"), a);
            if (comps.contains("minecraft:custom_data", NbtElement.COMPOUND_TYPE)) customKeys(comps.getCompound("minecraft:custom_data"), a, 0);
        }
        for (String holder : new String[]{"tag", "nbt"}) {
            if (!item.contains(holder, NbtElement.COMPOUND_TYPE)) continue;
            NbtCompound tag = item.getCompound(holder);
            if (tag.contains("display", NbtElement.COMPOUND_TYPE)) collectLore(tag.getCompound("display").get("Lore"), a);
            customKeys(tag, a, 0);
        }
        collectLore(item.get("Lore"), a);
    }

    private static void readSwordExtrasJson(JsonObject item, Acc a) {
        JsonObject comps = item.has("components") && item.get("components").isJsonObject() ? item.getAsJsonObject("components") : item;
        for (String k : new String[]{"minecraft:lore", "lore", "Lore", "minecraft:custom_name"}) {
            JsonElement e = comps.get(k);
            if (e == null) continue;
            if (e.isJsonArray()) {
                for (JsonElement x : e.getAsJsonArray()) {
                    StringBuilder sb = new StringBuilder();
                    if (x.isJsonPrimitive()) {
                        gather(net.minecraft.nbt.NbtString.of(x.getAsString()), sb, 0);
                    } else {
                        gatherJson(x, sb, 0);
                    }
                    String line = clean(sb.toString());
                    if (!line.isEmpty()) a.swordLore.add(line);
                }
            } else {
                StringBuilder sb = new StringBuilder();
                if (e.isJsonPrimitive()) gather(net.minecraft.nbt.NbtString.of(e.getAsString()), sb, 0); else gatherJson(e, sb, 0);
                String line = clean(sb.toString());
                if (!line.isEmpty()) a.swordLore.add(line);
            }
        }
        JsonElement cd = comps.get("minecraft:custom_data");
        if (cd != null && cd.isJsonObject()) customKeysJson(cd.getAsJsonObject(), a, 0);
    }

    private static void customKeys(NbtCompound c, Acc a, int depth) {
        if (depth > 4) return;
        for (String k : c.getKeys()) {
            String lk = k.toLowerCase(Locale.ROOT);
            NbtElement v = c.get(k);
            for (SpawnerFilter.Entry e : SpawnerFilter.customEntries()) {
                if (!e.matches(lk, null)) continue;
                int lvl = 1;
                if (v != null && v.getType() >= NbtElement.BYTE_TYPE && v.getType() <= NbtElement.DOUBLE_TYPE) lvl = Math.max(1, c.getInt(k));
                a.customLevels.merge(e.key(), lvl, Math::max);
            }
            if (v != null && v.getType() == NbtElement.COMPOUND_TYPE) customKeys((NbtCompound) v, a, depth + 1);
        }
    }

    private static void customKeysJson(JsonObject o, Acc a, int depth) {
        if (depth > 4) return;
        for (Map.Entry<String, JsonElement> en : o.entrySet()) {
            String lk = en.getKey().toLowerCase(Locale.ROOT);
            JsonElement v = en.getValue();
            for (SpawnerFilter.Entry e : SpawnerFilter.customEntries()) {
                if (!e.matches(lk, null)) continue;
                int lvl = 1;
                if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) lvl = Math.max(1, v.getAsInt());
                a.customLevels.merge(e.key(), lvl, Math::max);
            }
            if (v.isJsonObject()) customKeysJson(v.getAsJsonObject(), a, depth + 1);
        }
    }

    /** Добавляет серверные зачарования (Фармер и т.п.), найденные в lore/custom_data, и неизвестные строки вида «Название IV» из lore меча. */
    private static void applyCustom(Acc a) {
        for (SpawnerFilter.Entry ce : SpawnerFilter.customEntries()) {
            boolean have = false;
            for (Ench e : a.enchants) if (ce.matches(e.shortId(), e.label())) { have = true; break; }
            if (have) continue;
            int lvl = a.customLevels.getOrDefault(ce.key(), 0);
            if (lvl == 0) lvl = levelFromLines(a.swordLore, ce);
            if (lvl == 0) lvl = levelFromLines(a.spawnerLore, ce);
            if (lvl > 0) a.enchants.add(new Ench("custom:" + ce.key(), lvl, ce.label()));
        }
        for (String line : a.swordLore) {
            Matcher m = ROMAN_LINE.matcher(line);
            if (!m.matches()) continue;
            String name = m.group(1).trim();
            String lower = name.toLowerCase(Locale.ROOT);
            boolean known = false;
            for (Ench e : a.enchants) {
                if (e.name().equalsIgnoreCase(name) || e.shortId().equalsIgnoreCase(lower.replace(' ', '_'))) { known = true; break; }
            }
            for (SpawnerFilter.Entry fe : SpawnerFilter.ENTRIES) {
                if (fe.matches(lower.replace(' ', '_'), lower)) { known = true; break; }
            }
            if (known) continue;
            a.enchants.add(new Ench("lore:" + lower.replace(' ', '_'), Math.max(1, parseLevel(m.group(2))), name));
        }
    }

    private static int levelFromLines(List<String> lines, SpawnerFilter.Entry ce) {
        for (String line : lines) {
            String low = line.toLowerCase(Locale.ROOT);
            for (String alias : ce.aliases()) {
                int i = low.indexOf(alias);
                if (i < 0) continue;
                Matcher m = LEVEL_AFTER.matcher(line.substring(i + alias.length()));
                if (m.find()) {
                    int l = parseLevel(m.group(1));
                    if (l > 0) return l;
                }
                return 1;
            }
        }
        return 0;
    }
}
