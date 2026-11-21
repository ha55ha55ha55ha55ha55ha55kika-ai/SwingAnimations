package com.kitkatvisuals.module;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

/**
 * AutoTool: пока зажата кнопка атаки и прицел на блоке, выбирает в хотбаре самый быстрый инструмент.
 * Когда ломать перестали, возвращает прежний слот.
 *
 * Mixin не нужен: решение принимается в START_CLIENT_TICK, то есть до ванильного syncSelectedSlot() и
 * handleInputEvents() того же тика. Сервер узнаёт о смене слота обычным пакетом, как при прокрутке колеса.
 */
public final class AutoToolModule extends Module {
    private final Setting.Mode prefer = add(new Setting.Mode("prefer", "Prefer enchant", "None", "Silk Touch", "Fortune"));
    private final Setting.Bool saveTools = add(new Setting.Bool("save_tools", "Avoid breaking tools", true));
    private final Setting.Bool restore = add(new Setting.Bool("restore", "Restore previous slot", true));
    private final Setting.Num restoreDelay = add(new Setting.Num("restore_delay", "Restore delay (ticks)", 2f, 0f, 20f, 1f)
            .<Setting.Num>visibleWhen(() -> restore.value));

    /** Слот, который был выбран до нашего переключения (-1: мы ничего не меняли). */
    private int prevSlot = -1;
    /** Слот, на который переключили мы. Если игрок выбрал другой вручную, мы его не трогаем. */
    private int ourSlot = -1;
    private int idleTicks;

    public AutoToolModule() {
        super("auto_tool", "AutoTool", "player");
        ClientTickEvents.START_CLIENT_TICK.register(this::onStartTick);
    }

    @Override
    public void onDisable() {
        MinecraftClient mc = MinecraftClient.getInstance();
        restoreNow(mc == null ? null : mc.player);
    }

    private void onStartTick(MinecraftClient mc) {
        if (!isEnabled()) return;
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.interactionManager == null) {
            reset();
            return;
        }
        BlockState state = null;
        if (canSwitch(mc, p) && mc.options.attackKey.isPressed()
                && mc.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            BlockState st = mc.world.getBlockState(pos);
            // воздух и неразрушаемые блоки (бедрок, барьер) не считаем
            if (!st.isAir() && st.getHardness(mc.world, pos) >= 0f) state = st;
        }

        if (state != null) {
            idleTicks = 0;
            PlayerInventory inv = p.getInventory();
            int cur = inv.selectedSlot;
            int best = pickBest(inv, state);
            if (best != cur) {
                if (ourSlot < 0 || cur != ourSlot) prevSlot = cur; // новая «сессия» или игрок сменил слот сам
                inv.selectedSlot = best;
                ourSlot = best;
            }
        } else if (ourSlot >= 0 && ++idleTicks > (int) restoreDelay.value) {
            restoreNow(p);
        }
    }

    private static boolean canSwitch(MinecraftClient mc, ClientPlayerEntity p) {
        return mc.currentScreen == null && !p.isSpectator() && !p.isCreative() && !p.isUsingItem();
    }

    private int pickBest(PlayerInventory inv, BlockState state) {
        int cur = inv.selectedSlot;
        int best = cur;
        float bestScore = score(inv.getStack(cur), state);
        for (int i = 0; i < 9; i++) {
            if (i == cur) continue;
            float sc = score(inv.getStack(i), state);
            // строго лучше: при равенстве остаёмся на текущем слоте, чтобы не дёргать хотбар
            if (sc > bestScore + 1e-4f) {
                best = i;
                bestScore = sc;
            }
        }
        return best;
    }

    /** Скорость добычи с учётом Efficiency; зачарование Silk Touch/Fortune лишь разрешает ничью в его пользу. */
    private float score(ItemStack st, BlockState state) {
        float speed = st.isEmpty() ? 1f : st.getMiningSpeedMultiplier(state);
        if (speed > 1f) {
            // 1.21: Efficiency даёт атрибут mining_efficiency = уровень² + 1 (и только если скорость > 1)
            int eff = level(st, Enchantments.EFFICIENCY);
            if (eff > 0) speed += eff * eff + 1;
        }
        if (saveTools.value && st.isDamageable() && st.getMaxDamage() - st.getDamage() <= 1) return 0f;
        if (!prefer.is("None")) {
            RegistryKey<Enchantment> key = prefer.is("Silk Touch") ? Enchantments.SILK_TOUCH : Enchantments.FORTUNE;
            if (level(st, key) > 0) speed += 0.01f;
        }
        return speed;
    }

    private static int level(ItemStack st, RegistryKey<Enchantment> key) {
        if (st.isEmpty()) return 0;
        ItemEnchantmentsComponent c = EnchantmentHelper.getEnchantments(st);
        for (Object2IntMap.Entry<RegistryEntry<Enchantment>> e : c.getEnchantmentEntries()) {
            if (e.getKey().matchesKey(key)) return e.getIntValue();
        }
        return 0;
    }

    private void restoreNow(ClientPlayerEntity p) {
        if (restore.value && p != null && prevSlot >= 0 && ourSlot >= 0
                && p.getInventory().selectedSlot == ourSlot) {
            p.getInventory().selectedSlot = prevSlot;
        }
        reset();
    }

    private void reset() {
        prevSlot = -1;
        ourSlot = -1;
        idleTicks = 0;
    }
}
