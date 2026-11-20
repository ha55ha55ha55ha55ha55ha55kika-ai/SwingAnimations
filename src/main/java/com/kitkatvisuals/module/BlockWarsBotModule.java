package com.kitkatvisuals.module;

import com.kitkatvisuals.util.BlockWarsColor;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.util.List;
import java.util.Locale;

/**
 * BlockWars-бот: читает цвет из title / subtitle / actionbar, бежит к ближайшей шерсти этого цвета
 * (спринт, без прыжков на ровном месте), на нужном блоке приседает (Shift).
 * Подсветка цели вынесена в отдельный модуль BlockWarsESP.
 */
public final class BlockWarsBotModule extends Module {
    private final Setting.Num range = add(new Setting.Num("range", "Search range", 24f, 8f, 40f, 1f));
    private final Setting.Num reaction = add(new Setting.Num("reaction", "Reaction delay (ticks)", 0f, 0f, 10f, 1f));
    private final Setting.Num turnSpeed = add(new Setting.Num("turn_speed", "Turn speed (deg/tick)", 180f, 10f, 180f, 5f));
    private final Setting.Bool sneakOnTarget = add(new Setting.Bool("sneak", "Sneak on target", true));
    private final Setting.Bool debug = add(new Setting.Bool("debug", "Log received texts to chat", false));

    private long seenSeq;
    private long loggedSeq;
    private Block targetBlock;
    private BlockPos targetPos;
    private int delayLeft;
    private int rescanIn;

    public BlockWarsBotModule() {
        super("block_wars_bot", "BlockWarsBot", "movement");
    }

    @Override public boolean restoreOnLoad() { return false; }

    /** Скрытый модуль: виден только после секретного кода в поиске меню. */
    @Override public boolean secret() { return true; }

    @Override
    public void onEnable() {
        seenSeq = BlockWarsColor.latestSeq(); // старые сообщения игнорируем
        loggedSeq = seenSeq;
        resetRound();
        targetBlock = null;
    }

    @Override
    public void onDisable() {
        targetBlock = null;
        targetPos = null;
        KeyBinding.updatePressedStates(); // вернуть клавиши в реальное состояние
    }

    private void resetRound() {
        targetPos = null;
        rescanIn = 0;
        delayLeft = (int) reaction.value;
    }

    @Override
    public void tick(MinecraftClient mc) {
        if (!ModuleManager.adminUnlocked()) {
            if (isEnabled()) setEnabled(false);
            return;
        }
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return;

        if (debug.value) logNew(mc);

        if (mc.currentScreen != null) { release(mc); return; }

        // новый раунд? смотрим только на сообщения, пришедшие после прошлой проверки
        long top = BlockWarsColor.latestSeq();
        if (top != seenSeq) {
            List<BlockWarsColor.Msg> fresh = BlockWarsColor.since(seenSeq);
            seenSeq = top;
            DyeColor c = BlockWarsColor.pickColor(fresh);
            if (c != null) {
                targetBlock = BlockWarsColor.wool(c);
                resetRound();
                if (debug.value) say(mc, "цель: " + c.name().toLowerCase(Locale.ROOT));
            }
        }
        if (targetBlock == null) { release(mc); return; }

        World w = mc.world;
        if (delayLeft > 0) { delayLeft--; release(mc); return; }

        boolean onGround = p.isOnGround();

        // стоим ли на нужной шерсти
        BlockPos under = BlockPos.ofFloored(p.getX(), p.getY() - 0.2, p.getZ());
        boolean onTarget = w.getBlockState(under).isOf(targetBlock);
        if (onTarget) {
            mc.options.forwardKey.setPressed(false);
            mc.options.sprintKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
            mc.options.sneakKey.setPressed(sneakOnTarget.value);
            return;
        }
        mc.options.sneakKey.setPressed(false);

        // поиск цели
        if (targetPos == null || --rescanIn <= 0 || !w.getBlockState(targetPos).isOf(targetBlock)) {
            targetPos = findNearest(w, p);
            rescanIn = 10;
        }
        if (targetPos == null) { release(mc); return; }

        // поворот к центру блока
        double dx = targetPos.getX() + 0.5 - p.getX();
        double dz = targetPos.getZ() + 0.5 - p.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        float wantYaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
        float diff = MathHelper.wrapDegrees(wantYaw - p.getYaw());
        float step = turnSpeed.value;
        p.setYaw(p.getYaw() + MathHelper.clamp(diff, -step, step));

        boolean close = dist < 1.1; // у самой цели: без спринта, чтобы не перелететь за край
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(!close && dist > 3.0);

        // прыжок только когда реально нужен: впереди стена, или цель на блок выше. На ровном полу не прыгаем.
        boolean stepUp = targetPos.getY() > p.getBlockY();
        boolean jump = onGround && !close && (p.horizontalCollision || (stepUp && dist < 3.0));
        mc.options.jumpKey.setPressed(jump);
    }

    private BlockPos findNearest(World w, ClientPlayerEntity p) {
        int r = (int) range.value;
        BlockPos o = p.getBlockPos();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int dy = -4; dy <= 2; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    m.set(o.getX() + dx, o.getY() + dy, o.getZ() + dz);
                    if (!w.getBlockState(m).isOf(targetBlock)) continue;
                    double d = dx * dx + dz * dz + dy * dy * 4.0;
                    if (!w.getBlockState(m.up()).isAir()) d += 4.0; // сверху занято: берём, но свободные предпочитаем
                    if (d < bestD) { bestD = d; best = m.toImmutable(); }
                }
            }
        }
        return best;
    }

    private void release(MinecraftClient mc) {
        mc.options.forwardKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
    }

    private void logNew(MinecraftClient mc) {
        for (BlockWarsColor.Msg m : BlockWarsColor.since(loggedSeq)) {
            String ch = m.channel() == 0 ? "title" : m.channel() == 1 ? "subtitle" : "actionbar";
            say(mc, "[" + ch + "] " + m.text());
        }
        loggedSeq = BlockWarsColor.latestSeq();
    }

    private static void say(MinecraftClient mc, String msg) {
        if (mc.inGameHud == null) return;
        mc.inGameHud.getChatHud().addMessage(Text.literal("[BlockWars] " + msg));
    }
}
