package com.kitkatvisuals.gui;

import com.kitkatvisuals.link.FriendChat;
import com.kitkatvisuals.link.FriendLink;
import com.kitkatvisuals.module.FriendMarksModule;
import com.kitkatvisuals.module.ModuleManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Вкладка «Друзья» в ClickGUI: добавить друга, запросы, список (кто в сети и где), чат, кнопка «Помощь!»,
 * метки, «Кто в сети» (/pronoob), переключатель «Делиться координатами». Клавишу можно назначить вкладке (кнопка внизу)
 * и действиям «Помощь!», «Чат», «Метка», «Снять» (правый клик по кнопке).
 */
public final class FriendsPanel {
    private static final int RED = 0xB0B04040, RED_H = 0xFFE05555;
    private static final int GREEN = 0xB02F9E5A, GREEN_H = 0xFF2FD07A;
    private static final int ROW_H = 24;

    /** bind != null: по правому клику на кнопку можно назначить ей клавишу. */
    private record Hit(int x, int y, int w, int h, Runnable action, KeyBinding bind, String bindName) { }

    private final List<Hit> hits = new ArrayList<>();
    private TextFieldWidget field;
    private int fieldX, fieldY, fieldW;
    private TextFieldWidget markField;
    private int markX, markY, markW;
    /** Какому действию сейчас назначается клавиша (null: не ждём нажатия). */
    private KeyBinding listeningFor;
    private String listeningName = "";
    private float scroll, scrollTarget;
    private int contentH, viewH;
    private String armed = "";
    private long armedUntil;

    private static MinecraftClient mc() {
        return MinecraftClient.getInstance();
    }

