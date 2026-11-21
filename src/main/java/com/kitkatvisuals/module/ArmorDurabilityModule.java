package com.kitkatvisuals.module;

import com.kitkatvisuals.gui.Hud;
import com.kitkatvisuals.gui.HudLayout;
import com.kitkatvisuals.gui.Ui;
import com.kitkatvisuals.render.Projection;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ArmorDurability: цветовая индикация прочности брони (зелёный -> жёлтый -> красный).
 * Своя броня: панель на HUD (двигается в режиме редактирования, как остальные окна).
 * Чужая: над головой игроков рядом рисуются четыре цветные полоски с иконками.
 *
 * Mixin не нужен. Список целей обновляется раз в 4 тика (дистанция, видимость лучом, лимит N ближайших);
 * в кадре остаются только проекция и несколько fill. Прочность чужих вещей приходит с сервера в пакете
 * экипировки как обычный компонент damage, поэтому читается так же, как у своих.
 */
public final class ArmorDurabilityModule extends Module {
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final Setting.Bool self = add(new Setting.Bool("self", "Own armor panel", true));
    private final Setting.Bool enemies = add(new Setting.Bool("enemies", "Show on players", true));
    private final Setting.Mode style = add(new Setting.Mode("style", "Color style", "Gradient", "Steps"));
    private final Setting.Bool percent = add(new Setting.Bool("percent", "Show percent", true));
    private final Setting.Num range = add(new Setting.Num("range", "Range (blocks)", 24f, 4f, 64f, 1f)
            .<Setting.Num>visibleWhen(() -> enemies.value));
    private final Setting.Num maxPlayers = add(new Setting.Num("max_players", "Max players", 8f, 1f, 20f, 1f)
            .<Setting.Num>visibleWhen(() -> enemies.value));
    private final Setting.Bool wallCheck = add(new Setting.Bool("visible_only", "Only visible players", true)
            .<Setting.Bool>visibleWhen(() -> enemies.value));

    private final HudLayout.Win win = HudLayout.win("armor_durability");
    private final List<PlayerEntity> targets = new ArrayList<>();
    private int tickCounter;

    public ArmorDurabilityModule() {
        super("armor_durability", "ArmorDurability", "render");
    }

    @Override
    public void onDisable() {
        targets.clear();
    }

    /** Доля прочности 0..1; -1, если предмет не имеет прочности. */
    private static float fraction(ItemStack st) {
        if (st.isEmpty() || !st.isDamageable() || st.getMaxDamage() <= 0) return -1f;
        return MathHelper.clamp((st.getMaxDamage() - st.getDamage()) / (float) st.getMaxDamage(), 0f, 1f);
    }

    /** RGB без альфы. Gradient: тот же переход оттенка, что у ванильной полоски; Steps: три ступени. */
    private int color(float f) {
        if (style.is("Steps")) return f > 0.6f ? 0x55FF55 : (f > 0.3f ? 0xFFD633 : 0xFF4444);
        return MathHelper.hsvToRgb(f / 3f, 0.9f, 1f);
    }

    @Override
    public void tick(MinecraftClient mc) {
        if (!enemies.value || mc.world == null || mc.player == null) {
            targets.clear();
            return;
        }
        if (tickCounter++ % 4 != 0) return;
        ClientPlayerEntity me = mc.player;
        double r2 = (double) range.value * range.value;
        List<PlayerEntity> found = new ArrayList<>();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == me || p.isSpectator() || !p.isAlive() || me.squaredDistanceTo(p) > r2) continue;
            if (wallCheck.value && !me.canSee(p)) continue;
            found.add(p);
        }
        found.sort(Comparator.comparingDouble(me::squaredDistanceTo));
        targets.clear();
        for (int i = 0; i < found.size() && i < (int) maxPlayers.value; i++) targets.add(found.get(i));
    }

    @Override
    public void renderHud(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;
        if (self.value) drawSelf(ctx, mc.player);
        if (enemies.value && Projection.valid()) drawEnemies(ctx, mc, tickDelta);
    }

    private void drawSelf(DrawContext ctx, ClientPlayerEntity p) {
        List<ItemStack> items = new ArrayList<>();
        List<Float> fr = new ArrayList<>();
        for (EquipmentSlot s : ARMOR) {
            ItemStack st = p.getEquippedStack(s);
            float f = fraction(st);
            if (f < 0f) continue;
            items.add(st);
            fr.add(f);
        }
        if (items.isEmpty()) {
            if (!HudLayout.editing()) return;
            items = List.of(new ItemStack(Items.NETHERITE_HELMET), new ItemStack(Items.DIAMOND_CHESTPLATE), new ItemStack(Items.IRON_BOOTS));
            fr = List.of(0.9f, 0.45f, 0.12f);
        }
        int rowH = 18;
        int w = percent.value ? 96 : 72;
        int h = items.size() * rowH + 6;
        int x = 8;
        int y = (ctx.getScaledWindowHeight() - h) / 2;
        HudLayout.push(ctx, win);
        Hud.panel(ctx, x, y, w, h);
        for (int i = 0; i < items.size(); i++) {
            float f = fr.get(i);
            int rgb = color(f);
            int ry = y + 3 + i * rowH;
            ctx.drawItem(items.get(i), x + 4, ry);
            int bx = x + 24;
            int bw = 40;
            ctx.fill(bx, ry + 6, bx + bw, ry + 11, 0xFF1A1A1A);
            ctx.fill(bx, ry + 6, bx + Math.max(1, Math.round(bw * f)), ry + 11, 0xFF000000 | rgb);
            if (percent.value) Hud.textRight(ctx, Math.round(f * 100f) + "%", x + w - 5, ry + 5, 0xFF000000 | rgb);
        }
        HudLayout.pop(ctx, win, x, y, w, h);
    }

    private void drawEnemies(DrawContext ctx, MinecraftClient mc, float tickDelta) {
        int sw = ctx.getScaledWindowWidth();
        int sh = ctx.getScaledWindowHeight();
        float[] out = new float[2];
        for (PlayerEntity p : targets) {
            if (!p.isAlive()) continue;
            Vec3d pos = p.getLerpedPos(tickDelta);
            if (!Projection.project(pos.x, pos.y + p.getHeight() + 0.75, pos.z, out, sw, sh)) continue;
            // 4 слота по 12 px: иконка 8x8 (масштаб 0.5) и полоска под ней
            int cell = 12;
            int total = 0;
            ItemStack[] st = new ItemStack[4];
            float[] f = new float[4];
            for (int i = 0; i < 4; i++) {
                st[i] = p.getEquippedStack(ARMOR[i]);
                f[i] = fraction(st[i]);
                if (f[i] >= 0f) total++;
            }
            if (total == 0) continue;
            int x0 = Math.round(out[0]) - total * cell / 2;
            int y0 = Math.round(out[1]) - 14;
            int n = 0;
            for (int i = 0; i < 4; i++) {
                if (f[i] < 0f) continue;
                int x = x0 + n * cell;
                n++;
                ctx.getMatrices().push();
                ctx.getMatrices().translate(x + 2, y0, 0f);
                ctx.getMatrices().scale(0.5f, 0.5f, 1f);
                ctx.drawItem(st[i], 0, 0);
                ctx.getMatrices().pop();
                int rgb = color(f[i]);
                ctx.fill(x + 1, y0 + 9, x + cell - 1, y0 + 12, 0xFF101010);
                ctx.fill(x + 1, y0 + 9, x + 1 + Math.max(1, Math.round((cell - 2) * f[i])), y0 + 12, 0xFF000000 | rgb);
            }
        }
    }
}
