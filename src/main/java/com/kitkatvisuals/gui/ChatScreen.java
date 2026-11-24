package com.kitkatvisuals.gui;

import com.kitkatvisuals.link.FriendChat;
import com.kitkatvisuals.link.FriendLink;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * КитКатГрам: слева друзья (кто в сети и на каком сервере), справа переписка и поле ввода.
 * Список обновляется сам: FriendLink раз в ~3 с синхронизируется с реле.
 */
public final class ChatScreen extends Screen {
    private static final int ROW_H = 24;
    private static final int SEND_W = 40;

    private record Bubble(boolean mine, List<OrderedText> lines, int w, int h) {}

    private final Screen parent;
    private UUID selected;
    private TextFieldWidget input;
    private String draft = "";

    private int px, py, pw, ph, listW;
    private float scroll, scrollTarget;     // пикселей вверх от самого низа (0 = читаем последние)
    private int listScroll;

    private List<Bubble> cache = List.of();
    private String cacheKey = "";
    private int cacheTotal;

    private ChatScreen(Screen parent, UUID selected) {
        super(Text.literal("KitKatGram"));
        this.parent = parent;
        this.selected = selected;
    }

    public static void open(MinecraftClient c, UUID friend) {
        c.setScreen(new ChatScreen(c.currentScreen, friend));
    }

    // ---------------------------------------------------------------- инициализация
    @Override
    protected void init() {
        if (input != null) draft = input.getText();
        pw = Math.min(width - 16, 460);
        ph = Math.min(height - 16, 300);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        listW = Math.max(90, Math.min(130, pw / 3));
        int ix = px + listW + 8;
        int iw = pw - listW - 16 - SEND_W - 6;
        input = new TextFieldWidget(textRenderer, ix + 6, py + ph - 22, iw - 12, 12, Text.empty());
        input.setDrawsBackground(false);
        input.setMaxLength(FriendChat.MAX_LEN);
        input.setPlaceholder(Text.literal("Написать…"));
        input.setText(draft);
        input.setFocused(true);
        ensureSelection();
        FriendChat.setViewing(selected);
    }

    private void ensureSelection() {
        List<FriendLink.Friend> fl = sortedFriends();
        if (fl.isEmpty()) return;
        if (selected != null) {
            for (FriendLink.Friend f : fl) if (f.id().equals(selected)) return;
        }
        selected = fl.get(0).id();      // первый: с непрочитанным, потом онлайн, потом по алфавиту
        FriendChat.setViewing(selected);
    }

    private List<FriendLink.Friend> sortedFriends() {
        List<FriendLink.Friend> l = new ArrayList<>(FriendLink.friends());
        l.sort(Comparator.<FriendLink.Friend>comparingInt(f -> FriendChat.unread(f.id()) > 0 ? 0 : 1)
                .thenComparingInt(f -> f.online() ? 0 : 1)
                .thenComparing(f -> f.name().toLowerCase(Locale.ROOT)));
        return l;
    }

    private FriendLink.Friend selectedFriend() {
        if (selected == null) return null;
        for (FriendLink.Friend f : FriendLink.friends()) if (f.id().equals(selected)) return f;
        return null;
    }

    private void select(UUID id) {
        if (id.equals(selected)) return;
        selected = id;
        scroll = scrollTarget = 0;
        FriendChat.status = "";
        FriendChat.setViewing(id);
    }

    // ---------------------------------------------------------------- отрисовка
    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        renderBackground(ctx, mx, my, 0f);
        ensureSelection();
        if (selected != null) FriendChat.markRead(selected);
        TextRenderer tr = textRenderer;
        int accentRgb = Ui.ACCENT & 0xFFFFFF;

        Ui.shadow(ctx, px, py, pw, ph, 10);
        Ui.rect(ctx, px, py, pw, ph, 10, Ui.BG);
        Ui.outline(ctx, px, py, pw, ph, 10, 0x30000000 | accentRgb);

