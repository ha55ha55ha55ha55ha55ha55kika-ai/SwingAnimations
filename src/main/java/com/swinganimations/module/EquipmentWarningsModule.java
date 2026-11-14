package com.swinganimations.module;

import com.swinganimations.event.EventBus;
import com.swinganimations.event.NotificationEvent;
import com.swinganimations.gui.Hud;
import com.swinganimations.gui.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Предупреждение о низкой прочности брони (и элитр): надпись по центру и одноразовое уведомление. */
public final class EquipmentWarningsModule extends Module {
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final Setting.Num threshold = add(new Setting.Num("threshold", "Warn below (%)", 25f, 5f, 75f, 5f));
    private final Setting.Bool notify = add(new Setting.Bool("notify", "Notification", true));
    private final Setting.Bool onScreen = add(new Setting.Bool("on_screen", "Warning on screen", true));

    private final boolean[] warned = new boolean[4];

    public EquipmentWarningsModule() {
        super("equipment_warnings", "EquipmentWarnings", "hud");
    }

    @Override
    public void onDisable() {
        java.util.Arrays.fill(warned, false);
    }

    private static int percent(ItemStack st) {
        if (st.isEmpty() || !st.isDamageable() || st.getMaxDamage() <= 0) return 100;
        return (int) Math.floor((st.getMaxDamage() - st.getDamage()) * 100f / st.getMaxDamage());
    }

    @Override
    public void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null) return;
        for (int i = 0; i < ARMOR.length; i++) {
            ItemStack st = p.getEquippedStack(ARMOR[i]);
            boolean low = percent(st) <= threshold.value;
            if (low && !warned[i]) {
                warned[i] = true;
                if (notify.value) {
                    EventBus.post(new NotificationEvent(st.getName().getString(), percent(st) + "% durability", 0xFFB347));
                }
            } else if (!low) {
                warned[i] = false;
            }
        }
    }

    @Override
    public void renderHud(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null || !onScreen.value || mc.options.hudHidden) return;
        List<ItemStack> low = new ArrayList<>();
        for (EquipmentSlot s : ARMOR) {
            ItemStack st = p.getEquippedStack(s);
            if (percent(st) <= threshold.value) low.add(st);
        }
        if (low.isEmpty()) return;
        float pulse = 0.65f + 0.35f * (float) Math.sin(System.currentTimeMillis() / 220.0);
        int col = ((int) (pulse * 255f) << 24) | 0xFF6B6B;
        int rowH = 18;
        int w = 128;
        int h = low.size() * rowH + 6;
        int x = (ctx.getScaledWindowWidth() - w) / 2;
        int y = ctx.getScaledWindowHeight() / 2 - 70 - h;
        Hud.panel(ctx, x, y, w, h);
        for (int i = 0; i < low.size(); i++) {
            ItemStack st = low.get(i);
            int ry = y + 3 + i * rowH;
            ctx.drawItem(st, x + 5, ry);
            String name = Ui.tr().trimToWidth(st.getName().getString(), w - 60);
            Hud.text(ctx, name, x + 25, ry + 5, Ui.TEXT);
            Hud.textRight(ctx, percent(st) + "%", x + w - 6, ry + 5, col);
        }
    }
}