    private static boolean in(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private TextFieldWidget field() {
        if (field == null) {
            field = new TextFieldWidget(Ui.tr(), 0, 0, 100, 12, Text.empty());
            field.setDrawsBackground(false);
            field.setMaxLength(32);
            field.setPlaceholder(Text.literal("Ник игрока (он должен быть онлайн с модом)"));
            field.setFocused(false);
        }
        return field;
    }

    private TextFieldWidget markField() {
        if (markField == null) {
            markField = new TextFieldWidget(Ui.tr(), 0, 0, 100, 12, Text.empty());
            markField.setDrawsBackground(false);
            markField.setMaxLength(40);
            markField.setPlaceholder(Text.literal("Название метки (необязательно)"));
            markField.setFocused(false);
        }
        return markField;
    }

    /** Ставит метку с названием из поля (подпись видят друзья над меткой) и очищает поле. */
    private void placeNamedMark() {
        String name = markField().getText().strip();
        FriendLink.placeMark(mc(), name);
        markField().setText("");
        FriendLink.pendingMarkLabel = "";
    }

    // ---------------------------------------------------------------- отрисовка
    public void render(DrawContext ctx, int x, int y, int w, int h, int mx, int my, int rgb) {
        hits.clear();
        MinecraftClient c = mc();
        int cy = y + 2;

        // ---- добавить друга
        int addW = 96;
        fieldX = x;
        fieldY = cy;
        fieldW = w - addW - 6;
        Ui.rect(ctx, fieldX, fieldY, fieldW, 18, 6, Ui.FIELD);
        TextFieldWidget f = field();
        f.setX(x + 6);
        f.setY(cy + 5);
        f.setWidth(fieldW - 12);
        f.render(ctx, mx, my, 0f);
        button(ctx, x + w - addW, cy, addW, 18, "Добавить", Ui.ACCENT_BG, Ui.ACCENT_LINE, mx, my, this::addFriend);
        cy += 24;

        // ---- метка с названием (клавиша метки берёт название из этого поля; ПКМ по кнопке: назначить клавишу)
        TextFieldWidget mf = markField();
        if (FriendLink.markLabelUsed) {                 // название ушло в метку по клавише: поле очищаем
            FriendLink.markLabelUsed = false;
            mf.setText("");
        }
        FriendLink.pendingMarkLabel = mf.getText().strip();
        int mbW = 110;
        markX = x;
        markY = cy;
        markW = w - mbW - 6;
        Ui.rect(ctx, markX, markY, markW, 18, 6, Ui.FIELD);
        mf.setX(x + 6);
        mf.setY(cy + 5);
        mf.setWidth(markW - 12);
        mf.render(ctx, mx, my, 0f);
        bindButton(ctx, x + w - mbW, cy, mbW, "Метка", Ui.ACCENT_BG, Ui.ACCENT_LINE, FriendLink.markKey, "Метка", mx, my, this::placeNamedMark);
        cy += 24;

        // ---- действия (ПКМ по кнопке: назначить клавишу)
        int bw = (w - 15) / 4;
        int ax = x;
        bindButton(ctx, ax, cy, bw, "Помощь!", RED, RED_H, FriendChat.helpKey, "Помощь!", mx, my, () -> FriendChat.sendHelp(c));
        ax += bw + 5;
        bindButton(ctx, ax, cy, bw, "Чат", Ui.ACCENT_BG, Ui.ACCENT_LINE, FriendChat.openKey, "Чат", mx, my, () -> ChatScreen.open(c, null));
        ax += bw + 5;
        bindButton(ctx, ax, cy, bw, "Снять", Ui.ACCENT_BG, Ui.ACCENT_LINE, FriendLink.unmarkKey, "Снять метки", mx, my, () -> FriendLink.clearMarks(c));
        ax += bw + 5;
        button(ctx, ax, cy, bw, 18, "Кто в сети", Ui.ACCENT_BG, Ui.ACCENT_LINE, mx, my, () -> FriendLink.showPronoob(c));
        cy += 24;

        // ---- настройки
        int hw = (w - 5) / 2;
        FriendMarksModule fm = ModuleManager.friendMarks;
        boolean share = fm == null || fm.shareCoords.value;
        button(ctx, x, cy, hw, 18, "Делиться коорд.: " + (share ? "ВКЛ" : "ВЫКЛ"),
                share ? GREEN : Ui.SWITCH_OFF, share ? GREEN_H : 0xFF555555, mx, my, () -> {
                    if (fm == null) return;
                    fm.shareCoords.value = !fm.shareCoords.value;
                    ModuleManager.save();
                });
        String bind = listeningFor != null ? "Нажми клавишу для «" + listeningName + "»… (Backspace: сбросить)"
                : "Бинд вкладки: " + keyName(FriendChat.friendsKey);
        button(ctx, x + hw + 5, cy, hw, 18, bind, listeningFor != null ? Ui.ACCENT_LINE : Ui.ACCENT_BG, Ui.ACCENT_LINE, mx, my,
                () -> listen(FriendChat.friendsKey, "Вкладка «Друзья»"));
        cy += 24;

        if (!FriendLink.linkReady()) {
            Ui.text(ctx, Text.literal("Включи модуль ClientLink (транспорт: HTTP-реле), иначе друзья не работают."), x, cy, 0xFFFF6B6B);
            cy += 12;
        }

        // ---- входящие запросы
        List<FriendLink.Request> reqs = FriendLink.pendingRequests();
        if (!reqs.isEmpty()) {
            Ui.text(ctx, Text.literal("Запросы в друзья · " + reqs.size()), x, cy, Ui.ACCENT);
            cy += 12;
            for (int i = 0; i < Math.min(4, reqs.size()); i++) {
                FriendLink.Request r = reqs.get(i);
                Ui.rect(ctx, x, cy, w, 20, 6, Ui.FIELD);
                Ui.text(ctx, Text.literal(r.name()), x + 8, cy + 6, Ui.TEXT);
                int dw = 70, aw = 60;
                String nick = r.name();
                button(ctx, x + w - 4 - dw, cy + 2, dw, 16, "Отклонить", RED, RED_H, mx, my, () -> FriendLink.respond(c, nick, false));
                button(ctx, x + w - 8 - dw - aw, cy + 2, aw, 16, "Принять", GREEN, GREEN_H, mx, my, () -> FriendLink.respond(c, nick, true));
                cy += 24;
            }
        }

        // ---- список друзей
        List<FriendLink.Friend> friends = new ArrayList<>(FriendLink.friends());
        friends.sort(Comparator.<FriendLink.Friend>comparingInt(fr -> fr.online() ? 0 : 1)
                .thenComparing(fr -> fr.name().toLowerCase(Locale.ROOT)));
        int online = 0;
        for (FriendLink.Friend fr : friends) if (fr.online()) online++;
        Ui.text(ctx, Text.literal("Друзья · " + friends.size() + " (в сети: " + online + ")"), x, cy, Ui.ACCENT);
        cy += 12;

        int top = cy;
        int bottom = y + h - 14;
        viewH = Math.max(10, bottom - top);
        contentH = friends.size() * ROW_H;
        float max = Math.max(0, contentH - viewH);
        scrollTarget = Math.max(0, Math.min(scrollTarget, max));
        scroll += (scrollTarget - scroll) * 0.35f;
        if (Math.abs(scrollTarget - scroll) < 0.4f) scroll = scrollTarget;

        if (friends.isEmpty()) {
            Ui.text(ctx, Text.literal("Друзей пока нет. Введи ник выше: игрок должен быть онлайн с модом."), x + 2, top + 2, Ui.TEXT_DIM);
        }
        ctx.enableScissor(x, top, x + w, bottom);
        for (int i = 0; i < friends.size(); i++) {
            FriendLink.Friend fr = friends.get(i);
            int ry = top + i * ROW_H - Math.round(scroll);
            if (ry + ROW_H < top || ry > bottom) continue;
            boolean hover = in(mx, my, x, Math.max(ry, top), w, Math.min(ry + 22, bottom) - Math.max(ry, top));
            Ui.rect(ctx, x, ry, w, 22, 6, hover ? Ui.FIELD_HOVER : Ui.FIELD);
            ctx.fill(x + 7, ry + 9, x + 11, ry + 13, fr.online() ? 0xFF55FF55 : 0xFF6A6A6A);
            int removeW = 56, chatW = 62;
            int removeX = x + w - 6 - removeW;
            int chatX = removeX - 4 - chatW;
            int textW = chatX - (x + 16) - 4;
            Ui.text(ctx, Text.literal(Ui.tr().trimToWidth(fr.name(), textW)), x + 16, ry + 3,
                    fr.online() ? 0xFF000000 | FriendLink.colorFor(fr.name()) : Ui.TEXT_DIM);
            String sub = fr.online() ? (fr.server().isEmpty() ? "в сети" : fr.server() + (fr.anarchy().isEmpty() ? "" : " · " + fr.anarchy())) : "не в сети";
            Ui.text(ctx, Text.literal(Ui.tr().trimToWidth(sub, textW)), x + 16, ry + 12, fr.online() ? 0xFF7FD67F : 0xFF777777);

            boolean visible = ry >= top - 4 && ry + 22 <= bottom + 4;
            int unread = FriendChat.unread(fr.id());
            String chatLabel = unread > 0 ? "Чат (" + unread + ")" : "Чат";
            boolean isArmed = fr.name().equals(armed) && System.currentTimeMillis() < armedUntil;
            if (visible) {
                button(ctx, chatX, ry + 3, chatW, 16, chatLabel, unread > 0 ? RED : Ui.ACCENT_BG, unread > 0 ? RED_H : Ui.ACCENT_LINE,
                        mx, my, () -> ChatScreen.open(c, fr.id()));
                String nick = fr.name();
                button(ctx, removeX, ry + 3, removeW, 16, isArmed ? "Точно?" : "Убрать", isArmed ? RED : Ui.SWITCH_OFF, RED_H,
                        mx, my, () -> {
                            if (nick.equals(armed) && System.currentTimeMillis() < armedUntil) {
                                armed = "";
                                FriendLink.remove(c, nick);
                            } else {
                                armed = nick;
                                armedUntil = System.currentTimeMillis() + 3000;
                            }
                        });
            }
        }
        ctx.disableScissor();

        // ---- строка статуса: последний ответ мода (то же, что идёт в чат игры)
        if (System.currentTimeMillis() - FriendLink.lastSayAt < 6000 && !FriendLink.lastSay.isEmpty()) {
            Ui.text(ctx, Text.literal(Ui.tr().trimToWidth(FriendLink.lastSay, w)), x, y + h - 10, 0xFFBFD8FF);
        } else {
            Ui.text(ctx, Text.literal("ПКМ по кнопке: назначить клавишу"), x, y + h - 10, Ui.TEXT_DIM);
        }
    }

    private void button(DrawContext ctx, int x, int y, int w, int h, String label, int color, int hoverColor,
                        int mx, int my, Runnable action) {
        boolean hov = in(mx, my, x, y, w, h);
        Ui.rect(ctx, x, y, w, h, 6, hov ? hoverColor : color);
        String t = Ui.tr().trimToWidth(label, w - 6);
        Text text = Text.literal(t);
        Ui.text(ctx, text, x + (w - Ui.tr().getWidth(text)) / 2, y + (h - 8) / 2, Ui.TEXT);
        hits.add(new Hit(x, y, w, h, action, null, ""));
    }

    /** Кнопка действия с биндом: на ней показана текущая клавиша, ПКМ назначает новую. */
    private void bindButton(DrawContext ctx, int x, int y, int w, String label, int color, int hoverColor,
                            KeyBinding kb, String name, int mx, int my, Runnable action) {
        boolean hov = in(mx, my, x, y, w, 18);
        boolean waiting = listeningFor != null && listeningFor == kb;
        Ui.rect(ctx, x, y, w, 18, 6, waiting ? Ui.ACCENT_LINE : hov ? hoverColor : color);
        String shown = kb == null || kb.isUnbound() ? label : label + " [" + keyName(kb) + "]";
        String t = Ui.tr().trimToWidth(waiting ? "…" : shown, w - 6);
        Text text = Text.literal(t);
        Ui.text(ctx, text, x + (w - Ui.tr().getWidth(text)) / 2, y + 5, Ui.TEXT);
        hits.add(new Hit(x, y, w, 18, action, kb, name));
    }

    private static String keyName(KeyBinding kb) {
        return kb == null || kb.isUnbound() ? "—" : kb.getBoundKeyLocalizedText().getString();
    }

    private void listen(KeyBinding kb, String name) {
        if (kb == null) return;
        listeningFor = kb;
        listeningName = name;
    }

    private void addFriend() {
        String nick = field().getText().strip();
        if (nick.isEmpty()) return;
        FriendLink.sendRequest(mc(), nick);
        field().setText("");
    }

    // ---------------------------------------------------------------- ввод
    public boolean click(double mx, double my, int button) {
        TextFieldWidget f = field();
        boolean inNick = mx >= fieldX && mx < fieldX + fieldW && my >= fieldY && my < fieldY + 18;
        boolean inMark = mx >= markX && mx < markX + markW && my >= markY && my < markY + 18;
        boolean inField = inNick || inMark;
        f.setFocused(inNick);
        markField().setFocused(inMark);
        if (inNick) f.mouseClicked(mx, my, button);
        if (inMark) markField().mouseClicked(mx, my, button);
        listeningFor = null;     // клик в любое другое место отменяет ожидание клавиши (кнопка бинда включит его снова)
        if (inField) return true;
        if (button != 0 && button != 1) return false;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (in((int) mx, (int) my, hit.x(), hit.y(), hit.w(), hit.h())) {
                MinecraftClient c = mc();
                if (button == 1) {                       // ПКМ: назначить клавишу тому, у кого есть бинд
                    if (hit.bind() == null) return false;
                    if (c != null) c.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 0.6f));
                    listen(hit.bind(), hit.bindName());
                    return true;
                }
                if (c != null) c.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 0.6f));
                hit.action().run();
                return true;
            }
        }
        return false;
    }

    public void scroll(double vertical) {
        scrollTarget -= (float) (vertical * ROW_H);
    }

    /** true, если клавишу забрала вкладка (ввод ника или назначение бинда). */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (listeningFor != null) {
            KeyBinding kb = listeningFor;
            listeningFor = null;
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) return true;
            InputUtil.Key key = keyCode == GLFW.GLFW_KEY_BACKSPACE || keyCode == GLFW.GLFW_KEY_DELETE
                    ? InputUtil.UNKNOWN_KEY : InputUtil.fromKeyCode(keyCode, scanCode);
            kb.setBoundKey(key);
            KeyBinding.updateKeysByCode();
            mc().options.write();
            return true;
        }
        TextFieldWidget f = field();
        if (f.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) f.setFocused(false);
            else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) addFriend();
            else f.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        TextFieldWidget mf = markField();
        if (mf.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) mf.setFocused(false);
            else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) placeNamedMark();
            else mf.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return false;
    }

    public boolean charTyped(char chr, int modifiers) {
        TextFieldWidget f = field();
        if (f.isFocused()) return f.charTyped(chr, modifiers);
        TextFieldWidget mf = markField();
        return mf.isFocused() && mf.charTyped(chr, modifiers);
    }

    /** Можно ли закрыть окно клавишей бинда (не вводим ник и не назначаем клавишу). */
    public boolean idle() {
        return listeningFor == null && !field().isFocused() && !markField().isFocused();
    }

    public void blur() {
        listeningFor = null;
        field().setFocused(false);
        markField().setFocused(false);
    }
}
