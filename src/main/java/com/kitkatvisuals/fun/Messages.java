package com.kitkatvisuals.fun;

import net.minecraft.text.Text;

/**
 * Фразы мода для чата. Каждый пул: [0] = русский, [1] = английский. %d подставляется шансом проигрыша (%% = знак процента).
 * Язык берётся из ключа kitkatvisuals.fun.lang (в ru_ru.json "ru", в en_us.json "en").
 */
final class Messages {
    private Messages() {}

    static boolean ru() {
        return "ru".equals(Text.translatableWithFallback("kitkatvisuals.fun.lang", "en").getString());
    }

    /** Старт: игрок депнул жизнь. */
    static final String[][] START = {
            {
                    "Депнул жизнь? Шанс сгореть: %d%%. Пути назад нет.",
                    "Ставка принята: твоя жизнь. Вселенная тихо хихикает.",
                    "Ещё один гений у рулетки. Крутим!",
                    "Мама говорила не играть... но ты не слушал.",
                    "Ставка: ничего. Риск: всё. Классика.",
                    "Красное - это просто цвет. Наверное. (%d%%)",
                    "Смелое решение. Глупое, но смелое."
            },
            {
                    "Deposited your life? Chance to burn: %d%%. No turning back.",
                    "Bet accepted: your life. The universe is quietly giggling.",
                    "Another genius at the wheel. Spinning!",
                    "Mom said don't gamble... but you never listened.",
                    "Stake: nothing. Risk: everything. A classic.",
                    "Red is just a color. Probably. (%d%%)",
                    "Bold move. Dumb, but bold."
            }
    };

    /** Посреди прокрутки. */
    static final String[][] SUSPENSE = {
            {
                    "Тик-так... тик-так...",
                    "Тихо. Колесо думает.",
                    "Чувствуешь запах респавна? Или показалось?",
                    "Замедляется... только не красное, только не красное...",
                    "Сердце колотится? Это нормально. Это казино."
            },
            {
                    "Tick-tock... tick-tock...",
                    "Quiet. The wheel is thinking.",
                    "Smell that? Is that respawn? Or just me?",
                    "Slowing down... please not red, please not red...",
                    "Heart pounding? That's normal. That's the casino."
            }
    };

    /** Зелёное: выжил. */
    static final String[][] WIN = {
            {
                    "Зелёное! Выжил. Казино плачет.",
                    "Фортуна на твоей стороне. Пока что.",
                    "Ты выжил при шансе %d%% на смерть. Купи себе лотерейку.",
                    "Живой! Остальные просто завидуют.",
                    "Победа! Только не останавливайся (это казино говорит, не я)."
            },
            {
                    "Green! You survived. The casino weeps.",
                    "Lady Luck is on your side. For now.",
                    "You survived a %d%% death chance. Go buy a lottery ticket.",
                    "Alive! Everyone else is just jealous.",
                    "A win! Don't stop now (that's the casino talking, not me)."
            }
    };

    /** Красное: мод пишет команду. */
    static final String[][] LOSE = {
            {
                    "Красное. Увидимся на респавне. F.",
                    "Красное. Ничего личного, это просто математика.",
                    "RIP. Минус один хит-пойнт и немного гордости.",
                    "Это был не риск, а вызов судьбе. Судьба ответила.",
                    "Красное! Респавн уже греется.",
                    "Шанс был всего %d%%, но ты же особенный. F."
            },
            {
                    "Red. See you at the respawn screen. F.",
                    "Red. Nothing personal, just math.",
                    "RIP. Minus one hitpoint and some pride.",
                    "That wasn't a risk, that was a dare to fate. Fate answered.",
                    "Red! The respawn button is warming up.",
                    "The chance was only %d%%, but you're special. F."
            }
    };

    /** Красное, но игрок не в мире. */
    static final String[][] LOSE_MENU = {
            {
                    "Красное, но ты не в мире. Повезло... в этот раз.",
                    "Красное! А ты в меню. Хитрый."
            },
            {
                    "Red, but you're not in a world. Lucky... this time.",
                    "Red! But you're in the menu. Sneaky."
            }
    };
}
