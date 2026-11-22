package com.kitkatvisuals.link;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.List;

/** Плашка «запрос в друзья» сверху по центру экрана. Рисуется всегда, независимо от того, включён ли модуль меток. */
final class FriendHud {
    private FriendHud() {}

    private static final int BG = 0xE6141418;
    private static final int ACCENT = 0xFF9D6BFF;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFB0B0B8;

    static void render(DrawContext ctx) {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c.player == null || c.options.hudHidden) return;
        List<FriendLink.Request> list = FriendLink.pendingRequests();
        if (list.isEmpty()) return;
        FriendLink.Request r = list.get(0);

        TextRenderer tr = c.textRenderer;
        Text title = Text.literal("Запрос в друзья" + (list.size() > 1 ? "  (+" + (list.size() - 1) + ")" : ""));
        Text who = Text.literal(r.name());
        MutableText keys = Text.literal("[")
                .append(FriendLink.acceptKey.getBoundKeyLocalizedText())
                .append(Text.literal("] Принять     ["))
                .append(FriendLink.denyKey.getBoundKeyLocalizedText())
                .append(Text.literal("] Отклонить"));

        int w = Math.max(170, Math.max(tr.getWidth(title), Math.max(tr.getWidth(who), tr.getWidth(keys))) + 24);
        int h = 46;
        int x = (ctx.getScaledWindowWidth() - w) / 2;
        int y = 22;

        ctx.fill(x, y, x + w, y + h, BG);
        ctx.drawBorder(x, y, w, h, 0x66FFFFFF);
        ctx.fill(x, y, x + w, y + 2, ACCENT);
        int cx = x + w / 2;
        ctx.drawCenteredTextWithShadow(tr, title, cx, y + 6, GRAY);
        ctx.drawCenteredTextWithShadow(tr, who, cx, y + 18, WHITE);
        ctx.drawCenteredTextWithShadow(tr, keys, cx, y + 31, GRAY);
        int bar = Math.round((w - 2) * FriendLink.requestFraction(r));
        ctx.fill(x + 1, y + h - 2, x + 1 + bar, y + h - 1, ACCENT);
    }
}
