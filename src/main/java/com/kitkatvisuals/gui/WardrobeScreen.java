package com.kitkatvisuals.gui;

import com.kitkatvisuals.cosmetics.CosmeticMesh;
import com.kitkatvisuals.cosmetics.CosmeticModel;
import com.kitkatvisuals.cosmetics.CosmeticState;
import com.kitkatvisuals.cosmetics.SkinMesh;
import com.kitkatvisuals.module.CosmeticsModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Гардероб: слева 3D-превью скина вместе с надетой косметикой (перетаскивание крутит, колёсико приближает),
 * справа список предметов и ползунки размера и положения выбранного предмета.
 */
public final class WardrobeScreen extends Screen {
    private static final int ROW_H = 18;
    private static final int BTN_H = 18;
    private static final int GAP = 3;
    private static final int FULL_LIGHT = 0xF000F0;

    private static final String[] SLIDER_LABELS = {"Размер", "Смещение X (вправо)", "Смещение Y (вверх)", "Смещение Z (назад)"};
    private static final float[] SLIDER_MIN = {CosmeticState.SCALE_MIN, CosmeticState.POS_MIN, CosmeticState.POS_MIN, CosmeticState.POS_MIN};
    private static final float[] SLIDER_MAX = {CosmeticState.SCALE_MAX, CosmeticState.POS_MAX, CosmeticState.POS_MAX, CosmeticState.POS_MAX};

    private final Screen parent;
    private final CosmeticsModule module;
    private final List<CosmeticModel> items = new ArrayList<>();

    private float yaw = 160f, pitch = 8f, zoom = 1f;
    private boolean dragPreview;
    private int dragSlider = -1;
    private int sel;

    private int vx, vy, vw, vh;
    private int px, py, pw, ph;
    private int listY, slidersY, sliderStep, btnY, btnW;

    public WardrobeScreen(Screen parent, CosmeticsModule module) {
        super(Text.literal("Wardrobe"));
        this.parent = parent;
        this.module = module;
        items.addAll(CosmeticModel.all());
    }

