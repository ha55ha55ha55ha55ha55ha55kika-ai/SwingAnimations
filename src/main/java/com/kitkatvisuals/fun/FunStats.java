package com.kitkatvisuals.fun;

/** Простая статистика фан-вкладки. Живёт в памяти, до перезапуска игры. */
public final class FunStats {
    public static int spins;
    public static int deaths;
    public static int survivals;
    public static int streak;
    public static int bestStreak;
    public static int debuffTrials;
    public static int guessGames;
    public static int guessBestAttempts = -1;

    private FunStats() {}

    public static void recordSpin(boolean died) {
        spins++;
        if (died) {
            deaths++;
            streak = 0;
        } else {
            survivals++;
            streak++;
            bestStreak = Math.max(bestStreak, streak);
        }
    }

    public static void recordDebuff() {
        debuffTrials++;
    }

    public static void recordGuess(int attempts) {
        guessGames++;
        if (guessBestAttempts < 0 || attempts < guessBestAttempts) guessBestAttempts = attempts;
    }
}
