package com.kitkatvisuals.input;

import com.kitkatvisuals.config.ConfigManager;
import com.kitkatvisuals.config.Presets;
import com.kitkatvisuals.config.SwingConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public final class KeyBinds {
    private static final String CATEGORY = "category.kitkatvisuals";
    public static KeyBinding OPEN;
    public static KeyBinding TOGGLE;
    public static KeyBinding NEXT_PRESET;
    public static KeyBinding LOCK;
    public static KeyBinding CROSSHAIR_STYLE;
    public static KeyBinding CROSSHAIR_COLOR;
    public static KeyBinding FREE_LOOK;
    public static KeyBinding FRIEND;
    public static KeyBinding EVENT_PAGE;
    public static KeyBinding EVENT_SEARCH;
    public static KeyBinding MUSIC_OPEN;
    public static KeyBinding MUSIC_PAUSE;
    public static KeyBinding MUSIC_NEXT;
    public static KeyBinding MUSIC_PREV;
    public static KeyBinding MINIMAP_ZOOM_IN;
    public static KeyBinding SPAWNER_DUMP;
    public static KeyBinding ANARCHY_FIND;
    public static KeyBinding ZOOM;
    public static KeyBinding WARDROBE;
    public static KeyBinding MINIMAP_ZOOM_OUT;
    public static KeyBinding MINIMAP_MARK;
    public static KeyBinding MINIMAP_UNMARK;
    private static boolean freeLookDown;

    private KeyBinds() {}

    public static void register() {
        OPEN = reg("open", GLFW.GLFW_KEY_RIGHT_SHIFT);
        TOGGLE = reg("toggle", GLFW.GLFW_KEY_UNKNOWN);       // по умолчанию не назначена, чтобы не конфликтовать
        NEXT_PRESET = reg("next_preset", GLFW.GLFW_KEY_UNKNOWN);
        LOCK = reg("lock_slot", GLFW.GLFW_KEY_Y); // L занята ванилью (достижения); при конфликте с модами ключ подбирается автоматически
        CROSSHAIR_STYLE = reg("crosshair_style", GLFW.GLFW_KEY_UNKNOWN); // назначается игроком в «Управлении»
        CROSSHAIR_COLOR = reg("crosshair_color", GLFW.GLFW_KEY_UNKNOWN);
        FRIEND = reg("friend", GLFW.GLFW_KEY_UNKNOWN);
        EVENT_PAGE = reg("event_page", GLFW.GLFW_KEY_UNKNOWN);
        EVENT_SEARCH = reg("event_search", GLFW.GLFW_KEY_K);
        MUSIC_OPEN = reg("music_open", GLFW.GLFW_KEY_M);
        MUSIC_PAUSE = reg("music_pause", GLFW.GLFW_KEY_UNKNOWN);
        MUSIC_NEXT = reg("music_next", GLFW.GLFW_KEY_UNKNOWN);
        MUSIC_PREV = reg("music_prev", GLFW.GLFW_KEY_UNKNOWN);
        SPAWNER_DUMP = reg("spawner_dump", GLFW.GLFW_KEY_F8);
        ZOOM = reg("zoom", GLFW.GLFW_KEY_C);
        WARDROBE = reg("wardrobe", GLFW.GLFW_KEY_UNKNOWN); // назначается игроком в «Управлении»
        ANARCHY_FIND = reg("anarchy_find", GLFW.GLFW_KEY_UNKNOWN); // назначается игроком в «Управлении»
        MINIMAP_ZOOM_IN = reg("minimap_zoom_in", GLFW.GLFW_KEY_RIGHT_BRACKET);
        MINIMAP_ZOOM_OUT = reg("minimap_zoom_out", GLFW.GLFW_KEY_LEFT_BRACKET);
        MINIMAP_MARK = reg("minimap_mark", GLFW.GLFW_KEY_B);
        MINIMAP_UNMARK = reg("minimap_unmark", GLFW.GLFW_KEY_UNKNOWN);
        FREE_LOOK = reg("free_look", GLFW.GLFW_KEY_UNKNOWN);  // обычная клавиша управления: работает и на Android (Pojav)
        ClientTickEvents.END_CLIENT_TICK.register(KeyBinds::tick);
        ClientTickEvents.START_CLIENT_TICK.register(KeyBinds::lockSlotGuards);
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(KeyBinds::ensureFreeLockKey);
    }

    /** Кандидаты для «Lock slot»: ни одна из них не занята ванилью 1.21.4. Первая свободная берётся после загрузки всех модов. */
    private static final int[] LOCK_CANDIDATES = {
            GLFW.GLFW_KEY_Y, GLFW.GLFW_KEY_H, GLFW.GLFW_KEY_N, GLFW.GLFW_KEY_U, GLFW.GLFW_KEY_O,
            GLFW.GLFW_KEY_APOSTROPHE, GLFW.GLFW_KEY_SEMICOLON, GLFW.GLFW_KEY_GRAVE_ACCENT, GLFW.GLFW_KEY_KP_0
    };

    /**
     * Проверка конфликтов в коде: после CLIENT_STARTED в options.allKeys есть бинды ванили и всех модов.
     * Если бинд Lock slot стоит на значении по умолчанию (или на старом L) и совпадает с чужим, переносим на первую свободную клавишу.
     * Ручную привязку игрока не трогаем.
     */
    private static void ensureFreeLockKey(MinecraftClient mc) {
        try {
            if (mc.options == null || LOCK == null) return;
            boolean oldDefaultL = LOCK.getBoundKeyTranslationKey().equals("key.keyboard.l");
            if (!LOCK.isDefault() && !oldDefaultL) return;
            if (!conflicts(mc, LOCK.getBoundKeyTranslationKey())) return;
            for (int code : LOCK_CANDIDATES) {
                InputUtil.Key k = InputUtil.Type.KEYSYM.createFromCode(code);
                if (conflicts(mc, k.getTranslationKey())) continue;
                LOCK.setBoundKey(k);
                KeyBinding.updateKeysByCode();
                mc.options.write();
                com.kitkatvisuals.KitKatVisualsClient.LOGGER.info("Lock slot: клавиша перенесена на {}", k.getTranslationKey());
                return;
            }
        } catch (RuntimeException e) {
            com.kitkatvisuals.KitKatVisualsClient.LOGGER.warn("Lock slot: не удалось проверить клавишу: {}", e.toString());
        }
    }

    private static boolean conflicts(MinecraftClient mc, String keyTranslation) {
        for (KeyBinding b : mc.options.allKeys) {
            if (b == LOCK || b.isUnbound()) continue;
            if (b.getBoundKeyTranslationKey().equals(keyTranslation)) return true;
        }
        return false;
    }

    /** До ванильной обработки ввода: гасим F (смена рук), если затронут заблокированный слот. Работает без миксинов. */
    private static void lockSlotGuards(MinecraftClient mc) {
        com.kitkatvisuals.module.LockSlotModule m = com.kitkatvisuals.module.ModuleManager.lockSlot;
        if (m == null || mc.player == null || mc.currentScreen != null) return;
        if (m.blocksSwapHands(mc.player)) {
            while (mc.options.swapHandsKey.wasPressed()) { /* съедаем нажатия */ }
        }
    }

    private static KeyBinding reg(String id, int key) {
        return KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.kitkatvisuals." + id, InputUtil.Type.KEYSYM, key, CATEGORY));
    }

    private static void tick(MinecraftClient mc) {
        while (OPEN.wasPressed()) {
            if (mc.currentScreen == null) mc.setScreen(com.kitkatvisuals.gui.ClickGuiScreen.create(null));
        }
        while (LOCK.wasPressed()) {
            com.kitkatvisuals.module.LockSlotModule ls = com.kitkatvisuals.module.ModuleManager.lockSlot;
            if (ls != null && ls.isEnabled() && mc.currentScreen == null && mc.player != null) ls.toggleSelected(mc);
        }
        crosshairKeys(mc);
        freeLookKey(mc);
        friendKey(mc);
        while (EVENT_PAGE.wasPressed()) {
            com.kitkatvisuals.module.EventHudModule ev = com.kitkatvisuals.module.ModuleManager.eventHud;
            if (ev != null) ev.nextPage();
        }
        while (EVENT_SEARCH.wasPressed()) {
            com.kitkatvisuals.module.EventHudModule ev = com.kitkatvisuals.module.ModuleManager.eventHud;
            if (ev != null && mc.currentScreen == null) ev.openSearch();
        }
        while (WARDROBE.wasPressed()) {
            com.kitkatvisuals.module.CosmeticsModule cos = com.kitkatvisuals.module.ModuleManager.cosmetics;
            if (cos != null && mc.currentScreen == null) cos.openWardrobe();
        }
        while (ANARCHY_FIND.wasPressed()) {
            com.kitkatvisuals.module.AnarchyFinderModule af = com.kitkatvisuals.module.ModuleManager.anarchyFinder;
            if (af != null && mc.currentScreen == null) af.run(mc);
        }
        musicKeys(mc);
        minimapKeys(mc);
        SwingConfig cfg = ConfigManager.get();
        while (TOGGLE.wasPressed()) {
            cfg.enabled = !cfg.enabled;
            ConfigManager.save();
            message(mc, Text.translatable("kitkatvisuals.msg.toggle",
                    Text.translatable(cfg.enabled ? "kitkatvisuals.on" : "kitkatvisuals.off")));
        }
        while (NEXT_PRESET.wasPressed()) {
            Presets.next(cfg);
            ConfigManager.save();
            message(mc, Text.translatable("kitkatvisuals.msg.preset", cfg.activePreset));
        }
    }

    /**
     * FreeLook через обычную клавишу управления (опрос состояния делает сама игра по событиям клавиатуры).
     * Если у модуля есть свой бинд из ClickGUI, им занимается ModuleManager, а здесь только сбрасываем очередь нажатий.
     */
    private static void freeLookKey(MinecraftClient mc) {
        com.kitkatvisuals.module.FreeLookModule fl = com.kitkatvisuals.module.ModuleManager.freeLook;
        boolean down = FREE_LOOK.isPressed();
        if (fl == null || fl.bindCode >= 0) {
            while (FREE_LOOK.wasPressed()) { /* очередь нажатий */ }
            freeLookDown = down;
            return;
        }
        if (fl.holdBind()) {
            while (FREE_LOOK.wasPressed()) { /* очередь нажатий */ }
            if (down && !freeLookDown && mc.currentScreen == null && mc.player != null) fl.setEnabled(true);
            else if (!down && freeLookDown) fl.setEnabled(false);
        } else {
            while (FREE_LOOK.wasPressed()) {
                if (mc.currentScreen == null && mc.player != null) fl.toggle();
            }
        }
        freeLookDown = down;
    }

    /** Добавить или убрать друга: игрок в прицеле. */
    private static void friendKey(MinecraftClient mc) {
        while (FRIEND.wasPressed()) {
            com.kitkatvisuals.module.HitboxesModule esp = com.kitkatvisuals.module.ModuleManager.hitboxes;
            if (esp == null || mc.player == null) continue;
            if (mc.targetedEntity instanceof net.minecraft.entity.player.PlayerEntity p) {
                String n = p.getName().getString();
                boolean now = esp.toggleFriend(n);
                message(mc, Text.translatableWithFallback(now ? "kitkatvisuals.msg.friend_added" : "kitkatvisuals.msg.friend_removed",
                        now ? "Friend added: %s" : "Friend removed: %s", n));
            } else {
                message(mc, Text.translatableWithFallback("kitkatvisuals.msg.friend_aim", "Aim at a player"));
            }
        }
    }

    private static void crosshairKeys(MinecraftClient mc) {
        com.kitkatvisuals.module.CrosshairModule c = com.kitkatvisuals.module.ModuleManager.crosshair;
        while (CROSSHAIR_STYLE.wasPressed()) {
            if (c == null) continue;
            if (!c.isEnabled()) c.setEnabled(true);
            int s = c.nextStyle();
            message(mc, Text.literal("Crosshair: " + s + "/" + com.kitkatvisuals.module.CrosshairModule.STYLES));
        }
        while (CROSSHAIR_COLOR.wasPressed()) {
            if (c == null) continue;
            if (!c.isEnabled()) c.setEnabled(true);
            message(mc, Text.literal("Crosshair color: " + c.nextColor()));
        }
    }

    private static void minimapKeys(MinecraftClient mc) {
        com.kitkatvisuals.module.MinimapModule mm = com.kitkatvisuals.module.ModuleManager.minimap;
        while (MINIMAP_ZOOM_IN.wasPressed()) {
            if (mm != null && mm.isEnabled()) mm.zoom(-8f);
        }
        while (MINIMAP_ZOOM_OUT.wasPressed()) {
            if (mm != null && mm.isEnabled()) mm.zoom(8f);
        }
        while (MINIMAP_MARK.wasPressed()) {
            if (mm != null && mm.isEnabled() && mc.currentScreen == null && mc.player != null) mm.addWaypointHere(mc);
        }
        while (MINIMAP_UNMARK.wasPressed()) {
            if (mm != null && mm.isEnabled() && mc.currentScreen == null && mc.player != null) mm.removeNearestWaypoint(mc);
        }
    }

    private static void musicKeys(MinecraftClient mc) {
        com.kitkatvisuals.module.MusicModule mm = com.kitkatvisuals.module.ModuleManager.music;
        while (MUSIC_OPEN.wasPressed()) {
            if (mm != null && mc.currentScreen == null) mm.openBrowser();
        }
        while (MUSIC_PAUSE.wasPressed()) {
            com.kitkatvisuals.music.MusicPlayer.togglePause();
            nowPlaying(mc);
        }
        while (MUSIC_NEXT.wasPressed()) {
            com.kitkatvisuals.music.MusicPlayer.next();
            nowPlaying(mc);
        }
        while (MUSIC_PREV.wasPressed()) {
            com.kitkatvisuals.music.MusicPlayer.prev();
            nowPlaying(mc);
        }
    }

    private static void nowPlaying(MinecraftClient mc) {
        com.kitkatvisuals.music.Track t = com.kitkatvisuals.music.MusicPlayer.current();
        if (t != null) message(mc, Text.literal("Music: " + t.title() + (t.artist().isEmpty() ? "" : " - " + t.artist())));
    }

    private static void message(MinecraftClient mc, Text t) {
        if (mc.inGameHud != null) mc.inGameHud.setOverlayMessage(t, false); // локально, пакетов нет
    }
}
