package com.kitkatvisuals.module;

import com.kitkatvisuals.cosmetics.CosmeticMesh;
import com.kitkatvisuals.cosmetics.CosmeticModel;
import com.kitkatvisuals.cosmetics.CosmeticState;
import com.kitkatvisuals.gui.WardrobeScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Cosmetics: косметика на вашем персонаже (крылья, нимб) и гардероб, где можно покрутить скин в 3D, надеть/снять предметы,
 * изменить их размер и положение. Косметика видна только вам (клиентская): серверу и другим игрокам ничего не отправляется.
 */
public final class CosmeticsModule extends Module {
    /** Перевод пикселей модели в блоки: игрок рисуется с масштабом 0.9375, 1 пиксель = 1/16 блока. */
    private static final float K = 0.9375f / 16f;

    private final Setting.Bool openWardrobe = add(new Setting.Bool("wardrobe", "Open wardrobe", false));
    // «id=надет,размер,x,y,z;...»: правится в гардеробе, в ClickGUI не показывается
    private final Setting.Str stateStr = add(new Setting.Str("state", "Wardrobe state", ""));

    private final Map<String, CosmeticState> states = new HashMap<>();
    private String parsed;

    public CosmeticsModule() {
        super("cosmetics", "Cosmetics", "player");
    }

    // ---------------- состояние гардероба ----------------

    private void parseIfChanged() {
        String v = stateStr.value == null ? "" : stateStr.value;
        if (Objects.equals(v, parsed)) return;
        parsed = v;
        states.clear();
        for (String part : v.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String id = part.substring(0, eq);
            String[] f = part.substring(eq + 1).split(",");
            CosmeticModel m = CosmeticModel.byId(id);
            if (m == null || f.length < 5) continue;
            try {
                CosmeticState s = new CosmeticState();
                s.equipped = f[0].equals("1");
                s.scale = MathHelper.clamp(Float.parseFloat(f[1]), CosmeticState.SCALE_MIN, CosmeticState.SCALE_MAX);
                s.ox = MathHelper.clamp(Float.parseFloat(f[2]), CosmeticState.POS_MIN, CosmeticState.POS_MAX);
                s.oy = MathHelper.clamp(Float.parseFloat(f[3]), CosmeticState.POS_MIN, CosmeticState.POS_MAX);
                s.oz = MathHelper.clamp(Float.parseFloat(f[4]), CosmeticState.POS_MIN, CosmeticState.POS_MAX);
                states.put(id, s);
            } catch (NumberFormatException ignored) {
                // битая запись: предмет вернётся к значениям по умолчанию
            }
        }
    }

    public CosmeticState state(CosmeticModel m) {
        parseIfChanged();
        return states.computeIfAbsent(m.id, k -> CosmeticState.defaults(m));
    }

    /** Сохраняет состояние гардероба в настройки модуля. */
    public void save() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, CosmeticState> e : states.entrySet()) {
            CosmeticState s = e.getValue();
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(s.equipped ? 1 : 0)
                    .append(String.format(Locale.ROOT, ",%.3f,%.2f,%.2f,%.2f", s.scale, s.ox, s.oy, s.oz));
        }
        stateStr.value = sb.toString();
        parsed = stateStr.value;
        ModuleManager.save();
    }

    public void openWardrobe() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!isEnabled()) setEnabled(true);
        if (mc.currentScreen instanceof WardrobeScreen) return;
        mc.setScreen(new WardrobeScreen(mc.currentScreen, this));
    }

    @Override
    public void tick(MinecraftClient mc) {
        if (openWardrobe.value) {
            openWardrobe.value = false;
            openWardrobe();
        }
    }

    // ---------------- отрисовка на персонаже ----------------

    private boolean anyEquipped() {
        for (CosmeticModel m : CosmeticModel.all()) if (state(m).equipped) return true;
        return false;
    }

    @Override
    public boolean wantsWorldRender() { return anyEquipped(); }

    @Override
    public void render(RenderContext c) {
        MinecraftClient mc = c.mc;
        ClientPlayerEntity p = mc.player;
        if (p == null || p.isInvisible() || p.isSpectator() || mc.options.getPerspective().isFirstPerson()) return;
        switch (p.getPose().name()) {
            case "SWIMMING", "GLIDING", "FALL_FLYING", "SLEEPING", "DYING", "SPIN_ATTACK" -> { return; }
            default -> { }
        }
        double x = MathHelper.lerp(c.tickDelta, p.lastRenderX, p.getX());
        double y = MathHelper.lerp(c.tickDelta, p.lastRenderY, p.getY());
        double z = MathHelper.lerp(c.tickDelta, p.lastRenderZ, p.getZ());
        float yaw = MathHelper.lerpAngleDegrees(c.tickDelta, p.lastBodyYaw, p.bodyYaw);
        int light = mc.getEntityRenderDispatcher().getLight(p, c.tickDelta);

        // пространство модели (пиксели, Y вверх, перед = -Z) -> мир. Игрок смотрит в +Z при нулевом повороте тела.
        Matrix4f m = new Matrix4f(c.m).translate((float) x, (float) y, (float) z).rotateY((float) Math.toRadians(-yaw));
        if (p.isSneaking()) {
            // как у ванильного тела при приседании: опускается на 3.2 пикселя и наклоняется на 0.5 рад вокруг шеи
            m.translate(0f, -3.2f * K, 0f).translate(0f, 24f * K, 0f).rotateX(0.5f).translate(0f, -24f * K, 0f);
        }
        m.scale(K).rotateY((float) Math.PI).translate(-8f, 0f, -8f);

        VertexConsumerProvider.Immediate vcp = mc.getBufferBuilders().getEntityVertexConsumers();
        for (CosmeticModel cm : CosmeticModel.all()) {
            CosmeticState s = state(cm);
            if (!s.equipped) continue;
            RenderLayer main = RenderLayer.getEntityCutoutNoCull(cm.texture);
            VertexConsumer vc = vcp.getBuffer(main);
            CosmeticMesh.emit(vc, m, cm.quads, cm.ax, cm.ay, cm.az, s.scale, s.ox, s.oy, s.oz, light, false, false);
            vcp.draw(main);
            if (cm.glow != null) {
                RenderLayer glow = RenderLayer.getEyes(cm.glow);
                CosmeticMesh.emit(vcp.getBuffer(glow), m, cm.quads, cm.ax, cm.ay, cm.az, s.scale, s.ox, s.oy, s.oz, 0xF000F0, false, false);
                vcp.draw(glow);
            }
        }
    }
}
