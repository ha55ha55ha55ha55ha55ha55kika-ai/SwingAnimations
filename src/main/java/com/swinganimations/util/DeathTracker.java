package com.swinganimations.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * Следит за смертью своего игрока (здоровье упало до нуля) и запоминает, где и когда это случилось.
 * Работает всегда, независимо от того, включены ли модули: на него опираются DeathMarker (поставить метку)
 * и EventHud (не принимать сообщение сервера о смерти за объявление ивента).
 */
public final class DeathTracker {
    public record Death(double x, double y, double z, String dim, String server, long at, int seq) {}

    private static boolean wasDead;
    private static volatile Death last;
    private static int seq;

    private DeathTracker() {}

    /** Вызывается каждый тик из ModuleManager. */
    public static void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            wasDead = false;
            return;
        }
        boolean dead = p.getHealth() <= 0.0f;
        if (dead && !wasDead) {
            last = new Death(p.getX(), p.getY(), p.getZ(), dimKey(mc), serverKey(mc), System.currentTimeMillis(), ++seq);
        }
        wasDead = dead;
    }

    public static Death last() { return last; }

    /** Сколько мс прошло с последней смерти (Long.MAX_VALUE, если смертей не было). */
    public static long sinceDeathMs() {
        Death d = last;
        return d == null ? Long.MAX_VALUE : System.currentTimeMillis() - d.at();
    }

    public static String dimKey(MinecraftClient mc) {
        return mc.world == null ? "" : mc.world.getRegistryKey().getValue().toString();
    }

    public static String serverKey(MinecraftClient mc) {
        return mc.getCurrentServerEntry() == null ? "singleplayer" : mc.getCurrentServerEntry().address.toLowerCase(java.util.Locale.ROOT);
    }
}