    @Override
    protected void init() {
        vx = 8;
        vy = 8;
        vw = Math.max(120, (int) (width * 0.5f) - 8);
        vh = height - 16;
        px = vx + vw + 8;
        py = 8;
        pw = Math.max(120, width - px - 8);
        ph = height - 16;
        listY = py + 26;
        btnY = py + ph - 10 - BTN_H;
        btnW = (pw - 24 - GAP) / 2;
        slidersY = listY + items.size() * (ROW_H + 2) + 10;
        int avail = btnY - 6 - slidersY;
        sliderStep = Math.max(16, Math.min(26, avail / 4));
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void close() {
        module.save();
        if (client != null) client.setScreen(parent);
    }

    private CosmeticModel current() { return items.isEmpty() ? null : items.get(MathHelper.clamp(sel, 0, items.size() - 1)); }

    // ---------------- значения ползунков ----------------

    private float get(CosmeticState s, int i) {
        return switch (i) {
            case 0 -> s.scale;
            case 1 -> s.ox;
            case 2 -> s.oy;
            default -> s.oz;
        };
    }

    private void set(CosmeticState s, int i, float v) {
        switch (i) {
            case 0 -> s.scale = v;
            case 1 -> s.ox = v;
            case 2 -> s.oy = v;
            default -> s.oz = v;
        }
    }

    private String format(CosmeticState s, int i) {
        return i == 0 ? Math.round(s.scale * 100f) + "%" : String.format(java.util.Locale.ROOT, "%.1f", get(s, i));
    }

    // ---------------- отрисовка ----------------

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        renderBackground(ctx, mx, my, delta);

        // превью
        Ui.rect(ctx, vx, vy, vw, vh, 8, Ui.BG);
        Ui.outline(ctx, vx, vy, vw, vh, 8, Ui.OUTLINE);
        drawPreview(ctx);
        Hud.text(ctx, "Тяните, чтобы вращать. Колёсико: масштаб", vx + 8, vy + vh - 14, Ui.TEXT_DIM);

        // правая панель
        Ui.rect(ctx, px, py, pw, ph, 8, Ui.BG);
        Ui.outline(ctx, px, py, pw, ph, 8, Ui.OUTLINE);
        Hud.text(ctx, "Гардероб", px + 12, py + 9, Ui.TEXT);

        for (int i = 0; i < items.size(); i++) {
            CosmeticModel m = items.get(i);
            CosmeticState s = module.state(m);
            int ry = listY + i * (ROW_H + 2);
            boolean hov = mx >= px + 12 && mx < px + pw - 12 && my >= ry && my < ry + ROW_H;
            Ui.rect(ctx, px + 12, ry, pw - 24, ROW_H, 5, hov ? Ui.FIELD_HOVER : Ui.FIELD);
            Ui.outline(ctx, px + 12, ry, pw - 24, ROW_H, 5, i == sel ? Ui.ACCENT_LINE : Ui.OUTLINE);
            int bx = px + 19, by = ry + (ROW_H - 10) / 2;
            Ui.outline(ctx, bx, by, 10, 10, 2, s.equipped ? Ui.ACCENT : Ui.OUTLINE);
            if (s.equipped) ctx.fill(bx + 3, by + 3, bx + 7, by + 7, Ui.ACCENT);
            Hud.text(ctx, m.title(), px + 36, ry + (ROW_H - 8) / 2, s.equipped ? Ui.TEXT : Ui.TEXT_DIM);
        }

        CosmeticModel cur = current();
        if (cur != null) {
            CosmeticState s = module.state(cur);
            for (int i = 0; i < 4; i++) {
                int sy = slidersY + i * sliderStep;
                Hud.text(ctx, SLIDER_LABELS[i], px + 12, sy, Ui.TEXT_DIM);
                Hud.textRight(ctx, format(s, i), px + pw - 12, sy, Ui.TEXT);
                int bx = px + 12, bw = pw - 24, by = sy + 11;
                Ui.rect(ctx, bx, by, bw, 6, 3, Ui.FIELD);
                float t = (get(s, i) - SLIDER_MIN[i]) / (SLIDER_MAX[i] - SLIDER_MIN[i]);
                int fw = Math.round(bw * MathHelper.clamp(t, 0f, 1f));
                if (fw > 0) Ui.rect(ctx, bx, by, Math.max(6, fw), 6, 3, Ui.ACCENT);
            }
        }

        button(ctx, mx, my, px + 12, btnY, "Сброс");
        button(ctx, mx, my, px + 12 + btnW + GAP, btnY, "Готово");
    }

    private void button(DrawContext ctx, int mx, int my, int x, int y, String label) {
        boolean hov = mx >= x && mx < x + btnW && my >= y && my < y + BTN_H;
        Ui.rect(ctx, x, y, btnW, BTN_H, 6, hov ? Ui.FIELD_HOVER : Ui.FIELD);
        Ui.outline(ctx, x, y, btnW, BTN_H, 6, hov ? Ui.ACCENT_LINE : Ui.OUTLINE);
        Hud.text(ctx, label, x + (btnW - Hud.w(label)) / 2, y + (BTN_H - 8) / 2, Ui.TEXT);
    }