        // ---- левая колонка: друзья
        Ui.text(ctx, Text.literal("KitKatGram"), px + 10, py + 9, Ui.ACCENT);
        ctx.fill(px + 6, py + 24, px + listW, py + 25, Ui.SEP);
        int listTop = py + 28;
        int listBottom = py + ph - 8;
        List<FriendLink.Friend> friends = sortedFriends();
        int maxList = Math.max(0, friends.size() * ROW_H - (listBottom - listTop));
        listScroll = Math.max(0, Math.min(listScroll, maxList));
        ctx.enableScissor(px + 4, listTop, px + listW, listBottom);
        for (int i = 0; i < friends.size(); i++) {
            FriendLink.Friend f = friends.get(i);
            int ry = listTop + i * ROW_H - listScroll;
            if (ry + ROW_H < listTop || ry > listBottom) continue;
            boolean sel = f.id().equals(selected);
            boolean hover = mx >= px + 4 && mx < px + listW && my >= Math.max(ry, listTop) && my < Math.min(ry + ROW_H, listBottom);
            if (sel) Ui.rect(ctx, px + 4, ry, listW - 6, ROW_H - 2, 6, Ui.ACCENT_BG);
            else if (hover) Ui.rect(ctx, px + 4, ry, listW - 6, ROW_H - 2, 6, Ui.FIELD_HOVER);
            ctx.fill(px + 9, ry + 8, px + 13, ry + 12, f.online() ? 0xFF55FF55 : 0xFF6A6A6A);
            int unread = FriendChat.unread(f.id());
            int textRight = px + listW - 6 - (unread > 0 ? 16 : 0);
            Ui.text(ctx, Text.literal(tr.trimToWidth(f.name(), textRight - (px + 17))), px + 17, ry + 3,
                    f.online() ? Ui.TEXT : Ui.TEXT_DIM);
            String sub = f.online() ? (f.server().isEmpty() ? "в сети" : f.server() + (f.anarchy().isEmpty() ? "" : " · " + f.anarchy())) : "не в сети";
            Ui.text(ctx, Text.literal(tr.trimToWidth(sub, textRight - (px + 17))), px + 17, ry + 13,
                    f.online() ? 0xFF7FD67F : 0xFF777777);
            if (unread > 0) {
                String n = unread > 9 ? "9+" : String.valueOf(unread);
                int bw = tr.getWidth(n) + 6;
                Ui.rect(ctx, px + listW - 6 - bw, ry + 6, bw, 11, 5, 0xFFE05555);
                Ui.text(ctx, Text.literal(n), px + listW - 3 - bw, ry + 8, 0xFFFFFFFF);
            }
        }
        ctx.disableScissor();
        ctx.fill(px + listW + 2, py + 8, px + listW + 3, py + ph - 8, Ui.SEP);

        // ---- правая часть
        int ix = px + listW + 8;
        int rx = px + pw - 8;
        FriendLink.Friend sel = selectedFriend();

