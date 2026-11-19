package com.kitkatvisuals.fun;

import java.util.Random;

/** Магический шар: тянет случайную фразу-предсказание. Чисто декоративно, в чат ничего не пишет. */
public final class MagicBall {
    private static final Random RNG = new Random();

    private static final String[][] ANSWERS = {
            {
                    "Шар говорит: сегодня не копай вниз.",
                    "Да. Но не сразу.",
                    "Нет. И даже не проси снова.",
                    "Спроси ещё раз, когда стемнеет.",
                    "Крипер уже знает ответ. Спроси его.",
                    "100% да. Шар редко ошибается. Редко.",
                    "Туман застилает ответ. Попробуй позже.",
                    "Определённо нет. Но попытка не пытка.",
                    "Звёзды говорят подождать. Звёзды часто врут.",
                    "Да, если наденешь броню.",
                    "Шар видит крипера за спиной. Обернись.",
                    "Ответ туманен, как чанк без лоадера.",
                    "Сегодня твой день. Или нет. 50 на 50.",
                    "Шар смеётся и отказывается отвечать."
            },
            {
                    "The ball says: don't dig straight down today.",
                    "Yes. But not right away.",
                    "No. And don't even ask again.",
                    "Ask again after dark.",
                    "A creeper already knows the answer. Ask it.",
                    "100% yes. The ball is rarely wrong. Rarely.",
                    "The fog hides the answer. Try later.",
                    "Definitely not. But no harm in trying.",
                    "The stars say wait. The stars lie a lot.",
                    "Yes, if you wear armor.",
                    "The ball sees a creeper behind you. Turn around.",
                    "The answer is as foggy as an unloaded chunk.",
                    "Today is your day. Or not. 50/50.",
                    "The ball laughs and refuses to answer."
            }
    };

    private MagicBall() {}

    public static String roll() {
        String[] arr = ANSWERS[Messages.ru() ? 0 : 1];
        return arr[RNG.nextInt(arr.length)];
    }
}
