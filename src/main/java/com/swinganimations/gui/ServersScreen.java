package com.swinganimations.gui;

import com.swinganimations.SwingAnimationsClient;
import com.swinganimations.module.ThemeModule;
import com.swinganimations.render.Batch;
import com.swinganimations.util.Spring;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.MultiplayerServerListPinger;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Меню серверов в стиле ClickGUI: стеклянное окно, строки с иконкой, названием, MOTD, пингом (с делениями) и онлайном,
 * кнопки «Подключиться / Прямое подключение / Добавить / Изменить / Удалить / Назад», добавление и правка в самом окне.
 * Если что-то пойдёт не так, само переключается на ванильное меню (failed = true) до перезапуска игры.
 * Для FPS: только fill-примитивы, пружины и иконки считаются у видимых строк, пинг делает ванильный пингер.
 */
public final class ServersScreen extends Screen {
    /** true: меню упало, дальше показываем ванильное. */
    public static boolean failed;

    private static String lastDirect = "";
    private static int iconCounter;

    private static final int PAD = 14;
    private static final int ENTRY_H = 32;
    private static final int GAP = 4;
    private static final int GREEN = 0xFF6BE08B;
    private static final int YELLOW = 0xFFFFC93C;
    private static final int ORANGE = 0xFFFF9A3C;
    private static final int RED = 0xFFFF6B6B;

    private enum Modal { NONE, ADD, EDIT, DIRECT }

    private enum State { PENDING, OK, FAIL }

    private static final class Btn {
        final Text label;
        final int style; // 0 обычная, 1 акцент, 2 опасная
        final Runnable action;
        final BooleanSupplier enabled;
        final Spring hover = new Spring(0f, 220f, 24f);
        int x, y, w, h;

        Btn(Text label, int style, BooleanSupplier enabled, Runnable action) {
            this.label = label;
            this.style = style;
            this.enabled = enabled;
            this.action = action;
        }

        boolean hit(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }

        boolean enabled() {
            return enabled == null || enabled.getAsBoolean();
        }
    }

    private static final class Icon {
        byte[] src;
        Identifier id;
        boolean bad;
    }

    private final Screen parent;
    private final ServerList serverList;
    private final MultiplayerServerListPinger pinger = new MultiplayerServerListPinger();
    private final List<ServerInfo> servers = new ArrayList<>();
    private final Map<ServerInfo, Icon> icons = new IdentityHashMap<>();
    private final Map<ServerInfo, Spring> hovers = new IdentityHashMap<>();
    private final Map<ServerInfo, Long> pingStart = new IdentityHashMap<>();
    private final List<Btn> btns = new ArrayList<>();
    private final List<Btn> modalBtns = new ArrayList<>();
    private final long openedAt = System.currentTimeMillis();
    private final String msgConnectFail = I18n.translate("multiplayer.status.cannot_connect");
    private final String msgResolveFail = I18n.translate("multiplayer.status.cannot_resolve");

    private Btn refreshBtn;
    private boolean pinged;
    private int selected = -1;
    private float scroll, scrollTarget;
    private int wx, wy, ww, wh;
    private int lx, lw, ltop, lbot;
    private long lastClickAt;
    private int lastClickIdx = -1;
    private long deleteArmedUntil;

    private Modal modal = Modal.NONE;
    private TextFieldWidget nameField, addrField;
    private Text modalError;
    private int mx0, my0, mw, mh;

    public ServersScreen(Screen parent) {
        super(tr("servers.title", "Multiplayer"));
        this.parent = parent;
        this.serverList = new ServerList(MinecraftClient.getInstance());
        this.serverList.loadFile();
        reload();
        if (!servers.isEmpty()) selected = 0;
    }

    private static Text tr(String key, String fallback, Object... args) {
        return Text.translatableWithFallback("swinganimations." + key, fallback, args);
    }

    private boolean hasSel() { return selected >= 0 && selected < servers.size(); }

