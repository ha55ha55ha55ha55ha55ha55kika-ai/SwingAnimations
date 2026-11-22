package com.kitkatvisuals.fun;

import com.kitkatvisuals.KitKatVisualsClient;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

/**
 * «Слив» для рулетки «Депнуть предметы»: после проигрыша мод по одному выбрасывает все стаки из инвентаря.
 * Быстро, но с безопасным темпом: один стак раз в 2 тика (10 пакетов в секунду), при высоком пинге раз в 4 тика.
 * Это на порядок ниже лимитов пакетов у серверов (Paper по умолчанию около 70 в секунду), поэтому кика за спам нет.
 * Работает только пока открыт обычный игровой экран: если игрок открыл инвентарь или сундук, ждём.
 * Выбрасывается весь стак за один клик (как Ctrl+Q). Остановка: кнопка в панели, смерть, выход с сервера, творческий режим.
 */
public final class ItemDump {
    private static final int PERIOD_TICKS = 2;
    private static final int PERIOD_SLOW_TICKS = 4;
    private static final int SLOW_PING_MS = 300;
    private static final int MAX_CLICKS = 120; // страховка от зацикливания, если сервер не принимает выброс
    /** Слоты PlayerScreenHandler: хотбар, основной инвентарь, левая рука, броня. */
    private static final int[] ORDER = buildOrder();

    public static boolean active;
    private static int wait;
    private static int clicks;
    private static ClientPlayerEntity owner;

    private ItemDump() {}

    private static int[] buildOrder() {
        int[] o = new int[9 + 27 + 1 + 4];
        int k = 0;
        for (int i = 36; i <= 44; i++) o[k++] = i;
        for (int i = 9; i <= 35; i++) o[k++] = i;
        o[k++] = 45;
        for (int i = 5; i <= 8; i++) o[k++] = i;
        return o;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(ItemDump::tick);
    }

    public static boolean start() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (active || p == null || mc.interactionManager == null || p.isCreative() || p.isSpectator()) return false;
        owner = p;
        wait = 0;
        clicks = 0;
        active = true;
        return true;
    }

    public static void stop() {
        active = false;
        owner = null;
    }

    /** Сколько стаков ещё осталось выбросить (для надписи на кнопке). */
    public static int remaining() {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p == null) return 0;
        ScreenHandler h = p.playerScreenHandler;
        int n = 0;
        for (int idx : ORDER) if (h.getSlot(idx).hasStack()) n++;
        return n;
    }

    private static int period(ClientPlayerEntity p) {
        PlayerListEntry e = p.networkHandler == null ? null : p.networkHandler.getPlayerListEntry(p.getUuid());
        return e != null && e.getLatency() > SLOW_PING_MS ? PERIOD_SLOW_TICKS : PERIOD_TICKS;
    }

    private static void tick(MinecraftClient mc) {
        if (!active) return;
        try {
            ClientPlayerEntity p = mc.player;
            if (p == null || p != owner || mc.world == null || mc.interactionManager == null || p.networkHandler == null
                    || !p.isAlive() || p.isSpectator() || p.isCreative()) {
                stop();
                return;
            }
            if (mc.currentScreen instanceof HandledScreen<?>) return; // не мешаем открытому инвентарю или сундуку
            ScreenHandler h = p.playerScreenHandler;
            if (!h.getCursorStack().isEmpty()) return;
            if (++wait < period(p)) return;
            wait = 0;
            for (int idx : ORDER) {
                Slot s = h.getSlot(idx);
                if (!s.hasStack() || !s.canTakeItems(p)) continue; // пустые и «проклятые» слоты пропускаем
                if (++clicks > MAX_CLICKS) {
                    stop();
                    return;
                }
                mc.interactionManager.clickSlot(h.syncId, idx, 1, SlotActionType.THROW, p); // кнопка 1 = весь стак
                return;
            }
            stop(); // выбрасывать больше нечего
        } catch (Throwable t) {
            KitKatVisualsClient.LOGGER.warn("Fun: слив предметов остановлен: {}", t.toString());
            stop();
        }
    }
}
