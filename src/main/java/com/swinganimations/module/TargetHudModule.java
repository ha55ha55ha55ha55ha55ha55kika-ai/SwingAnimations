package com.swinganimations.module;

import com.swinganimations.event.AttackEvent;
import com.swinganimations.event.EventBus;
import com.swinganimations.gui.Hud;
import com.swinganimations.gui.HudLayout;
import com.swinganimations.gui.Ui;
import com.swinganimations.util.Spring;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.MathHelper;

import java.util.Locale;

/** Панель цели: здоровье (с пружинной анимацией), поглощение, броня и предметы в руках. */
public final class TargetHudModule extends Module {
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final EquipmentSlot[] HANDS = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};

    private final Setting.Mode source = add(new Setting.Mode("source", "Target", "Attacked", "Crosshair", "Both"));
    private final Setting.Num hold = add(new Setting.Num("hold", "Hold time (ms)", 3000f, 500f, 8000f, 100f));
    private final Setting.Bool armor = add(new Setting.Bool("armor", "Armor", true));
    private final Setting.Bool hands = add(new Setting.Bool("hands", "Held items", true));
    private final Setting.Num offX = add(new Setting.Num("x", "X offset", 60f, -300f, 300f, 5f));
    private final Setting.Num offY = add(new Setting.Num("y", "Y offset", 30f, -200f, 300f, 5f));

    private final HudLayout.Win win = HudLayout.win("target_hud");
    private LivingEntity target;
    private long lastSeen;
    private float shownHp = Float.NaN, shownAbs = Float.NaN;
    private String hpText = "";
    private LivingEntity nameFor;
    private String nameStr = "";
    private int armorShown = -1;
    private String armorStr = "";
    private final Spring hp = new Spring(1f, 160f, 20f);
    private final Spring appear = new Spring(0f, 150f, 18f);

    public TargetHudModule() {
        super("target_hud", "TargetHud", "hud");
        EventBus.on(AttackEvent.class, e -> {
            if (isEnabled() && !source.is("Crosshair")) select(e.target());
        });
    }

    @Override
    public void onDisable() {
        target = null;
        appear.snap(0f);
    }

    private static float frac(LivingEntity e) {
        return MathHelper.clamp(e.getHealth() / Math.max(1f, e.getMaxHealth()), 0f, 1f);
    }

    private void select(Entity e) {
        if (!(e instanceof LivingEntity le) || !le.isAlive()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (e == mc.player) return;
        if (le != target) {
            target = le;
            hp.snap(frac(le));
            nameFor = null;
            shownHp = Float.NaN;
        }
        lastSeen = System.currentTimeMillis();
    }

    @Override
    public void tick(MinecraftClient mc) {
        if (source.is("Attacked")) return;
        Entity t = mc.targetedEntity;
        if (t != null) select(t);
    }

    @Override
    public void renderHud(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden) return;
        long now = System.currentTimeMillis();
        boolean show = target != null && target.isAlive() && !target.isRemoved() && now - lastSeen <= hold.value;
        appear.target = show ? 1f : 0f;
        float a = appear.update();
        boolean real = target != null && a >= 0.02f;
        if (!real) {
            if (!show) target = null;
            // Чат открыт: показываем окно на примере игрока, чтобы его можно было перетащить.
            if (!HudLayout.editing() || mc.player == null) return;
            a = 1f;
        }
        LivingEntity e = real ? target : mc.player;
        float max = Math.max(1f, e.getMaxHealth());
        float cur = e.getHealth();
        float abs = e.getAbsorptionAmount();
        hp.target = frac(e);
        float hf = hp.update();

        boolean extra = armor.value || hands.value;
        int w = 140;
        int h = extra ? 50 : 30;
        int x = ctx.getScaledWindowWidth() / 2 + (int) offX.value;
        int y = ctx.getScaledWindowHeight() / 2 + (int) offY.value;

        HudLayout.push(ctx, win);
        MatrixStack m = ctx.getMatrices();
        m.push();
        float s = 0.85f + 0.15f * MathHelper.clamp(a, 0f, 1.15f);
        m.translate(x + w / 2f, y + h / 2f, 0f);
        m.scale(s, s, 1f);
        m.translate(-(x + w / 2f), -(y + h / 2f), 0f);

        Hud.panel(ctx, x, y, w, h);
        if (cur != shownHp || abs != shownAbs) {
            shownHp = cur;
            shownAbs = abs;
            hpText = String.format(Locale.ROOT, "%.1f", cur) + (abs > 0.05f ? String.format(Locale.ROOT, " +%.1f", abs) : "");
            nameFor = null;
        }
        if (nameFor != e) {
            nameFor = e;
            nameStr = Ui.tr().trimToWidth(e.getName().getString(), w - 12 - Hud.w(hpText) - 6);
        }
        int color = Hud.hpColor(hf);
        Hud.textRight(ctx, hpText, x + w - 6, y + 6, color);
        Hud.text(ctx, nameStr, x + 6, y + 6, Ui.TEXT);
        Hud.bar(ctx, x + 6, y + 18, w - 12, 5, hf, color);
        if (abs > 0.05f) {
            int aw = Math.round((w - 12) * MathHelper.clamp(abs / max, 0f, 1f));
            ctx.fill(x + 6, y + 24, x + 6 + aw, y + 26, 0xFFFFD24A);
        }
        if (extra) {
            int ix = x + 6;
            int iy = y + 29;
            if (armor.value) {
                for (EquipmentSlot slot : ARMOR) ix = slot(ctx, e.getEquippedStack(slot), ix, iy);
            }
            if (hands.value) {
                if (armor.value) ix += 4;
                for (EquipmentSlot slot : HANDS) ix = slot(ctx, e.getEquippedStack(slot), ix, iy);
            }
            int ar = e.getArmor();
            if (ar != armorShown) {
                armorShown = ar;
                armorStr = "AR " + ar;
            }
            Hud.textRight(ctx, armorStr, x + w - 6, iy + 4, Ui.TEXT_DIM);
        }
        m.pop();
        HudLayout.pop(ctx, win, x, y, w, h);
    }

    private static int slot(DrawContext ctx, ItemStack st, int x, int y) {
        drawStack(ctx, st, x, y);
        return x + 18;
    }

    private static void drawStack(DrawContext ctx, ItemStack st, int x, int y) {
        if (st.isEmpty()) {
            ctx.fill(x, y, x + 16, y + 16, 0x22FFFFFF);
            return;
        }
        ctx.drawItem(st, x, y);
        ctx.drawStackOverlay(Ui.tr(), st, x, y);
    }
}
