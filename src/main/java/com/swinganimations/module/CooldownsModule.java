package com.swinganimations.module;

import com.swinganimations.gui.Hud;
import com.swinganimations.gui.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemCooldownManager;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Список предметов, у которых идёт перезарядка (жемчуг, хорус, щит, яблоки и т.д.). Справа сверху. */
public final class CooldownsModule extends Module {
    private record Entry(ItemStack stack, float progress) {}

    private final Setting.Num maxRows = add(new Setting.Num("rows", "Max rows", 6f, 2f, 10f, 1f));

    public CooldownsModule() {
        super("cooldowns", "Cooldowns", "hud");
    }

    @Override
    public void renderHud(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.options.hudHidden) return;
        ItemCooldownManager cm = p.getItemCooldownManager();
        PlayerInventory inv = p.getInventory();
        Set<Item> seen = new HashSet<>();
        List<Entry> list = new ArrayList<>();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack st = inv.getStack(i);
            if (st.isEmpty() || !seen.add(st.getItem())) continue;
            float pr = cm.getCooldownProgress(st, tickDelta);
            if (pr > 0f) list.add(new Entry(st, pr));
        }
        if (list.isEmpty()) return;
        list.sort((a, b) -> Float.compare(b.progress(), a.progress()));
        int n = Math.min(list.size(), (int) maxRows.value);

        int w = 104;
        int rowH = 16;
        int h = 16 + n * rowH + 2;
        int x = ctx.getScaledWindowWidth() - w - 8;
        int y = 8;
        Hud.panel(ctx, x, y, w, h);
        Hud.text(ctx, "Cooldowns", x + 6, y + 5, Ui.ACCENT);
        ctx.fill(x + 5, y + 15, x + w - 5, y + 16, Ui.SEP);
        MatrixStack m = ctx.getMatrices();
        for (int i = 0; i < n; i++) {
            Entry e = list.get(i);
            int ry = y + 18 + i * rowH;
            m.push();
            m.translate(x + 6, ry, 0f);
            m.scale(0.75f, 0.75f, 1f);
            ctx.drawItem(e.stack(), 0, 0);
            m.pop();
            String name = Ui.tr().trimToWidth(e.stack().getName().getString(), w - 52);
            Hud.text(ctx, name, x + 22, ry + 1, Ui.TEXT);
            Hud.textRight(ctx, (int) Math.ceil(e.progress() * 100f) + "%", x + w - 6, ry + 1, Ui.TEXT_DIM);
            Hud.bar(ctx, x + 22, ry + 11, w - 28, 2, e.progress(), Ui.ACCENT);
        }
    }
}
