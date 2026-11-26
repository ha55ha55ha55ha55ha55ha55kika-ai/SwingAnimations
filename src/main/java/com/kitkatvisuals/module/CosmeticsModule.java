package com.kitkatvisuals.module;

import com.kitkatvisuals.cosmetics.CosmeticMesh;
import com.kitkatvisuals.cosmetics.CosmeticModel;
import com.kitkatvisuals.cosmetics.CosmeticState;
import com.kitkatvisuals.gui.WardrobeScreen;
import net.minecraft.client.MinecraftClient;
import com.kitkatvisuals.link.ClientLink;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Cosmetics: косметика на персонаже (крылья, нимб) и гардероб, где можно покрутить скин в 3D, надеть/снять предметы,
 * изменить их размер и положение. Надетая косметика передаётся через ClientLink (HTTP-реле), поэтому её видят все игроки
 * с модом на этом сервере, а вы видите их косметику. Игрокам без мода и серверу ничего не отправляется.
 */
public final class CosmeticsModule extends Module {
    /** Перевод пикселей модели в блоки: игрок рисуется с масштабом 0.9375, 1 пиксель = 1/16 блока. */
    private static final float K = 0.9375f / 16f;

    private final Setting.Bool openWardrobe = add(new Setting.Bool("wardrobe", "Open wardrobe", false));
    // «id=надет,размер,x,y,z;...»: правится в гардеробе, в ClickGUI не показывается
    private final Setting.Bool shareMine = add(new Setting.Bool("share", "Show my cosmetics to other mod users", true));
    private final Setting.Bool showOthers = add(new Setting.Bool("show_others", "See other players' cosmetics", true));
    private final Setting.Str stateStr = add(new Setting.Str("state", "Wardrobe state", ""));

    private final Map<String, CosmeticState> states = new HashMap<>();
    private String parsed;
    /** Разобранная косметика других игроков (по UUID); пересобирается, когда строка у игрока изменилась. */
    private record Cached(String raw, Map<String, CosmeticState> map) { }
    private final Map<UUID, Cached> others = new HashMap<>();
    private static final int MAX_OTHERS = 20;
    private static final double OTHERS_RANGE = 64.0;

    public CosmeticsModule() {
        super("cosmetics", "Cosmetics", "player");
    }

    // ---------------- состояние гардероба ----------------