        if (!FriendLink.linkReady()) {
            hint(ctx, ix, rx, "Чат работает через реле. Включи модуль ClientLink (HTTP-реле) и зайди на сервер.");
        } else if (friends.isEmpty()) {
            hint(ctx, ix, rx, "Пока нет друзей. Добавь: /friend add <ник> (игрок должен быть онлайн с модом).");
        } else if (sel != null) {
            drawConversation(ctx, mx, my, sel, ix, rx);
            input.render(ctx, mx, my, delta);      // поверх плашки поля ввода
        }
    }

    private void hint(DrawContext ctx, int x0, int x1, String text) {
        List<OrderedText> lines = textRenderer.wrapLines(Text.literal(text), x1 - x0 - 16);
        int y = py + ph / 2 - lines.size() * 5;
        for (OrderedText l : lines) {
            ctx.drawText(textRenderer, l, x0 + 8, y, Ui.TEXT_DIM, false);
            y += 10;
        }
    }

    private void drawConversation(DrawContext ctx, int mx, int my, FriendLink.Friend f, int x0, int x1) {
        TextRenderer tr = textRenderer;
        // шапка
        Ui.text(ctx, Text.literal(f.name()), x0 + 2, py + 7, 0xFF000000 | FriendLink.colorFor(f.name()));
        String st = f.online() ? ("● в сети" + (f.server().isEmpty() ? "" : " · " + f.server()) + (f.anarchy().isEmpty() ? "" : " · " + f.anarchy())) : "не в сети (сообщение дойдёт, когда зайдёт)";
        Ui.text(ctx, Text.literal(tr.trimToWidth(st, x1 - x0 - 4)), x0 + 2, py + 18, f.online() ? 0xFF7FD67F : 0xFF888888);
        ctx.fill(x0, py + 30, x1, py + 31, Ui.SEP);

        // сообщения
        int top = py + 34;
        int bottom = py + ph - 40;
        int maxBubble = Math.max(60, (int) ((x1 - x0) * 0.78f));
        layout(f.id(), maxBubble - 12);
        int visible = bottom - top;
        float maxScroll = Math.max(0, cacheTotal - visible);
        scrollTarget = Math.max(0, Math.min(scrollTarget, maxScroll));
        scroll += (scrollTarget - scroll) * 0.4f;
        if (Math.abs(scrollTarget - scroll) < 0.4f) scroll = scrollTarget;

        ctx.enableScissor(x0, top, x1, bottom);
        float yy = bottom + scroll;
        for (int i = cache.size() - 1; i >= 0; i--) {
            Bubble b = cache.get(i);
            yy -= b.h() + 4;
            int by = Math.round(yy);
            if (by + b.h() < top) break;
            if (by > bottom) continue;
            int bx = b.mine() ? x1 - 4 - b.w() : x0 + 4;
            Ui.rect(ctx, bx, by, b.w(), b.h(), 6, b.mine() ? Ui.fade(Ui.ACCENT, 0.40f) : Ui.FIELD);
            int ty = by + 4;
            for (OrderedText line : b.lines()) {
                ctx.drawText(tr, line, bx + 6, ty, Ui.TEXT, false);
                ty += 10;
            }
        }
        ctx.disableScissor();
        if (cache.isEmpty()) Ui.text(ctx, Text.literal("Сообщений пока нет. Напиши первым!"), x0 + 6, top + 4, Ui.TEXT_DIM);

        // статус отправки
        if (!FriendChat.status.isEmpty()) Ui.text(ctx, Text.literal(FriendChat.status), x0 + 2, py + ph - 37, 0xFFFF6B6B);

        // поле ввода и кнопка
        int iw = x1 - x0 - SEND_W - 6;
        Ui.rect(ctx, x0, py + ph - 26, iw, 20, 6, Ui.FIELD);
        int sx = x1 - SEND_W;
        boolean hov = mx >= sx && mx < sx + SEND_W && my >= py + ph - 26 && my < py + ph - 6;
        Ui.rect(ctx, sx, py + ph - 26, SEND_W, 20, 6, hov ? Ui.ACCENT_LINE : Ui.ACCENT_BG);
        Text label = Text.literal("Отпр.");
        Ui.text(ctx, label, sx + (SEND_W - tr.getWidth(label)) / 2, py + ph - 20, Ui.TEXT);
    }

    /** Переразбивает сообщения на строки, только если поменялся диалог, число сообщений или ширина. */
    private void layout(UUID id, int textW) {
        List<FriendChat.Msg> hist = FriendChat.history(id);
        String key = id + "|" + hist.size() + "|" + textW;
        if (key.equals(cacheKey)) return;
        cacheKey = key;
        List<Bubble> out = new ArrayList<>(hist.size());
        int total = 0;
        for (FriendChat.Msg m : hist) {
            List<OrderedText> lines = textRenderer.wrapLines(Text.literal(m.text()), textW);
            int w = 0;
            for (OrderedText l : lines) w = Math.max(w, textRenderer.getWidth(l));
            int h = lines.size() * 10 + 6;
            out.add(new Bubble(m.mine(), lines, w + 12, h));
            total += h + 4;
        }
        cache = out;
        cacheTotal = total;
    }

    // ---------------------------------------------------------------- ввод
    private void sendCurrent() {
        FriendLink.Friend f = selectedFriend();
        String text = input.getText();
        if (f == null || text.isBlank()) return;
        FriendChat.send(MinecraftClient.getInstance(), f, text, false);
        input.setText("");
        scrollTarget = 0;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            int listTop = py + 28;
            if (mx >= px + 4 && mx < px + listW && my >= listTop && my < py + ph - 8) {
                int idx = (int) ((my - listTop + listScroll) / ROW_H);
                List<FriendLink.Friend> fl = sortedFriends();
                if (idx >= 0 && idx < fl.size()) select(fl.get(idx).id());
                return true;
            }
            int x1 = px + pw - 8;
            if (mx >= x1 - SEND_W && mx < x1 && my >= py + ph - 26 && my < py + ph - 6) {
                sendCurrent();
                return true;
            }
            input.setFocused(true);
            input.mouseClicked(mx, my, button);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (mx < px + listW) listScroll -= (int) (vertical * ROW_H);
        else scrollTarget += (float) (vertical * 24);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            sendCurrent();
            return true;
        }
        if (input.keyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        return input.charTyped(chr, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void removed() {
        FriendChat.setViewing(null);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
