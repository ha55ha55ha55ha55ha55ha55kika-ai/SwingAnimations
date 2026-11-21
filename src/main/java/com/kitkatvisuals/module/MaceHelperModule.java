package com.kitkatvisuals.module;

import com.kitkatvisuals.event.AttackEvent;
import com.kitkatvisuals.event.EventBus;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Items;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * MaceHelper: после удара булавой пишет в чат, сколько здоровья потерял противник.
 * Сравнивает здоровье + поглощение до удара и после обновления от сервера (оно приходит с задержкой пинга,
 * поэтому цель опрашивается несколько тиков). Mixin не нужен: используется AttackEvent из EventBus.
 */
public final class MaceHelperModule extends Module {
    private final Setting.Mode units = add(new Setting.Mode("units", "Units", "HP", "Hearts"));
    private final Setting.Bool showAbsorb = add(new Setting.Bool("show_absorption", "Show absorption part", true));
    private final Setting.Bool showFall = add(new Setting.Bool("show_fall", "Show fall distance", true));
    private final Setting.Num waitTicks = add(new Setting.Num("wait_ticks", "Wait for server (ticks)", 10f, 3f, 40f, 1f));

    private static final class Pending {
        final int id;
        final String name;
        final float hp0;
        final float abs0;
        final double fall;
        float lastHp;
        float lastAbs;
        int age;
        int stable;

        Pending(LivingEntity e, double fall) {
            this.id = e.getId();
            this.name = e.getName().getString();
            this.hp0 = e.getHealth();
            this.abs0 = e.getAbsorptionAmount();
            this.lastHp = hp0;
            this.lastAbs = abs0;
            this.fall = fall;
        }

        boolean changed() { return lastHp != hp0 || lastAbs != abs0; }
    }

    private final List<Pending> pending = new ArrayList<>();

    public MaceHelperModule() {
        super("mace_helper", "MaceHelper", "player");
        EventBus.on(AttackEvent.class, this::onAttack);
    }

    @Override
    public void onDisable() {
        pending.clear();
    }

    private void onAttack(AttackEvent e) {
        if (!isEnabled()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        if (!mc.player.getMainHandStack().isOf(Items.MACE)) return;
        if (!(e.target() instanceof LivingEntity le) || !le.isAlive()) return;
        // повторный удар по той же цели: закрываем предыдущий замер тем, что уже пришло
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending old = it.next();
            if (old.id == le.getId()) {
                report(mc, old, false);
                it.remove();
            }
        }
        // на момент события удар ещё не отправлен: здоровье цели «до»
        pending.add(new Pending(le, mc.player.fallDistance));
    }

    @Override
    public void tick(MinecraftClient mc) {
        if (pending.isEmpty()) return;
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            p.age++;
            Entity ent = mc.world.getEntityById(p.id);
            if (!(ent instanceof LivingEntity le) || ent.isRemoved()) {
                report(mc, p, false); // цель пропала (ушла из зоны прорисовки)
                it.remove();
                continue;
            }
            float hp = le.getHealth();
            float abs = le.getAbsorptionAmount();
            if (hp != p.lastHp || abs != p.lastAbs) {
                p.lastHp = hp;
                p.lastAbs = abs;
                p.stable = 0;
            } else if (p.changed()) {
                p.stable++;
            }
            boolean dead = le.isDead();
            // значение «устоялось» (2 тика без изменений) либо вышло время ожидания
            if (dead || (p.changed() && p.stable >= 2) || p.age >= (int) waitTicks.value) {
                report(mc, p, dead);
                it.remove();
            }
        }
    }

    private void report(MinecraftClient mc, Pending p, boolean killed) {
        if (mc.player == null) return;
        float hpLost = p.hp0 - p.lastHp;
        float absLost = p.abs0 - p.lastAbs;
        float total = hpLost + absLost;

        MutableText msg;
        if (!p.changed() && !killed) {
            msg = Text.translatableWithFallback("kitkatvisuals.msg.mace_none",
                    "[Mace] %1$s: no damage detected (blocked, or the server hides health)", p.name);
        } else if (killed) {
            msg = Text.translatableWithFallback("kitkatvisuals.msg.mace_kill",
                    "[Mace] %1$s: killed, at least -%2$s", p.name, fmt(p.hp0 + p.abs0));
        } else if (showAbsorb.value && absLost > 0f && hpLost > 0f) {
            msg = Text.translatableWithFallback("kitkatvisuals.msg.mace_hit_absorb",
                    "[Mace] %1$s: -%2$s (absorption %3$s), %4$s left", p.name, fmt(total), fmt(absLost), fmt(p.lastHp + p.lastAbs));
        } else {
            msg = Text.translatableWithFallback("kitkatvisuals.msg.mace_hit",
                    "[Mace] %1$s: -%2$s, %3$s left", p.name, fmt(total), fmt(p.lastHp + p.lastAbs));
        }
        msg.formatted(killed ? Formatting.DARK_RED : (p.changed() ? Formatting.GOLD : Formatting.GRAY));
        if (showFall.value && p.fall > 0.0) {
            msg.append(Text.translatableWithFallback("kitkatvisuals.msg.mace_fall", " | fall %1$s m",
                    String.format(Locale.ROOT, "%.1f", p.fall)).formatted(Formatting.AQUA));
        }
        mc.player.sendMessage(msg, false);
    }

    /** Форматирует здоровье в выбранных единицах: HP или сердца (1 сердце = 2 HP). */
    private String fmt(float hp) {
        if (units.is("Hearts")) return String.format(Locale.ROOT, "%.1f\u2665", hp / 2f);
        return String.format(Locale.ROOT, "%.1f HP", hp);
    }
}