    /** Разбор строки «id=надет,размер,x,y,z;...». Неизвестные предметы и битые записи пропускаются, числа ограничиваются. */
    private static Map<String, CosmeticState> parseState(String v) {
        Map<String, CosmeticState> out = new HashMap<>();
        if (v == null || v.isEmpty() || v.length() > 1000) return out;
        int n = 0;
        for (String part : v.split(";")) {
            if (++n > 16) break;
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
                out.put(id, s);
            } catch (NumberFormatException ignored) {
                // битая запись: предмет вернётся к значениям по умолчанию
            }
        }
        return out;
    }

    private void parseIfChanged() {
        String v = stateStr.value == null ? "" : stateStr.value;
        if (Objects.equals(v, parsed)) return;
        parsed = v;
        states.clear();
        states.putAll(parseState(v));
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

    /**
     * Что отправляется другим игрокам с модом: только надетые предметы («id=1,размер,x,y,z;...»).
     * Пустая строка, если модуль выключен или вы отключили «Show my cosmetics to other mod users».
     */
    public String shareString() {
        if (!isEnabled() || !shareMine.value) return "";
        parseIfChanged();
        StringBuilder sb = new StringBuilder();
        for (CosmeticModel m : CosmeticModel.all()) {
            CosmeticState s = states.get(m.id);
            if (s == null || !s.equipped) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(m.id).append("=1")
                    .append(String.format(Locale.ROOT, ",%.3f,%.2f,%.2f,%.2f", s.scale, s.ox, s.oy, s.oz));
        }
        return sb.toString();
    }

    /** Включено ли «See other players' cosmetics» (для диагностики /kk debug). */
    public boolean showsOthers() { return showOthers.value; }

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
        if (!others.isEmpty() && mc.player != null && mc.player.age % 100 == 0) {
            others.keySet().removeIf(id -> ClientLink.peer(id) == null); // игрок ушёл или перестал присылать
        }
    }

    // ---------------- отрисовка на персонажах ----------------

    private boolean anyEquipped() {
        for (CosmeticModel m : CosmeticModel.all()) if (state(m).equipped) return true;
        return false;
    }

    @Override
    public boolean wantsWorldRender() { return anyEquipped() || (showOthers.value && ClientLink.anyCosmetics()); }

    @Override
    public void render(RenderContext c) {
        MinecraftClient mc = c.mc;
        ClientPlayerEntity self = mc.player;
        if (self == null || mc.world == null) return;

        // вы: только не от первого лица
        if (!mc.options.getPerspective().isFirstPerson()) {
            parseIfChanged();
            drawFor(c, self, states);
        }

        // другие игроки с модом: ближайшие, в пределах дальности
        if (!showOthers.value || !ClientLink.anyCosmetics()) return;
        List<PlayerEntity> near = new ArrayList<>();
        double r2 = OTHERS_RANGE * OTHERS_RANGE;
        for (PlayerEntity o : mc.world.getPlayers()) {
            if (o == self || o.isInvisible() || o.isSpectator()) continue;
            if (ClientLink.cosmeticsOf(o.getUuid()).isEmpty()) continue;
            if (self.squaredDistanceTo(o) > r2) continue;
            near.add(o);
        }
        near.sort((a, b) -> Double.compare(self.squaredDistanceTo(a), self.squaredDistanceTo(b)));
        int n = Math.min(near.size(), MAX_OTHERS);
        for (int i = 0; i < n; i++) {
            PlayerEntity o = near.get(i);
            String raw = ClientLink.cosmeticsOf(o.getUuid());
            Cached cached = others.get(o.getUuid());
            if (cached == null || !cached.raw().equals(raw)) {
                cached = new Cached(raw, parseState(raw));
                others.put(o.getUuid(), cached);
            }
            drawFor(c, o, cached.map());
        }
    }

    /** Рисует надетые предметы на игроке p по набору состояний st. */
    private void drawFor(RenderContext c, PlayerEntity p, Map<String, CosmeticState> st) {
        if (p.isInvisible() || p.isSpectator()) return;
        boolean any = false;
        for (CosmeticState s : st.values()) if (s.equipped) { any = true; break; }
        if (!any) return;
        switch (p.getPose().name()) {
            case "SWIMMING", "GLIDING", "FALL_FLYING", "SLEEPING", "DYING", "SPIN_ATTACK" -> { return; }
            default -> { }
        }
        MinecraftClient mc = c.mc;
        double x = MathHelper.lerp(c.tickDelta, p.lastRenderX, p.getX());
        double y = MathHelper.lerp(c.tickDelta, p.lastRenderY, p.getY());
        double z = MathHelper.lerp(c.tickDelta, p.lastRenderZ, p.getZ());
        float yaw = MathHelper.lerpAngleDegrees(c.tickDelta, p.prevBodyYaw, p.bodyYaw);
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
            CosmeticState s = st.get(cm.id);
            if (s == null || !s.equipped) continue;
            float[] q = cm.quads;
            if (cm.animated()) {                                    // у каждого игрока своя фаза, чтобы крылья не хлопали синхронно
                float phase = ((p.getUuid().hashCode() & 0x7fffffff) % 1000) / 1000f * cm.animLen;
                q = cm.pose((mc.world.getTime() + c.tickDelta) / 20f + phase);
            }
            RenderLayer main = RenderLayer.getEntityCutoutNoCull(cm.texture);
            VertexConsumer vc = vcp.getBuffer(main);
            CosmeticMesh.emit(vc, m, q, cm.ax, cm.ay, cm.az, s.scale, s.ox, s.oy, s.oz, light, false, false);
            vcp.draw(main);
            if (cm.glow != null) {
                RenderLayer glow = RenderLayer.getEyes(cm.glow);
                CosmeticMesh.emit(vcp.getBuffer(glow), m, q, cm.ax, cm.ay, cm.az, s.scale, s.ox, s.oy, s.oz, 0xF000F0, false, false);
                vcp.draw(glow);
            }
        }
    }
}