    /** 3D-превью: скин + надетая косметика, без освещения (слой «emissive»), порядок отрисовки по глубине. */
    private void drawPreview(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        var skin = mc.player.getSkinTextures();
        Identifier skinTex = skin.texture();
        boolean slim = String.valueOf(skin.model()).equalsIgnoreCase("SLIM");

        float s = (vh - 28) / 40f * zoom; // модель ~34 пикселя в высоту (с крыльями выше)
        MatrixStack ms = ctx.getMatrices();
        ms.push();
        ms.translate(vx + vw / 2f, vy + vh / 2f + 6f, 150f);
        // вид спереди: вправо на экране = -X модели, вниз = -Y, к зрителю (больший z) = -Z модели
        ms.scale(-s, -s, -s);
        ms.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_X.rotationDegrees(pitch));
        ms.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y.rotationDegrees(yaw));
        ms.translate(-8f, -16f, -8f);
        Matrix4f m = ms.peek().getPositionMatrix();

        ctx.enableScissor(vx + 2, vy + 2, vx + vw - 2, vy + vh - 2);
        VertexConsumerProvider.Immediate vcp = ctx.getVertexConsumers();

        // группы (тело и каждая косметика) рисуем от дальней к ближней, внутри группы квады тоже по глубине
        record Group(float depth, Runnable draw) { }
        List<Group> groups = new ArrayList<>();
        float[] base = SkinMesh.base(slim);
        float[] outer = SkinMesh.overlay(slim);
        float[] body = new float[base.length + outer.length];
        System.arraycopy(base, 0, body, 0, base.length);
        System.arraycopy(outer, 0, body, base.length, outer.length);
        groups.add(new Group(CosmeticMesh.center(m, body, 0, 0, 0, 1f, 0, 0, 0)[2], () ->
                CosmeticMesh.emit(vcp.getBuffer(RenderLayer.getEntityTranslucentEmissive(skinTex)), m, body, 0, 0, 0, 1f, 0, 0, 0, FULL_LIGHT, true, true)));
        for (CosmeticModel cm : items) {
            CosmeticState st = module.state(cm);
            if (!st.equipped) continue;
            float depth = CosmeticMesh.center(m, cm.quads, cm.ax, cm.ay, cm.az, st.scale, st.ox, st.oy, st.oz)[2];
            groups.add(new Group(depth, () -> {
                CosmeticMesh.emit(vcp.getBuffer(RenderLayer.getEntityTranslucentEmissive(cm.texture)), m, cm.quads, cm.ax, cm.ay, cm.az,
                        st.scale, st.ox, st.oy, st.oz, FULL_LIGHT, true, true);
                vcp.draw();
            }));
        }
        groups.sort((a, b) -> Float.compare(a.depth(), b.depth())); // дальше = меньше z
        for (Group g : groups) {
            g.draw().run();
            vcp.draw();
        }
        ctx.draw();
        ctx.disableScissor();
        ms.pop();
    }

    // ---------------- ввод ----------------

    private int sliderAt(double mx, double my) {
        CosmeticModel cur = current();
        if (cur == null) return -1;
        for (int i = 0; i < 4; i++) {
            int sy = slidersY + i * sliderStep;
            if (mx >= px + 12 && mx < px + pw - 12 && my >= sy + 6 && my < sy + 20) return i;
        }
        return -1;
    }

    private void applySlider(int i, double mx) {
        CosmeticModel cur = current();
        if (cur == null || i < 0) return;
        float t = MathHelper.clamp((float) ((mx - (px + 12)) / (pw - 24)), 0f, 1f);
        float v = SLIDER_MIN[i] + (SLIDER_MAX[i] - SLIDER_MIN[i]) * t;
        if (i > 0) v = Math.round(v * 2f) / 2f; // шаг 0.5 пикселя
        set(module.state(cur), i, v);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);
        // кнопки
        if (my >= btnY && my < btnY + BTN_H) {
            if (mx >= px + 12 && mx < px + 12 + btnW) {
                CosmeticModel cur = current();
                if (cur != null) module.state(cur).reset(cur);
                module.save();
                return true;
            }
            if (mx >= px + 12 + btnW + GAP && mx < px + 12 + 2 * btnW + GAP) {
                close();
                return true;
            }
        }
        // список
        for (int i = 0; i < items.size(); i++) {
            int ry = listY + i * (ROW_H + 2);
            if (mx >= px + 12 && mx < px + pw - 12 && my >= ry && my < ry + ROW_H) {
                sel = i;
                if (mx < px + 34) { // галочка: надеть или снять
                    CosmeticState s = module.state(items.get(i));
                    s.equipped = !s.equipped;
                    module.save();
                }
                return true;
            }
        }
        // ползунки
        int sl = sliderAt(mx, my);
        if (sl >= 0) {
            dragSlider = sl;
            applySlider(sl, mx);
            return true;
        }
        // превью
        if (mx >= vx && mx < vx + vw && my >= vy && my < vy + vh) {
            dragPreview = true;
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragSlider >= 0) {
            applySlider(dragSlider, mx);
            return true;
        }
        if (dragPreview) {
            yaw += (float) dx * 1.2f;
            pitch = MathHelper.clamp(pitch + (float) dy * 0.8f, -60f, 60f);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragSlider >= 0) module.save();
        dragSlider = -1;
        dragPreview = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (mx >= vx && mx < vx + vw && my >= vy && my < vy + vh) {
            zoom = MathHelper.clamp(zoom + (float) vertical * 0.1f, 0.5f, 2.5f);
            return true;
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }
}
