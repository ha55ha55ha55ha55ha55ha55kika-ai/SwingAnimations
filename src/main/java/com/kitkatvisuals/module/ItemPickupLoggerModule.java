package com.kitkatvisuals.module;

import com.kitkatvisuals.gui.Hud;
import com.kitkatvisuals.gui.HudLayout;
import com.kitkatvisuals.gui.Ui;
import com.kitkatvisuals.mixin.HandledScreenAccessor;
import com.kitkatvisuals.util.Spring;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ItemPickupLogger: уведомление о подобранном предмете (иконка, название, количество) с пружинной анимацией
 * появления/исчезновения; слот, куда лёг предмет, подсвечивается на хотбаре и в экране инвентаря.
 *
 * Подбор определяется по росту суммарного количества предмета в инвентаре (перекладывание и обмен рук
 * суммы не меняют), поэтому перехватывать пакеты не нужно. Пока открыт любой экран, подбор не считается.
 * Единственный Mixin: HandledScreenAccessor (координаты контейнера для подсветки в инвентаре).
 */
public final class ItemPickupLoggerModule extends Module {
    private static final int SLOTS = 37; // 0..35 инвентарь + 36 = вторая рука (индекс 40)

    private static final class Entry {
        final String key;
        ItemStack stack;
        int count;
        int slot; // индекс в PlayerInventory
        long t0;
        final Spring slide = new Spring(0f, 170f, 21f);

        Entry(String key, ItemStack stack, int count, int slot, long t0) {
            this.key = key;
            this.stack = stack;
            this.count = count;
            this.slot = slot;
            this.t0 = t0;
        }
    }

    private final Setting.Num duration = add(new Setting.Num("duration", "Duration (ms)", 2500f, 1000f, 6000f, 250f));
    private final Setting.Num maxEntries = add(new Setting.Num("max_entries", "Max entries", 4f, 1f, 8f, 1f));
    private final Setting.Bool showName = add(new Setting.Bool("show_name", "Show item name", true));
    private final Setting.Bool hlHotbar = add(new Setting.Bool("highlight_hotbar", "Highlight hotbar slot", true));
    private final Setting.Bool hlInventory = add(new Setting.Bool("highlight_inventory", "Highlight inventory slot", true));
    private final ColorSettings colors = new ColorSettings(this);

    private final HudLayout.Win win = HudLayout.win("item_pickup");
    private final List<Entry> entries = new ArrayList<>();

    private final String[] prevKeys = new String[SLOTS];
    private final int[] prevCounts = new int[SLOTS];
    private Object lastWorld;
    private Object lastPlayer;

    public ItemPickupLoggerModule() {
        super("item_pickup", "ItemPickupLogger", "hud");
        ScreenEvents.AFTER_INIT.register(this::onScreenInit);
    }

    @Override
    public void onEnable() { lastWorld = null; }

    @Override
    public void onDisable() {
        entries.clear();
        lastWorld = null;
    }

    private static String keyOf(ItemStack st) {
        return Registries.ITEM.getId(st.getItem()) + "#" + st.getComponentChanges().hashCode();
    }

    @Override
    public void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return;
        PlayerInventory inv = p.getInventory();

        String[] keys = new String[SLOTS];
        int[] counts = new int[SLOTS];
        Map<String, Integer> now = new HashMap<>();
        for (int i = 0; i < SLOTS; i++) {
            ItemStack st = inv.getStack(i < 36 ? i : 40);
            if (st.isEmpty()) continue;
            keys[i] = keyOf(st);
            counts[i] = st.getCount();
            now.merge(keys[i], st.getCount(), Integer::sum);
        }

