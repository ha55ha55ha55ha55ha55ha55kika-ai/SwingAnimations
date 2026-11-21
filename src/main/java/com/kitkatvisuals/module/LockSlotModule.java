package com.kitkatvisuals.module;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Блокировка слотов инвентаря: заблокированный слот нельзя переложить, выбросить или обменять.
 * Слот блокируется клавишей «Lock slot» (по умолчанию L) при наведении на него в инвентаре.
 * Индексы как в PlayerInventory: 0–8 хотбар, 9–35 основной, 36–39 броня, 40 вторая рука.
 */
public final class LockSlotModule extends Module {
    private final Setting.Str locked = add(new Setting.Str("locked", "Locked slots", ""));
    private final Setting.Bool hudMark = add(new Setting.Bool("hud_mark", "Mark on hotbar", true));
    private final Setting.Mode markStyle = add(new Setting.Mode("mark_style", "Mark style", "Padlock", "Frame", "Both"));
    private final Setting.Bool blockSwap = add(new Setting.Bool("block_swap", "Block swap hands (F)", true));
    private String cacheSrc = null;
    private Set<Integer> cache = new LinkedHashSet<>();

    public LockSlotModule() {
        super("lock_slot", "LockSlot", "player");
    }

    private Set<Integer> set() {
        if (!locked.value.equals(cacheSrc)) {
            cacheSrc = locked.value;
            cache = new LinkedHashSet<>();
            for (String p : locked.value.split(",")) {
                try {
                    if (!p.isBlank()) cache.add(Integer.parseInt(p.trim()));
                } catch (NumberFormatException ignored) { }
            }
        }
        return cache;
    }

    public boolean isLocked(int invIndex) {
        return isEnabled() && set().contains(invIndex);
    }

    public void toggleLock(int invIndex) {
        Set<Integer> s = new LinkedHashSet<>(set());
        if (!s.remove(invIndex)) s.add(invIndex);
        StringBuilder sb = new StringBuilder();
        for (int i : s) {
            if (sb.length() > 0) sb.append(',');
            sb.append(i);
        }
        locked.value = sb.toString();
        ModuleManager.save();
    }

    /** Слот хотбара/руки: клавиша F не должна менять местами заблокированные предметы. */
    public boolean blocksSwapHands(ClientPlayerEntity p) {
        if (!isEnabled() || !blockSwap.value || p == null) return false;
        return set().contains(p.getInventory().selectedSlot) || set().contains(40);
    }

    /** Переключает замок на выбранном слоте хотбара (клавиша вне инвентаря). */
    public void toggleSelected(MinecraftClient mc) {
        if (mc.player == null) return;
        int i = mc.player.getInventory().selectedSlot;
        toggleLock(i);
        mc.player.sendMessage(net.minecraft.text.Text.translatable(
                set().contains(i) ? "kitkatvisuals.msg.lock_on" : "kitkatvisuals.msg.lock_off", i + 1), true);
    }

    /** Визуальный индикатор на панели быстрого доступа. Рисуется поверх ванильного хотбара. */
    @Override
    public void renderHud(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!hudMark.value || mc.player == null || mc.options.hudHidden || mc.player.isSpectator()) return;
        Set<Integer> s = set();
        if (s.isEmpty()) return;
        int cx = ctx.getScaledWindowWidth() / 2;
        int y = ctx.getScaledWindowHeight() - 19;
        boolean padlock = !markStyle.is("Frame");
        boolean frame = !markStyle.is("Padlock");
        float pulse = 0.75f + 0.25f * MathHelper.sin((System.currentTimeMillis() % 2000L) / 2000f * MathHelper.TAU);
        int edge = ((int) (0xD0 * pulse) << 24) | 0xFF5050;
        for (int i = 0; i < 9; i++) {
            if (!s.contains(i)) continue;
            int x = cx - 90 + i * 20 + 3;
            ctx.fill(x, y, x + 16, y + 16, 0x40FF3030);
            if (frame) {
                ctx.fill(x - 1, y - 1, x + 17, y, edge);
                ctx.fill(x - 1, y + 16, x + 17, y + 17, edge);
                ctx.fill(x - 1, y, x, y + 16, edge);
                ctx.fill(x + 16, y, x + 17, y + 16, edge);
            }
            if (padlock) drawPadlock(ctx, x + 10, y + 9);
        }
        // Вторая рука (если заблокирована): слева от хотбара.
        if (s.contains(40)) {
            int x = cx - 91 - 29 + 3;
            ctx.fill(x, y, x + 16, y + 16, 0x40FF3030);
            drawPadlock(ctx, x + 10, y + 9);
        }
    }

    /** Замок 6x7 пикселей: чёрная подложка, жёлтая дужка и красное тело. */
    private static void drawPadlock(DrawContext c, int x, int y) {
        c.fill(x, y, x + 6, y + 7, 0xE0000000);
        c.fill(x + 1, y, x + 5, y + 1, 0xFFFFD34D);       // дужка сверху
        c.fill(x + 1, y + 1, x + 2, y + 3, 0xFFFFD34D);   // левая стойка
        c.fill(x + 4, y + 1, x + 5, y + 3, 0xFFFFD34D);   // правая стойка
        c.fill(x, y + 3, x + 6, y + 7, 0xFFFF4D4D);       // тело замка
        c.fill(x + 2, y + 4, x + 4, y + 6, 0xFF330000);   // скважина
    }
}