    private void reload() {
        servers.clear();
        for (int i = 0; i < serverList.size(); i++) servers.add(serverList.get(i));
    }

    // ---------------- пинг ----------------

    private void pingAll() {
        pinger.cancel();
        for (ServerInfo s : servers) ping(s);
    }

    private void ping(ServerInfo s) {
        s.ping = 0L;
        pingStart.put(s, System.currentTimeMillis());
        try {
            pinger.add(s, () -> MinecraftClient.getInstance().execute(serverList::saveFile), () -> { });
        } catch (Exception e) {
            s.label = Text.translatable("multiplayer.status.cannot_resolve");
            s.ping = -1L;
        }
    }

    private State state(ServerInfo s) {
        if (s.ping > 0L) return State.OK;
        String lbl = s.label == null ? "" : s.label.getString();
        if (lbl.equals(msgConnectFail) || lbl.equals(msgResolveFail)) return State.FAIL;
        long start = pingStart.getOrDefault(s, System.currentTimeMillis());
        return System.currentTimeMillis() - start > 10_000L ? State.FAIL : State.PENDING;
    }

    @Override
    public void tick() {
        pinger.tick();
    }

    // ---------------- раскладка ----------------

    @Override
    protected void init() {
        ww = Math.min(width - 16, 340);
        wh = Math.min(height - 16, 262);
        wx = (width - ww) / 2;
        wy = (height - wh) / 2;
        lx = wx + PAD;
        lw = ww - PAD * 2;
        ltop = wy + 46;
        int btnTop = wy + wh - 12 - 45;
        lbot = btnTop - 8;

        btns.clear();
        int bw = (lw - 10) / 3;
        btns.add(place(new Btn(tr("servers.connect", "Connect"), 1, this::hasSel, this::connectSelected), lx, btnTop, bw, 20));
        btns.add(place(new Btn(tr("servers.direct", "Direct connect"), 0, null, () -> openModal(Modal.DIRECT)), lx + bw + 5, btnTop, bw, 20));
        btns.add(place(new Btn(tr("servers.add", "Add"), 0, null, () -> openModal(Modal.ADD)), lx + (bw + 5) * 2, btnTop, lw - (bw + 5) * 2, 20));
        btns.add(place(new Btn(tr("servers.edit", "Edit"), 0, this::hasSel, () -> openModal(Modal.EDIT)), lx, btnTop + 25, bw, 20));
        btns.add(place(new Btn(Text.empty(), 2, this::hasSel, this::deleteSelected), lx + bw + 5, btnTop + 25, bw, 20));
        btns.add(place(new Btn(tr("servers.back", "Back"), 0, null, this::close), lx + (bw + 5) * 2, btnTop + 25, lw - (bw + 5) * 2, 20));

        Text rl = tr("servers.refresh", "Refresh");
        int rw = Hud.w(rl) + 20;
        refreshBtn = place(new Btn(rl, 0, null, this::refresh), wx + ww - PAD - rw, wy + 13, rw, 18);

        if (!pinged) {
            pinged = true;
            pingAll();
        }
        if (modal != Modal.NONE) modal = Modal.NONE;
        clampScroll();
    }

    private static Btn place(Btn b, int x, int y, int w, int h) {
        b.x = x;
        b.y = y;
        b.w = w;
        b.h = h;
        return b;
    }

    private int viewH() { return Math.max(10, lbot - ltop); }

    private int contentH() { return servers.isEmpty() ? 0 : servers.size() * (ENTRY_H + GAP) - GAP; }

    private void clampScroll() {
        float max = Math.max(0, contentH() - viewH());
        scrollTarget = MathHelper.clamp(scrollTarget, 0f, max);
        scroll = MathHelper.clamp(scroll, 0f, max);
    }

    private void ensureVisible(int idx) {
        int top = idx * (ENTRY_H + GAP);
        if (top < scrollTarget) scrollTarget = top;
        else if (top + ENTRY_H > scrollTarget + viewH()) scrollTarget = top + ENTRY_H - viewH();
        clampScroll();
    }

