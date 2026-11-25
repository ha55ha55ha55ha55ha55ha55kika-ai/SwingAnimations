package com.kitkatvisuals.module;

import com.kitkatvisuals.gui.Hud;
import com.kitkatvisuals.gui.HudLayout;
import com.kitkatvisuals.gui.NotesScreen;
import com.kitkatvisuals.gui.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Заметки: пока модуль включён, на экране висит перетаскиваемое HUD-окно со списком заметок
 * (координаты, «что хотел сделать» и т.п.). Писать и править заметки можно в окне редактора
 * (клавиша «Заметки» или кнопка в настройках модуля), никаких команд не нужно.
 * Хранятся в настройках модуля (скрытая строка «list»): по строке на заметку, первый символ «+» (сделано) или «-».
 */
public final class NotesModule extends Module {
    public static final int MAX_NOTES = 40;
    public static final int MAX_LEN = 90;

    public static final class Note {
        public String text;
        public boolean done;

        Note(String text, boolean done) {
            this.text = text;
            this.done = done;
        }
    }

    private final Setting.Num rows = add(new Setting.Num("rows", "Max rows", 8f, 3f, 15f, 1f));
    private final Setting.Num width = add(new Setting.Num("width", "Width", 150f, 100f, 260f, 10f));
    private final Setting.Bool hideDone = add(new Setting.Bool("hide_done", "Hide completed", false));
    private final Setting.Bool openEditor = add(new Setting.Bool("open", "Open notes editor", false));
    private final Setting.Str list = add(new Setting.Str("list", "Notes", ""));

    private final HudLayout.Win win = HudLayout.win("notes");
    private final List<Note> notes = new ArrayList<>();
    private final List<Note> shown = new ArrayList<>();
    private String parsedFrom;

    public NotesModule() {
        super("notes", "Notes", "hud");
    }

    // ---------------- данные ----------------

    /** Актуальный список (разбирается заново, только если строка настройки изменилась, например при загрузке конфига). */
    public List<Note> notes() {
        String raw = list.value == null ? "" : list.value;
        if (!raw.equals(parsedFrom)) {
            parsedFrom = raw;
            notes.clear();
            if (!raw.isEmpty()) {
                for (String line : raw.split("\n")) {
                    if (line.length() < 2) continue;
                    String t = clean(line.substring(1));
                    if (t.isEmpty()) continue;
                    notes.add(new Note(t, line.charAt(0) == '+'));
                    if (notes.size() >= MAX_NOTES) break;
                }
            }
        }
        return notes;
    }

    private static String clean(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        if (s.length() > MAX_LEN) s = s.substring(0, MAX_LEN).trim();
        return s;
    }

    private void commit() {
        StringBuilder sb = new StringBuilder();
        for (Note n : notes) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(n.done ? '+' : '-').append(n.text);
        }
        list.value = sb.toString();
        parsedFrom = list.value;
        ModuleManager.save();
    }

    /** @return false, если текст пустой или достигнут лимит. */
    public boolean addNote(String text) {
        List<Note> l = notes();
        String t = clean(text);
        if (t.isEmpty() || l.size() >= MAX_NOTES) return false;
        l.add(new Note(t, false));
        commit();
        return true;
    }

    public void toggle(int i) {
        List<Note> l = notes();
        if (i < 0 || i >= l.size()) return;
        l.get(i).done = !l.get(i).done;
        commit();
    }

    public void remove(int i) {
        List<Note> l = notes();
        if (i < 0 || i >= l.size()) return;
        l.remove(i);
        commit();
    }

    public void clearDone() {
        if (notes().removeIf(n -> n.done)) commit();
    }

    public int doneCount() {
        int c = 0;
        for (Note n : notes()) if (n.done) c++;
        return c;
    }

    public void openEditor() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen instanceof NotesScreen) return;
        mc.setScreen(new NotesScreen(mc.currentScreen, this));
    }

    // ---------------- жизненный цикл ----------------

    @Override
    public void tick(MinecraftClient mc) {
        if (openEditor.value) {
            openEditor.value = false;
            openEditor();
        }
    }

    // ---------------- HUD ----------------

    @Override
    public void renderHud(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;
        boolean edit = HudLayout.editing();

        shown.clear();
        List<Note> all = notes();
        int done = 0;
        for (Note n : all) {
            if (n.done) done++;
            if (hideDone.value && n.done) continue;
            shown.add(n);
        }
        boolean sample = false;
        if (shown.isEmpty()) {
            if (!edit) return;
            sample = true; // в режиме правки HUD показываем пример
            shown.add(new Note("XYZ: 120 64 -310 (Мир)", false));
            shown.add(new Note("Собрать незерит", false));
            shown.add(new Note("Починить элитры", true));
        }

        int n = Math.min(shown.size(), (int) rows.value);
        int more = shown.size() - n;
        int w = (int) width.value;
        int rowH = 11;
        int h = 18 + n * rowH + (more > 0 ? 11 : 0) + 3;
        int x = 8;
        int y = 40;

        HudLayout.push(ctx, win);
        Hud.panel(ctx, x, y, w, h);
        Hud.text(ctx, "Заметки", x + 6, y + 5, Ui.ACCENT);
        Hud.textRight(ctx, (sample ? 1 : done) + "/" + (sample ? 3 : all.size()), x + w - 6, y + 5, Ui.TEXT_DIM);
        ctx.fill(x + 5, y + 15, x + w - 5, y + 16, Ui.SEP);
        for (int i = 0; i < n; i++) {
            Note note = shown.get(i);
            int ry = y + 19 + i * rowH;
            int bullet = note.done ? 0x66FFFFFF : Ui.ACCENT;
            ctx.fill(x + 7, ry + 2, x + 11, ry + 6, bullet);
            String t = Ui.tr().trimToWidth(note.text, w - 24);
            int col = note.done ? Ui.TEXT_DIM : Ui.TEXT;
            Hud.text(ctx, t, x + 15, ry, col);
            if (note.done) ctx.fill(x + 15, ry + 4, x + 15 + Hud.w(t), ry + 5, Ui.TEXT_DIM);
        }
        if (more > 0) Hud.text(ctx, "+" + more + " ещё", x + 15, y + 19 + n * rowH, Ui.TEXT_DIM);
        HudLayout.pop(ctx, win, x, y, w, h);
    }
}
