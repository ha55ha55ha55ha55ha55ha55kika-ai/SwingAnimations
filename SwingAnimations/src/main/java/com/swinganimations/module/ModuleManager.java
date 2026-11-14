package com.swinganimations.module;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.swinganimations.SwingAnimationsClient;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.ActionResult;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ModuleManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final List<Module> MODULES = new ArrayList<>();
    private static TargetEspModule targetEsp;
    private static HeartShowModule heartShow;
    public static SeeInvisiblesModule seeInvisibles;
    private static boolean renderFailed;

    private ModuleManager() {}

    /** Сохранённое состояние одного модуля. */
    private static final class Saved {
        boolean enabled;
        int bind = -1;
        boolean mouse;
        Map<String, String> values = new LinkedHashMap<>();
    }

    public static void init() {
        targetEsp = new TargetEspModule();
        MODULES.add(targetEsp);
        MODULES.add(new TrailsModule());
        MODULES.add(new JumpCircleModule());
        heartShow = new HeartShowModule();
        MODULES.add(heartShow);
        seeInvisibles = new SeeInvisiblesModule();
        MODULES.add(seeInvisibles);
        MODULES.add(new InterfaceModule());
        MODULES.add(new AutoSprintModule());
        MODULES.add(new SwingModule());
        load();
        ClientTickEvents.END_CLIENT_TICK.register(ModuleManager::tick);
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> renderHud(ctx, tickCounter.getTickDelta(false)));
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            try {
                if (world.isClient) {
                    targetEsp.onAttack(entity);
                    heartShow.onAttack(entity);
                }
            } catch (Throwable t) {
                SwingAnimationsClient.LOGGER.warn("TargetESP: {}", t.toString());
            }
            return ActionResult.PASS;
        });
    }

    private static void tick(MinecraftClient mc) {
        boolean noScreen = mc.currentScreen == null;
        long handle = mc.getWindow() != null ? mc.getWindow().getHandle() : 0L;
        for (Module m : MODULES) {
            if (m.bindCode >= 0 && handle != 0L) {
                boolean down = m.bindMouse
                        ? GLFW.glfwGetMouseButton(handle, m.bindCode) == GLFW.GLFW_PRESS
                        : InputUtil.isKeyPressed(handle, m.bindCode);
                if (down && !m.bindDown && noScreen) {
                    m.toggle();
                    save();
                }
                m.bindDown = down;
            }
            if (mc.player != null && mc.world != null && m.isEnabled()) {
                try {
                    m.tick(mc);
                } catch (Throwable t) {
                    SwingAnimationsClient.LOGGER.warn("{}: tick error {}", m.id, t.toString());
                }
            }
        }
    }

    private static void renderHud(net.minecraft.client.gui.DrawContext ctx, float tickDelta) {
        for (Module m : MODULES) {
            if (!m.isEnabled()) continue;
            try {
                m.renderHud(ctx, tickDelta);
            } catch (Throwable t) {
                SwingAnimationsClient.LOGGER.warn("{}: ошибка HUD {}", m.id, t.toString());
            }
        }
    }

    /** Вызывается из WorldRendererMixin в конце рендера мира. */
    public static void renderWorld(Matrix4f positionMatrix, Camera camera, float tickDelta) {
        if (renderFailed) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        boolean any = false;
        for (Module m : MODULES) if (m.isEnabled() && !"swing".equals(m.id)) { any = true; break; }
        if (!any) return;
        try {
            MatrixStack ms = new MatrixStack();
            ms.multiplyPositionMatrix(positionMatrix);
            ms.translate(-camera.getPos().x, -camera.getPos().y, -camera.getPos().z);
            RenderContext ctx = new RenderContext(ms, camera, tickDelta, System.currentTimeMillis(), mc);
            for (Module m : MODULES) {
                if (m.isEnabled()) m.render(ctx);
            }
        } catch (Throwable t) {
            renderFailed = true;
            SwingAnimationsClient.LOGGER.error("Ошибка мирового рендера, визуалы отключены до перезапуска", t);
        }
    }

    // ---------------- сохранение ----------------

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("swinganimations_modules.json");
    }

    public static void save() {
        try {
            Map<String, Saved> out = new LinkedHashMap<>();
            for (Module m : MODULES) {
                Saved s = new Saved();
                s.enabled = m.isEnabled();
                s.bind = m.bindCode;
                s.mouse = m.bindMouse;
                for (Setting st : m.settings) s.values.put(st.id, st.serialize());
                out.put(m.id, s);
            }
            Path p = path();
            Path tmp = p.resolveSibling("swinganimations_modules.json.tmp");
            Files.createDirectories(p.getParent());
            Files.writeString(tmp, GSON.toJson(out), StandardCharsets.UTF_8);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            SwingAnimationsClient.LOGGER.warn("Не удалось сохранить модули: {}", e.toString());
        }
    }

    private static void load() {
        Path p = path();
        if (!Files.exists(p)) return;
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            Type type = new TypeToken<Map<String, Saved>>() { }.getType();
            Map<String, Saved> in = GSON.fromJson(r, type);
            if (in == null) return;
            for (Module m : MODULES) {
                Saved s = in.get(m.id);
                if (s == null) continue;
                m.bindCode = s.bind;
                m.bindMouse = s.mouse;
                if (s.values != null) {
                    for (Setting st : m.settings) {
                        String v = s.values.get(st.id);
                        if (v != null) st.deserialize(v);
                    }
                }
                if (!"swing".equals(m.id) && s.enabled) m.setEnabled(true);
            }
        } catch (Exception e) {
            SwingAnimationsClient.LOGGER.warn("Повреждён swinganimations_modules.json ({}), использую значения по умолчанию", e.toString());
            try {
                Files.move(p, p.resolveSibling("swinganimations_modules.json.broken"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // не критично
            }
        }
    }
}