    // ---------------- действия ----------------

    private void click() {
        if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 0.6f));
    }

    private void refresh() {
        deleteArmedUntil = 0L;
        pingAll();
    }

    private void connectSelected() {
        if (hasSel()) connect(servers.get(selected));
    }

    private void connect(ServerInfo info) {
        serverList.saveFile();
        ConnectScreen.connect(this, client, ServerAddress.parse(info.address), info, false, null);
    }

    private void deleteSelected() {
        if (!hasSel()) return;
        long now = System.currentTimeMillis();
        if (now > deleteArmedUntil) {
            deleteArmedUntil = now + 3000L; // первый клик только «вооружает» кнопку
            return;
        }
        deleteArmedUntil = 0L;
        ServerInfo s = servers.get(selected);
        serverList.remove(s);
        serverList.saveFile();
        disposeIcon(s);
        reload();
        selected = Math.min(selected, servers.size() - 1);
        clampScroll();
    }

    // ---------------- окно добавления / правки ----------------

    private TextFieldWidget field(int boxY, int max, String text) {
        TextFieldWidget f = new TextFieldWidget(textRenderer, mx0 + 16, boxY + 4, mw - 32, 12, Text.empty());
        f.setDrawsBackground(false);
        f.setMaxLength(max);
        f.setText(text);
        f.setFocused(false);
        return f;
    }

    private void openModal(Modal m) {
        if (m == Modal.EDIT && !hasSel()) return;
        modal = m;
        modalError = null;
        deleteArmedUntil = 0L;
        mw = Math.min(width - 20, 250);
        mh = m == Modal.DIRECT ? 86 : 116;
        mx0 = (width - mw) / 2;
        my0 = (height - mh) / 2;
        modalBtns.clear();
        int by = my0 + mh - 26;
        int half = (mw - 32 - 6) / 2;
        modalBtns.add(place(new Btn(tr("servers.apply", "Apply"), 1, null, this::applyModal), mx0 + 16, by, half, 18));
        modalBtns.add(place(new Btn(tr("servers.cancel", "Cancel"), 0, null, this::closeModal), mx0 + 16 + half + 6, by, mw - 32 - half - 6, 18));
        if (m == Modal.DIRECT) {
            nameField = null;
            addrField = field(my0 + 28, 128, lastDirect);
            addrField.setFocused(true);
        } else if (m == Modal.ADD) {
            nameField = field(my0 + 26, 64, I18n.translate("selectServer.defaultName"));
            addrField = field(my0 + 52, 128, "");
            addrField.setFocused(true);
        } else {
            ServerInfo s = servers.get(selected);
            nameField = field(my0 + 26, 64, s.name == null ? "" : s.name);
            addrField = field(my0 + 52, 128, s.address == null ? "" : s.address);
            nameField.setFocused(true);
        }
    }

    private void closeModal() {
        modal = Modal.NONE;
        nameField = null;
        addrField = null;
        modalError = null;
    }

    private void applyModal() {
        String addr = addrField == null ? "" : addrField.getText().trim();
        if (addr.isEmpty()) {
            modalError = tr("servers.need_address", "Enter a server address");
            return;
        }
        switch (modal) {
            case DIRECT -> {
                lastDirect = addr;
                ServerInfo info = new ServerInfo(I18n.translate("selectServer.defaultName"), addr, ServerInfo.ServerType.OTHER);
                closeModal();
                connect(info);
            }
            case ADD -> {
                String name = nameField.getText().trim();
                ServerInfo info = new ServerInfo(name.isEmpty() ? addr : name, addr, ServerInfo.ServerType.OTHER);
                serverList.add(info, false);
                serverList.saveFile();
                reload();
                selected = servers.size() - 1;
                ensureVisible(selected);
                ping(info);
                closeModal();
            }
            case EDIT -> {
                if (hasSel()) {
                    ServerInfo s = servers.get(selected);
                    String name = nameField.getText().trim();
                    s.name = name.isEmpty() ? addr : name;
                    s.address = addr;
                    serverList.saveFile();
                    ping(s);
                }
                closeModal();
            }
            default -> closeModal();
        }
    }

    // ---------------- иконки серверов ----------------

    private Identifier iconFor(ServerInfo info) {
        byte[] src = info.getFavicon();
        Icon ic = icons.get(info);
        if (src == null) {
            if (ic != null) disposeIcon(info);
            return null;
        }
        if (ic != null && ic.src == src) return ic.bad ? null : ic.id;
        if (ic != null) disposeIcon(info);
        ic = new Icon();
        ic.src = src;
        icons.put(info, ic);
        try (ByteArrayInputStream in = new ByteArrayInputStream(src)) {
            NativeImage img = NativeImage.read(in);
            if (img.getWidth() != 64 || img.getHeight() != 64) {
                img.close();
                ic.bad = true;
            } else {
                Identifier id = Identifier.of("swinganimations", "server_icon/" + (++iconCounter));
                client.getTextureManager().registerTexture(id, new NativeImageBackedTexture(img));
                ic.id = id;
            }
        } catch (Exception e) {
            ic.bad = true;
        }
        return ic.bad ? null : ic.id;
    }

    private void disposeIcon(ServerInfo info) {
        Icon ic = icons.remove(info);
        if (ic != null && ic.id != null && client != null) client.getTextureManager().destroyTexture(ic.id);
    }

    // ---------------- отрисовка ----------------

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        try {
            draw(ctx, mx, my);
        } catch (Throwable t) {
            failed = true;
            SwingAnimationsClient.LOGGER.error("Меню серверов упало, возвращаю ванильное", t);
            if (client != null) client.setScreen(new MultiplayerScreen(parent));
        }
    }

    private void draw(DrawContext ctx, int mx, int my) {
        ThemeModule.sync();
        renderBackground(ctx, mx, my, 0f);
        int rgb = Ui.ACCENT & 0xFFFFFF;
        float f = MathHelper.clamp((System.currentTimeMillis() - openedAt) / 280f, 0f, 1f);
        ctx.fillGradient(0, 0, width, height, Batch.argb(rgb, 0.10f * f),
                Batch.argb(Batch.mix(rgb, 0x000000, 0.85f), 0.50f * f));

        boolean active = modal == Modal.NONE;
        scroll += (scrollTarget - scroll) * 0.35f;
        if (Math.abs(scrollTarget - scroll) < 0.4f) scroll = scrollTarget;

        Ui.shadow(ctx, wx, wy, ww, wh, 12);
        Ui.rect(ctx, wx, wy, ww, wh, 12, Batch.argb(Batch.mix(0x160D0D, rgb, 0.07f), 0.90f));
        Ui.outline(ctx, wx, wy, ww, wh, 12, Batch.argb(Batch.mix(0xFFFFFF, rgb, 0.35f), 0.18f));

        // заголовок
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(lx, wy + 12, 0f);
        m.scale(1.3f, 1.3f, 1f);
        Hud.text(ctx, tr("servers.title", "Multiplayer"), 0, 0, Ui.TEXT);
        m.pop();
        Hud.text(ctx, tr("servers.count", "Servers: %s", servers.size()), lx, wy + 27, Ui.TEXT_DIM);
        drawBtn(ctx, refreshBtn, mx, my, active, rgb, null);

        // список
        ctx.enableScissor(lx - 2, ltop, lx + lw + 2, lbot);
        if (servers.isEmpty()) {
            Text t = tr("servers.empty", "No servers yet. Press \"Add\"");
            Hud.text(ctx, t, lx + (lw - Hud.w(t)) / 2, ltop + 26, 0x80BEC3CD);
        }
        int y = ltop - Math.round(scroll);
        for (int i = 0; i < servers.size(); i++) {
            if (y + ENTRY_H >= ltop && y <= lbot) drawEntry(ctx, i, servers.get(i), y, mx, my, active, rgb);
            y += ENTRY_H + GAP;
        }
        ctx.disableScissor();
        drawScrollbar(ctx, rgb);

        // кнопки
        boolean armed = System.currentTimeMillis() < deleteArmedUntil;
        for (Btn b : btns) {
            Text over = null;
            if (b.style == 2) over = armed ? tr("servers.confirm", "Confirm?") : tr("servers.delete", "Delete");
            drawBtn(ctx, b, mx, my, active, rgb, over);
        }

        if (modal != Modal.NONE) drawModal(ctx, mx, my, rgb);
    }

    private void drawScrollbar(DrawContext ctx, int rgb) {
        int vh = viewH();
        int ch = contentH();
        if (ch <= vh) return;
        int th = Math.max(14, vh * vh / ch);
        int ty = ltop + Math.round((vh - th) * (scroll / (ch - vh)));
        int x = lx + lw - 3;
        Ui.rect(ctx, x, ltop, 2, vh, 1, Batch.argb(0xFFFFFF, 0.05f));
        Ui.rect(ctx, x, ty, 2, th, 1, Batch.argb(rgb, 0.75f));
    }

    private void drawBtn(DrawContext ctx, Btn b, int mx, int my, boolean active, int rgb, Text override) {
        boolean en = b.enabled();
        b.hover.target = active && en && b.hit(mx, my) ? 1f : 0f;
        float k = MathHelper.clamp(b.hover.update(), 0f, 1f);
        int base = b.style == 2 ? 0xFF6B6B : 0xFFFFFF;
        float a = b.style == 1 ? (en ? 0.30f + 0.25f * k : 0.10f) : (en ? 0.07f + 0.09f * k : 0.04f);
        if (b.style == 1) base = rgb;
        Ui.rect(ctx, b.x, b.y, b.w, b.h, 6, Batch.argb(base, a));
        int outline = en ? Batch.argb(Batch.mix(0xFFFFFF, rgb, k), 0.10f + 0.35f * k) : 0x10FFFFFF;
        Ui.outline(ctx, b.x, b.y, b.w, b.h, 6, outline);
        Text label = override != null ? override : b.label;
        int col = !en ? 0x60FFFFFF : (b.style == 2 ? RED : Ui.TEXT);
        Hud.text(ctx, label, b.x + (b.w - Hud.w(label)) / 2, b.y + (b.h - 8) / 2, col);
    }

    private void drawEntry(DrawContext ctx, int i, ServerInfo info, int y, int mx, int my, boolean active, int rgb) {
        int x = lx;
        int w = lw - 8;
        boolean sel = i == selected;
        boolean hov = active && mx >= x && mx < x + w && my >= y && my < y + ENTRY_H && my >= ltop && my < lbot;
        Spring hs = hovers.computeIfAbsent(info, k -> new Spring(0f, 220f, 24f));
        hs.target = hov ? 1f : 0f;
        float k = MathHelper.clamp(hs.update(), 0f, 1f);
        Ui.rect(ctx, x, y, w, ENTRY_H, 8, sel ? Batch.argb(rgb, 0.16f) : Batch.argb(0xFFFFFF, 0.05f + 0.05f * k));
        if (sel) Ui.outline(ctx, x, y, w, ENTRY_H, 8, Batch.argb(rgb, 0.55f));

        // иконка
        Identifier icon = iconFor(info);
        if (icon != null) {
            ctx.drawTexture(RenderLayer::getGuiTextured, icon, x + 6, y + 4, 0f, 0f, 24, 24, 64, 64, 64, 64);
        } else {
            Ui.rect(ctx, x + 6, y + 4, 24, 24, 6, Batch.argb(rgb, 0.25f));
            String n = info.name == null || info.name.isEmpty() ? "?" : info.name.substring(0, 1).toUpperCase(Locale.ROOT);
            ctx.drawText(Ui.tr(), n, x + 6 + (24 - Ui.tr().getWidth(n)) / 2, y + 4 + 8, Ui.ACCENT, false);
        }

        // правая колонка: пинг, деления, онлайн
        State st = state(info);
        String pingS = "";
        int pingCol = Ui.TEXT_DIM;
        int bars = 0;
        if (st == State.OK) {
            long p = info.ping;
            pingS = p + " ms";
            if (p < 80) { pingCol = GREEN; bars = 4; }
            else if (p < 150) { pingCol = GREEN; bars = 3; }
            else if (p < 250) { pingCol = YELLOW; bars = 2; }
            else { pingCol = ORANGE; bars = 1; }
        } else if (st == State.PENDING) {
            pingS = "...";
        }
        String players = "";
        if (st == State.OK && info.playerCountLabel != null) {
            players = info.playerCountLabel.getString().replace("/", " / ");
        }
        int barsX = x + w - 8 - 11;
        int pw = Ui.tr().getWidth(pingS);
        if (!pingS.isEmpty()) ctx.drawText(Ui.tr(), pingS, barsX - 4 - pw, y + 6, pingCol, false);
        if (st != State.PENDING) drawBars(ctx, barsX, y + 6, bars, pingCol);
        int plw = Ui.tr().getWidth(players);
        if (!players.isEmpty()) ctx.drawText(Ui.tr(), players, x + w - 8 - plw, y + 18, Ui.TEXT_DIM, false);

        // название и MOTD
        int tx = x + 36;
        int maxW = Math.max(20, x + w - 8 - Math.max(plw, pw + 15) - 8 - tx);
        String name = info.name == null ? "" : info.name;
        ctx.drawText(Ui.tr(), Ui.tr().trimToWidth(name, maxW), tx, y + 6, Ui.TEXT, false);
        String motd = info.label == null ? "" : info.label.getString();
        int nl = motd.indexOf('\n');
        if (nl >= 0) motd = motd.substring(0, nl);
        motd = motd.trim();
        ctx.drawText(Ui.tr(), Ui.tr().trimToWidth(motd, maxW), tx, y + 18, st == State.FAIL ? RED : 0xA0BEC3CD, false);
    }

    private static void drawBars(DrawContext ctx, int x, int y, int n, int color) {
        for (int i = 0; i < 4; i++) {
            int h = 3 + 2 * i;
            int col = i < n ? color : 0x30FFFFFF;
            ctx.fill(x + i * 3, y + 9 - h, x + i * 3 + 2, y + 9, col);
        }
    }

    private void drawModal(DrawContext ctx, int mx, int my, int rgb) {
        ctx.fill(0, 0, width, height, 0x96000000);
        Ui.shadow(ctx, mx0, my0, mw, mh, 10);
        Ui.rect(ctx, mx0, my0, mw, mh, 10, Batch.argb(Batch.mix(0x160D0D, rgb, 0.07f), 0.97f));
        Ui.outline(ctx, mx0, my0, mw, mh, 10, Batch.argb(Batch.mix(0xFFFFFF, rgb, 0.35f), 0.22f));
        Text title = switch (modal) {
            case ADD -> tr("servers.add_title", "Add server");
            case EDIT -> tr("servers.edit_title", "Edit server");
            default -> tr("servers.direct", "Direct connect");
        };
        Hud.text(ctx, title, mx0 + 16, my0 + 9, Ui.TEXT);
        if (nameField != null) drawField(ctx, nameField, my0 + 26, tr("servers.name_hint", "Server name"), mx, my, rgb);
        int addrBoxY = modal == Modal.DIRECT ? my0 + 28 : my0 + 52;
        drawField(ctx, addrField, addrBoxY, tr("servers.address_hint", "Server address"), mx, my, rgb);
        if (modalError != null) Hud.text(ctx, modalError, mx0 + 16, addrBoxY + 26, RED);
        for (Btn b : modalBtns) drawBtn(ctx, b, mx, my, true, rgb, null);
    }

    private void drawField(DrawContext ctx, TextFieldWidget f, int boxY, Text hint, int mx, int my, int rgb) {
        int bx = mx0 + 10, bw = mw - 20;
        Ui.rect(ctx, bx, boxY, bw, 20, 6, Batch.argb(0xFFFFFF, 0.07f));
        Ui.outline(ctx, bx, boxY, bw, 20, 6, f.isFocused() ? Ui.ACCENT_LINE : Batch.argb(0xFFFFFF, 0.10f));
        if (f.getText().isEmpty() && !f.isFocused()) Hud.text(ctx, hint, bx + 6, boxY + 6, 0x70BEC3CD);
        f.render(ctx, mx, my, 0f);
    }

    // ---------------- ввод ----------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return true;
        if (modal != Modal.NONE) {
            for (Btn b : modalBtns) {
                if (b.hit(mx, my)) {
                    click();
                    b.action.run();
                    return true;
                }
            }
            if (nameField != null && inBox(mx, my, my0 + 26)) {
                nameField.setFocused(true);
                addrField.setFocused(false);
                nameField.mouseClicked(mx, my, button);
            } else if (inBox(mx, my, modal == Modal.DIRECT ? my0 + 28 : my0 + 52)) {
                if (nameField != null) nameField.setFocused(false);
                addrField.setFocused(true);
                addrField.mouseClicked(mx, my, button);
            } else if (mx < mx0 || mx >= mx0 + mw || my < my0 || my >= my0 + mh) {
                closeModal();
            }
            return true;
        }
        if (refreshBtn.hit(mx, my)) {
            click();
            refreshBtn.action.run();
            return true;
        }
        for (Btn b : btns) {
            if (b.hit(mx, my) && b.enabled()) {
                click();
                b.action.run();
                return true;
            }
        }
        if (mx >= lx && mx < lx + lw - 8 && my >= ltop && my < lbot) {
            int idx = (int) Math.floor((my - ltop + Math.round(scroll)) / (double) (ENTRY_H + GAP));
            int within = (int) ((my - ltop + Math.round(scroll)) - idx * (ENTRY_H + GAP));
            if (idx >= 0 && idx < servers.size() && within < ENTRY_H) {
                long now = System.currentTimeMillis();
                boolean dbl = idx == lastClickIdx && now - lastClickAt < 350L;
                selected = idx;
                lastClickIdx = idx;
                lastClickAt = now;
                deleteArmedUntil = 0L;
                click();
                if (dbl) connectSelected();
                return true;
            }
        }
        return true;
    }

    private boolean inBox(double mx, double my, int boxY) {
        return mx >= mx0 + 10 && mx < mx0 + mw - 10 && my >= boxY && my < boxY + 20;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (modal != Modal.NONE) return true;
        scrollTarget -= (float) (vertical * 26);
        clampScroll();
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (modal != Modal.NONE) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                closeModal();
            } else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                applyModal();
            } else if (keyCode == GLFW.GLFW_KEY_TAB && nameField != null) {
                boolean toAddr = nameField.isFocused();
                nameField.setFocused(!toAddr);
                addrField.setFocused(toAddr);
            } else {
                TextFieldWidget f = focusedField();
                if (f != null) f.keyPressed(keyCode, scanCode, modifiers);
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DOWN && !servers.isEmpty()) {
            selected = Math.min(servers.size() - 1, selected + 1);
            ensureVisible(selected);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP && !servers.isEmpty()) {
            selected = Math.max(0, selected - 1);
            ensureVisible(selected);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            connectSelected();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_F5) {
            refresh();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private TextFieldWidget focusedField() {
        if (nameField != null && nameField.isFocused()) return nameField;
        if (addrField != null && addrField.isFocused()) return addrField;
        return null;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (modal != Modal.NONE) {
            TextFieldWidget f = focusedField();
            return f != null && f.charTyped(chr, modifiers);
        }
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void removed() {
        pinger.cancel();
        pinged = false;
        serverList.saveFile();
        for (ServerInfo s : new ArrayList<>(icons.keySet())) disposeIcon(s);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