        boolean fresh = lastWorld != mc.world || lastPlayer != p; // вход в мир, смена измерения, респавн
        if (!fresh && mc.currentScreen == null) {
            Map<String, Integer> before = new HashMap<>();
            for (int i = 0; i < SLOTS; i++) if (prevKeys[i] != null) before.merge(prevKeys[i], prevCounts[i], Integer::sum);
            for (Map.Entry<String, Integer> e : now.entrySet()) {
                int delta = e.getValue() - before.getOrDefault(e.getKey(), 0);
                if (delta > 0) record(e.getKey(), delta, keys, counts, inv);
            }
        }
        System.arraycopy(keys, 0, prevKeys, 0, SLOTS);
        System.arraycopy(counts, 0, prevCounts, 0, SLOTS);
        lastWorld = mc.world;
        lastPlayer = p;
    }

    private void record(String key, int delta, String[] keys, int[] counts, PlayerInventory inv) {
        int idx = -1;
        for (int i = 0; i < SLOTS && idx < 0; i++) {
            if (key.equals(keys[i]) && (!key.equals(prevKeys[i]) || counts[i] > prevCounts[i])) idx = i;
        }
        if (idx < 0) for (int i = 0; i < SLOTS && idx < 0; i++) if (key.equals(keys[i])) idx = i;
        if (idx < 0) return;
        int invIndex = idx < 36 ? idx : 40;
        ItemStack copy = inv.getStack(invIndex).copyWithCount(1);
        long now = System.currentTimeMillis();
        for (Entry e : entries) {
            if (e.key.equals(key) && now - e.t0 < duration.value) { // тот же предмет подобран снова: плюсуем
                e.count += delta;
                e.slot = invIndex;
                e.t0 = now;
                return;
            }
        }
        entries.add(new Entry(key, copy, delta, invIndex, now));
        while (entries.size() > (int) maxEntries.value) entries.remove(0);
    }

    /** 0..1: насколько сильно подсвечивать слот сейчас (плавное затухание в конце жизни записи). */
    private float slotAlpha(int invIndex, long now) {
        float best = 0f;
        for (Entry e : entries) {
            if (e.slot != invIndex) continue;
            float left = duration.value - (now - e.t0);
            if (left <= 0f) continue;
            float a = MathHelper.clamp(left / 500f, 0f, 1f);
            float pulse = 0.8f + 0.2f * MathHelper.sin((now - e.t0) / 120f);
            best = Math.max(best, a * pulse);
        }
        return best;
    }

    private void fillSlot(DrawContext ctx, int x, int y, float alpha, long now) {
        int rgb = colors.rgb(now, 0f);
        int a = (int) (alpha * 0x70);
        ctx.fill(x, y, x + 16, y + 16, (a << 24) | rgb);
        int edge = ((int) (alpha * 0xFF) << 24) | rgb;
        ctx.fill(x - 1, y - 1, x + 17, y, edge);
        ctx.fill(x - 1, y + 16, x + 17, y + 17, edge);
        ctx.fill(x - 1, y, x, y + 16, edge);
        ctx.fill(x + 16, y, x + 17, y + 16, edge);
    }

    private void onScreenInit(MinecraftClient c, Screen screen, int w, int h) {
        if (!(screen instanceof HandledScreen<?> hs)) return;
        ScreenEvents.afterRender(screen).register((s, ctx, mx, my, td) -> {
            if (!isEnabled() || !hlInventory.value || entries.isEmpty()) return;
            HandledScreenAccessor acc = (HandledScreenAccessor) hs;
            long now = System.currentTimeMillis();
            for (Slot sl : hs.getScreenHandler().slots) {
                if (!(sl.inventory instanceof PlayerInventory)) continue;
                float a = slotAlpha(sl.getIndex(), now);
                if (a > 0f) fillSlot(ctx, acc.kitkat$getX() + sl.x, acc.kitkat$getY() + sl.y, a, now);
            }
        });
    }

    @Override
    public void renderHud(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;
        long now = System.currentTimeMillis();

        // подсветка слотов хотбара
        if (hlHotbar.value && !entries.isEmpty()) {
            int cx = ctx.getScaledWindowWidth() / 2;
            int y = ctx.getScaledWindowHeight() - 19;
            for (int i = 0; i < 9; i++) {
                float a = slotAlpha(i, now);
                if (a > 0f) fillSlot(ctx, cx - 90 + i * 20 + 3, y, a, now);
            }
        }

        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            boolean alive = now - e.t0 < duration.value;
            e.slide.target = alive ? 1f : 0f;
            e.slide.update();
            if (!alive && e.slide.value < 0.02f) entries.remove(i);
        }

        List<Entry> shown = entries;
        if (shown.isEmpty()) {
            if (!HudLayout.editing()) return;
            Entry s = new Entry("sample", new ItemStack(Items.DIAMOND), 3, 0, now);
            s.slide.snap(1f);
            shown = List.of(s);
        }

        int rowH = 22;
        int sw = ctx.getScaledWindowWidth();
        int sh = ctx.getScaledWindowHeight();
        int maxW = 0;
        int[] widths = new int[shown.size()];
        for (int i = 0; i < shown.size(); i++) {
            Entry e = shown.get(i);
            int nameW = showName.value ? Math.min(Hud.w(e.stack.getName().getString()), 110) + 4 : 0;
            widths[i] = 26 + nameW + Hud.w("+" + e.count) + 8;
            maxW = Math.max(maxW, widths[i]);
        }
        int baseX = sw - 8 - maxW;
        int baseY = sh / 2 + 20;
        HudLayout.push(ctx, win);
        for (int i = 0; i < shown.size(); i++) {
            Entry e = shown.get(i);
            int w = widths[i];
            float k = Math.max(0f, e.slide.value);
            int x = baseX + maxW - w + Math.round((1f - k) * (w + 16));
            int y = baseY + i * (rowH + 3);
            Hud.panel(ctx, x, y, w, rowH);
            ctx.fill(x + 3, y + 5, x + 5, y + rowH - 5, 0xFF000000 | colors.rgb(now, i * 0.08f));
            ctx.drawItem(e.stack, x + 8, y + 3);
            int tx = x + 28;
            if (showName.value) {
                String name = Ui.tr().trimToWidth(e.stack.getName().getString(), 110);
                Hud.text(ctx, name, tx, y + 7, Ui.TEXT);
            }
            Hud.textRight(ctx, "+" + e.count, x + w - 6, y + 7, 0xFF000000 | colors.rgb(now, i * 0.08f));
        }
        HudLayout.pop(ctx, win, baseX, baseY, maxW, shown.size() * (rowH + 3) - 3);
    }
}
