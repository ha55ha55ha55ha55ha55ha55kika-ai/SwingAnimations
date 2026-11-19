package com.swinganimations.fun;

import java.util.Random;

/**
 * Мини-игра «угадай число 1-10». Логика без текста: панель сама переводит lastDirection в надпись.
 * -1 = нужно меньше, 1 = нужно больше, 0 = пока нет попытки или угадал.
 */
public final class GuessGame {
    private static final Random RNG = new Random();

    public static int target = roll();
    public static int attempts;
    public static boolean won;
    public static int lastDirection;
    public static int lastGuess = -1;

    private GuessGame() {}

    private static int roll() {
        return 1 + RNG.nextInt(10);
    }

    public static void reset() {
        target = roll();
        attempts = 0;
        won = false;
        lastDirection = 0;
        lastGuess = -1;
    }

    public static void guess(int n) {
        if (won) return;
        attempts++;
        lastGuess = n;
        if (n == target) {
            won = true;
            lastDirection = 0;
            FunStats.recordGuess(attempts);
        } else {
            lastDirection = n < target ? 1 : -1;
        }
    }
}
