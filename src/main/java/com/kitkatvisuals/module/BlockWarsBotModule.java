package com.kitkatvisuals.module;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * BlockWars-бот: читает цвет из title / subtitle / actionbar, находит ближайшую шерсть этого цвета,
 * бежит к ней (спринт + прыжки), на нужном блоке приседает (Shift). Если не успевает, в воздухе делает
 * до 2 «двойных прыжков» за раунд. Раунды ускоряются сами: бот реагирует на каждое новое сообщение.
 */
public final class BlockWarsBotModule extends Module {
    private final Setting.Num range = add(new Setting.Num("range", "Search range", 24f, 8f, 48f, 1f));
    private final Setting.Num reaction = add(new Setting.Num("reaction", "Reaction delay (ticks)", 0f, 0f, 10f, 1f));
    private final Setting.Num turnSpeed = add(new Setting.Num("turn_speed", "Turn speed (deg/tick)", 180f, 10f, 180f, 5f));
    private final Setting.Bool doubleJump = add(new Setting.Bool("double_jump", "Double jump (max 2)", true));
    private final Setting.Bool sneakOnTarget = add(new Setting.Bool("sneak", "Sneak on target", true));

    // ---- приём текста из хуков (клиентский поток) ----
    private static volatile String lastText = "";
    private static volatile int textVersion = 0;

    public static void feed(Text t) {
        if (t == null) return;
        String s = t.getString();
        if (s == null || s.isBlank()) return;
        lastText = s;
        textVersion++;
    }

    // ---- цвета: слова (RU / EN) -> DyeColor. Берётся самое длинное совпадение ----
    private static final Map<String, DyeColor> NAMES = new LinkedHashMap<>();
    private static void n(DyeColor c, String... keys) { for (String k : keys) NAMES.put(k, c); }
    static {
        n(DyeColor.WHITE, "white", "бел");
        n(DyeColor.ORANGE, "orange", "оранж");
        n(DyeColor.MAGENTA, "magenta", "маджент", "малинов", "пурпурн");
        n(DyeColor.LIGHT_BLUE, "light blue", "light_blue", "lightblue", "голуб", "светло-син", "светло син");
        n(DyeColor.YELLOW, "yellow", "желт", "жёлт");
        n(DyeColor.LIME, "lime", "лайм", "салатов", "светло-зел", "светло зел");
        n(DyeColor.PINK, "pink", "розов");
        n(DyeColor.GRAY, "gray", "grey", "сер", "тёмно-сер", "темно-сер");
        n(DyeColor.LIGHT_GRAY, "light gray", "light grey", "light_gray", "светло-сер", "светло сер");
        n(DyeColor.CYAN, "cyan", "бирюз", "циан");
        n(DyeColor.PURPLE, "purple", "фиолет");
        n(DyeColor.BLUE, "blue", "син");
        n(DyeColor.BROWN, "brown", "коричн");
        n(DyeColor.GREEN, "green", "зелен", "зелён");
        n(DyeColor.RED, "red", "красн");
        n(DyeColor.BLACK, "black", "чёрн", "черн");
    }

    static DyeColor parse(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).replace('ё', 'е');
        DyeColor best = null;
        int bestLen = 0;
        for (Map.Entry<String, DyeColor> e : NAMES.entrySet()) {
            String k = e.getKey().replace('ё', 'е');
            if (k.length() > bestLen && s.contains(k)) { best = e.getValue(); bestLen = k.length(); }
        }
        return best;
    }

    private static Block wool(DyeColor c) {
        return switch (c) {
            case WHITE -> Blocks.WHITE_WOOL; case ORANGE -> Blocks.ORANGE_WOOL;
            case MAGENTA -> Blocks.MAGENTA_WOOL; case LIGHT_BLUE -> Blocks.LIGHT_BLUE_WOOL;
            case YELLOW -> Blocks.YELLOW_WOOL; case LIME -> Blocks.LIME_WOOL;
            case PINK -> Blocks.PINK_WOOL; case GRAY -> Blocks.GRAY_WOOL;
            case LIGHT_GRAY -> Blocks.LIGHT_GRAY_WOOL; case CYAN -> Blocks.CYAN_WOOL;
            case PURPLE -> Blocks.PURPLE_WOOL; case BLUE -> Blocks.BLUE_WOOL;
            case BROWN -> Blocks.BROWN_WOOL; case GREEN -> Blocks.GREEN_WOOL;
            case RED -> Blocks.RED_WOOL; case BLACK -> Blocks.BLACK_WOOL;
        };
    }

    // ---- состояние раунда ----
    private int seenVersion;
    private Block targetBlock;
    private BlockPos targetPos;
    private int delayLeft;
    private int rescanIn;
    private int airTicks;
    private int djUsed;
    private int djCooldown;

    public BlockWarsBotModule() {
        super("block_wars_bot", "BlockWarsBot", "movement");
    }

    @Override public boolean restoreOnLoad() { return false; }

    @Override
    public void onEnable() {
        seenVersion = textVersion; // старые сообщения игнорируем
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
        airTicks = 0;
        djUsed = 0;
        djCooldown = 0;
        delayLeft = (int) reaction.value;
    }

    @Override
    public void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return;
        if (mc.currentScreen != null) { release(mc); return; }

        // новый раунд?
        if (textVersion != seenVersion) {
            seenVersion = textVersion;
            DyeColor c = parse(lastText);
            if (c != null) {
                targetBlock = wool(c);
                resetRound();
            }
        }
        if (targetBlock == null) { release(mc); return; }
        if (delayLeft > 0) { delayLeft--; release(mc); return; }

        World w = mc.world;
        boolean onGround = p.isOnGround();
        if (onGround) airTicks = 0; else airTicks++;
        if (djCooldown > 0) djCooldown--;

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
        Vec3d to = new Vec3d(targetPos.getX() + 0.5, 0, targetPos.getZ() + 0.5);
        double dx = to.x - p.getX(), dz = to.z - p.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        float wantYaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
        float diff = MathHelper.wrapDegrees(wantYaw - p.getYaw());
        float step = turnSpeed.value;
        p.setYaw(p.getYaw() + MathHelper.clamp(diff, -step, step));

        boolean close = dist < 1.1; // у самой цели: без спринта, чтобы не перелететь за край
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(!close);

        // прыжки: бег-прыжок с земли, пока далеко; двойной прыжок в воздухе, если не долетаем
        boolean jump = false;
        if (onGround) {
            jump = dist > 2.5 && !close;
        } else if (doubleJump.value && djUsed < 2 && djCooldown == 0
                && airTicks >= 3 && p.getVelocity().y <= 0.05 && dist > 2.0) {
            jump = true;
            djUsed++;
            djCooldown = 4; // между нажатиями клавиша обязательно отпускается
        }
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
                    BlockState s = w.getBlockState(m);
                    if (!s.isOf(targetBlock)) continue;
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
}
