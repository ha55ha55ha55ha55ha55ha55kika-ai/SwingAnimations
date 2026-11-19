package com.swinganimations.fun;

import com.swinganimations.SwingAnimationsClient;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/**
 * Рулетка вкладки Fun: игрок ставит свою жизнь, больше ничего. Зелёное = живёшь, красное = мод пишет
 * {@link #COMMAND} (второй раз через полсекунды, если игрок ещё жив). Шанс красного настраивается вручную
 * или растёт сам в режиме «эскалация». Всё только в панели, в чат ничего не пишется.
 */
public final class FunGame {
    /** Команда, которую мод отправляет на красном (без слэша). */
    public static final String COMMAND = "suicide";

    public static final int TILE = 30;
    public static final int PITCH = 32; // плитка + зазор
    public static final int LEN = 70;   // плиток в ленте
    private static final int LAND = 58; // индекс плитки, на которой останавливаемся

    public enum Color { SAFE, DEAD }

    private static final long SPIN_MS = 4600L;
    private static final int SECOND_COMMAND_DELAY_TICKS = 10;
    private static final int HISTORY = 12;
    private static final int ESCALATION_STEP = 3;
    private static final int ESCALATION_MAX = 90;
    private static final Random RNG = new Random();

    /** Шанс красного (проигрыша) в процентах, 1..99. */
    public static int deathChance = 10;
    public static boolean escalation;
    private static int escalationBase = 10;

    public static boolean spinning;
    public static final Deque<Color> history = new ArrayDeque<>();
    public static Text status;
    public static int statusColor = 0xFFBEC3CD;
    public static Color[] strip = randomStrip();

    private static float pos = PITCH * 3.5f;
    private static float startPos;
    private static float endPos;
    private static long spinStart;
    private static Color result = Color.SAFE;
    private static boolean midSent;
    private static int lastTile = Integer.MIN_VALUE;

    private static int secondIn = -1;
    private static ClientPlayerEntity firstPlayer;

    private FunGame() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(FunGame::tick);
    }

    private static Color roll() {
        return RNG.nextInt(100) < deathChance ? Color.DEAD : Color.SAFE;
    }

    private static Color[] randomStrip() {
        Color[] a = new Color[LEN];
        for (int i = 0; i < LEN; i++) a[i] = roll();
        return a;
    }

    public static void addChance(int d) {
        if (spinning || escalation) return;
        deathChance = Math.max(1, Math.min(99, deathChance + d));
    }

    public static void toggleEscalation() {
        if (spinning) return;
        escalation = !escalation;
        if (escalation) escalationBase = deathChance;
    }

    /** Текущее положение указателя вдоль ленты, px. */
    public static float pointer() {
        if (!spinning) return pos;
        float t = Math.min(1f, (System.currentTimeMillis() - spinStart) / (float) SPIN_MS);
        float inv = 1f - t;
        float ease = 1f - inv * inv * inv * inv; // быстрый разгон, долгое торможение
        return startPos + (endPos - startPos) * ease;
    }

    /** Депнуть жизнь. false, если уже идёт прокрутка. */
    public static boolean spin() {
        if (spinning) return false;
        result = roll();

        // новая лента с учётом текущего шанса; несколько плиток вокруг указателя оставляем без скачка
        Color[] n = randomStrip();
        int c = (int) Math.floor(pos / PITCH);
        for (int d = -3; d <= 3; d++) {
            int src = c + d;
            if (src >= 0 && src < LEN) n[3 + d] = strip[src];
        }
        n[LAND] = result;
        strip = n;
        startPos = 3 * PITCH + (pos - c * PITCH);
        endPos = LAND * PITCH + PITCH / 2f + (RNG.nextFloat() - 0.5f) * TILE * 0.7f;
        spinStart = System.currentTimeMillis();
        midSent = false;
        lastTile = (int) Math.floor(startPos / PITCH);
        spinning = true;

        say(pick(Messages.START), 0xFF8BB9FF);
        return true;
    }

    // ---------------- тик ----------------

    private static void tick(MinecraftClient mc) {
        try {
            if (spinning) {
                float t = (System.currentTimeMillis() - spinStart) / (float) SPIN_MS;
                int tile = (int) Math.floor(pointer() / PITCH);
                if (tile != lastTile) {
                    lastTile = tile;
                    tickSound(mc);
                }
                if (!midSent && t > 0.55f) {
                    midSent = true;
                    say(pick(Messages.SUSPENSE), 0xFFBEC3CD);
                }
                if (t >= 1f) finish(mc);
            }
            if (secondIn >= 0 && --secondIn < 0) {
                // второй раз «на всякий случай»: только если тот же игрок ещё жив (после респавна сущность другая)
                ClientPlayerEntity p = mc.player;
                if (p != null && p == firstPlayer && p.isAlive() && p.networkHandler != null) {
                    p.networkHandler.sendChatCommand(COMMAND);
                }
                firstPlayer = null;
            }
        } catch (Throwable t) {
            SwingAnimationsClient.LOGGER.warn("Fun: ошибка тика: {}", t.toString());
            spinning = false;
        }
    }

    private static void tickSound(MinecraftClient mc) {
        if (mc.player == null) return;
        float pitch = 0.9f + RNG.nextFloat() * 0.3f;
        mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, pitch));
    }

    private static void finish(MinecraftClient mc) {
        spinning = false;
        pos = endPos;
        history.addFirst(result);
        while (history.size() > HISTORY) history.removeLast();
        FunStats.recordSpin(result == Color.DEAD);

        if (result == Color.SAFE) {
            say(pick(Messages.WIN), 0xFF6BE08B);
            if (escalation) deathChance = Math.min(ESCALATION_MAX, deathChance + ESCALATION_STEP);
            return;
        }
        if (escalation) deathChance = escalationBase;
        boolean inGame = mc.player != null && mc.player.networkHandler != null && mc.world != null;
        if (inGame) {
            say(pick(Messages.LOSE), 0xFFFF6B6B);
            mc.player.networkHandler.sendChatCommand(COMMAND);
            firstPlayer = mc.player;
            secondIn = SECOND_COMMAND_DELAY_TICKS;
        } else {
            say(pick(Messages.LOSE_MENU), 0xFFFF6B6B);
        }
    }

    // ---------------- статус (только в панели, не в чат) ----------------

    private static String pick(String[][] pool) {
        String[] arr = pool[Messages.ru() ? 0 : 1];
        String s = arr[RNG.nextInt(arr.length)];
        return s.contains("%d") ? String.format(s, deathChance) : s;
    }

    private static void say(String msg, int color) {
        status = Text.literal(msg);
        statusColor = color;
    }
}
