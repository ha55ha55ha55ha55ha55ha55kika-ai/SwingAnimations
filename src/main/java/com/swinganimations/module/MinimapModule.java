package com.swinganimations.module;

import com.mojang.blaze3d.systems.RenderSystem;
import com.swinganimations.gui.Hud;
import com.swinganimations.gui.HudLayout;
import com.swinganimations.gui.Ui;
import com.swinganimations.link.ClientLink;
import com.swinganimations.render.Batch;
import com.swinganimations.util.Perf;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.world.BiomeColors;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.entity.vehicle.ChestBoatEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimap: собственная мини-карта (код Xaero's Minimap, VoxelMap и JourneyMap закрыт, встроить его нельзя).
 * Карта рисуется из загруженных чанков (цвета блоков как на картах Minecraft, оттенки биомов, затенение по рельефу,
 * глубина воды), вращается вместе с вами или стоит севером вверх, круг или квадрат. Поверх: точки игроков
 * (друзья зелёные, пользователи клиента цвета темы, остальные красные), мобы, предметы и метки (ивенты, смерть).
 * Только клиент: используются данные, которые игра уже получила; никакого «рентгена» сквозь незагруженный мир или невидимок.
 */
public final class MinimapModule extends Module {
    private static final Identifier TEX_ID = Identifier.of("swinganimations", "minimap_dynamic");
    private static final int T = 128;
    private static final int UNKNOWN = 0x0B0E13;
    private static final int[] DOT_PLAYER = {0xFF4A4A};
    private static final int DOT_FRIEND = 0x33FF55;
    private static final int DOT_HOSTILE = 0xFF9A1F;
    private static final int DOT_PASSIVE = 0x9FD79A;
    private static final int DOT_ITEM = 0xFFE066;

    private final Setting.Mode shape = add(new Setting.Mode("shape", "Shape", "Circle", "Square"));
    private final Setting.Num size = add(new Setting.Num("size", "Size", 110f, 70f, 200f, 5f));
    private final Setting.Num range = add(new Setting.Num("range", "Range (blocks)", 48f, 16f, 160f, 8f));
    private final Setting.Bool rotate = add(new Setting.Bool("rotate", "Rotate with player", true));
    private final Setting.Num opacity = add(new Setting.Num("opacity", "Opacity", 0.95f, 0.4f, 1f, 0.05f));
    private final Setting.Bool smooth = add(new Setting.Bool("smooth", "Smooth map", true));
    private final Setting.Bool biomeColors = add(new Setting.Bool("biome_colors", "Biome colors", true));
    private final Setting.Bool shading = add(new Setting.Bool("shading", "Relief shading", true));
    private final Setting.Bool players = add(new Setting.Bool("players", "Players", true));
    private final Setting.Bool hostile = add(new Setting.Bool("hostile", "Hostile mobs", true));
    private final Setting.Bool passive = add(new Setting.Bool("passive", "Passive mobs", false));
    private final Setting.Bool items = add(new Setting.Bool("items", "Items", false));
    private final Setting.Mode playersFilter = add(new Setting.Mode("players_filter", "Players shown", "All", "Friends only", "Enemies only"));
    private final Setting.Bool chestCart = add(new Setting.Bool("chest_minecart", "Minecart with chest", true));
    private final Setting.Bool hopperCart = add(new Setting.Bool("hopper_minecart", "Minecart with hopper", false));
    private final Setting.Bool tntCart = add(new Setting.Bool("tnt_minecart", "Minecart with TNT", false));
    private final Setting.Bool otherCart = add(new Setting.Bool("other_minecart", "Other minecarts", false));
    private final Setting.Bool chestBoat = add(new Setting.Bool("chest_boat", "Boat with chest", true));
    private final Setting.Bool primedTnt = add(new Setting.Bool("primed_tnt", "Lit TNT", false));
    private final Setting.Bool highlightCarts = add(new Setting.Bool("highlight_carts", "Highlight chest carts and boats", true));
    private final Setting.Bool cartEdge = add(new Setting.Bool("cart_edge", "Chest carts beyond range at the edge", true));
    private final Setting.Bool cartDistance = add(new Setting.Bool("cart_distance", "Distance to chest carts and boats", true));
    private final Setting.Num iconScale = add(new Setting.Num("icon_scale", "Icon scale", 1.25f, 1f, 2f, 0.25f));
    private final Setting.Bool waypoints = add(new Setting.Bool("waypoints", "Markers (events, death)", true));
    private final Setting.Bool compass = add(new Setting.Bool("compass", "Compass letters", true));
    private final Setting.Bool coords = add(new Setting.Bool("coords", "Coordinates", true));
    private final Setting.Bool biomeName = add(new Setting.Bool("biome_name", "Biome name", true));

    private final HudLayout.Win win = HudLayout.win("minimap");
    private NativeImageBackedTexture tex;
    private NativeImage img;
    private final int[] rgb = new int[T * T];
    private final int[] topY = new int[T * T];
    private final boolean[] water = new boolean[T * T];
    private int centerX, centerZ;
    private float texPPB = 1f;
    private float builtRange = -1f;
    private boolean builtSmooth;
    private int ticks;
    private double lastBuildX = Double.NaN, lastBuildZ = Double.NaN;
    private String biome = "";

    public MinimapModule() {
        super("minimap", "Minimap", "hud");
    }

    @Override
    public void onDisable() {
        freeTexture();
    }

    /** Приблизить/отдалить (delta в блоках радиуса; отрицательное = ближе). */
    public void zoom(float delta) {
        range.set(MathHelper.clamp(range.value + delta, 16f, 160f));
        ModuleManager.save();
    }

    private void freeTexture() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (tex != null) {
            mc.getTextureManager().destroyTexture(TEX_ID);
            tex = null;
            img = null;
        }
        builtRange = -1f;
    }

    private void ensureTexture(MinecraftClient mc) {
        if (tex != null && builtSmooth == smooth.value) return;
        if (tex == null) {
            img = new NativeImage(NativeImage.Format.RGBA, T, T, true);
            tex = new NativeImageBackedTexture(img);
            mc.getTextureManager().registerTexture(TEX_ID, tex);
        }
        tex.setFilter(smooth.value, false);
        builtSmooth = smooth.value;
    }

    // ---------------- обновление текстуры ----------------

    @Override
    public void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return;
        int every = Perf.quality() == 0 ? 4 : 8;
        // В Нижнем мире на каждый пиксель до 48 шагов вниз по блокам: строим вдвое реже.
        if (mc.world.getDimension().hasCeiling()) every *= 2;
        // Стоим на месте: карта почти не меняется (только подгружаются чанки), хватает раза в 1,5 с.
        double mdx = p.getX() - lastBuildX, mdz = p.getZ() - lastBuildZ;
        if (!(mdx * mdx + mdz * mdz > 0.25)) every = Math.max(every, 30);
        if (++ticks >= every || Math.abs(range.value - builtRange) > 0.01f || tex == null) {
            ticks = 0;
            rebuild(mc);
        }
        if (biomeName.value && p.age % 20 == 0) {
            var key = mc.world.getBiome(p.getBlockPos()).getKey();
            biome = key.map(k -> Text.translatable(k.getValue().toTranslationKey("biome")).getString()).orElse("");
        }
    }

    private void rebuild(MinecraftClient mc) {
        ClientWorld w = mc.world;
        ClientPlayerEntity p = mc.player;
        if (w == null || p == null) return;
        ensureTexture(mc);
        float r = range.value;
        float halfExtent = r * 1.45f + 4f;
        texPPB = (T / 2f) / halfExtent;
        builtRange = r;
        lastBuildX = p.getX();
        lastBuildZ = p.getZ();
        centerX = MathHelper.floor(p.getX());
        centerZ = MathHelper.floor(p.getZ());
        boolean ceiling = w.getDimension().hasCeiling();
        int py = MathHelper.floor(p.getY());
        int bottom = w.getBottomY();
        int half = T / 2;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        BlockColors colors = mc.getBlockColors();
        boolean bio = biomeColors.value;
        Chunk chunk = null;
        int lcx = Integer.MIN_VALUE, lcz = Integer.MIN_VALUE;

        for (int j = 0; j < T; j++) {
            int wz = centerZ + MathHelper.floor((j - half + 0.5f) / texPPB);
            int cz = wz >> 4;
            int prevX = Integer.MIN_VALUE;
            for (int i = 0; i < T; i++) {
                int idx = j * T + i;
                int wx = centerX + MathHelper.floor((i - half + 0.5f) / texPPB);
                if (i > 0 && wx == prevX) { // тот же блок, что и у соседнего пикселя
                    rgb[idx] = rgb[idx - 1];
                    topY[idx] = topY[idx - 1];
                    water[idx] = water[idx - 1];
                    continue;
                }
                prevX = wx;
                int cx = wx >> 4;
                if (cx != lcx || cz != lcz) {
                    chunk = w.getChunk(cx, cz, ChunkStatus.FULL, false);
                    lcx = cx;
                    lcz = cz;
                }
                if (chunk == null) {
                    rgb[idx] = UNKNOWN;
                    topY[idx] = Integer.MIN_VALUE;
                    water[idx] = false;
                    continue;
                }
                int y = ceiling ? py + 1 : chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, wx & 15, wz & 15);
                BlockState st = null;
                MapColor mcol = null;
                for (int k = 0; k < 48 && y >= bottom; k++, y--) {
                    pos.set(wx, y, wz);
                    BlockState s = chunk.getBlockState(pos);
                    if (s.isAir()) continue;
                    MapColor mc2 = s.getMapColor(w, pos);
                    if (mc2 == MapColor.CLEAR) continue;
                    st = s;
                    mcol = mc2;
                    break;
                }
                if (st == null) {
                    rgb[idx] = UNKNOWN;
                    topY[idx] = bottom;
                    water[idx] = false;
                    continue;
                }
                topY[idx] = y;
                if (st.getFluidState().isIn(FluidTags.WATER)) {
                    int depth = 0;
                    int yy = y;
                    while (depth < 16 && yy > bottom) {
                        pos.set(wx, yy, wz);
                        if (!chunk.getBlockState(pos).getFluidState().isIn(FluidTags.WATER)) break;
                        depth++;
                        yy--;
                    }
                    pos.set(wx, y, wz);
                    int base = bio ? (BiomeColors.getWaterColor(w, pos) & 0xFFFFFF) : 0x3F76E4;
                    rgb[idx] = scale(base, 1f - 0.5f * Math.min(depth, 14) / 14f);
                    water[idx] = true;
                } else {
                    pos.set(wx, y, wz);
                    int c = mcol.color;
                    if (bio) {
                        int tint = colors.getColor(st, w, pos, 0);
                        if (tint != -1) c = tint & 0xFFFFFF;
                    }
                    rgb[idx] = c & 0xFFFFFF;
                    water[idx] = false;
                }
            }
        }

        // затенение по рельефу: сравниваем высоту с соседом к северу (как на картах Minecraft)
        boolean shade = shading.value;
        for (int j = 0; j < T; j++) {
            for (int i = 0; i < T; i++) {
                int idx = j * T + i;
                int c = rgb[idx];
                if (shade && topY[idx] != Integer.MIN_VALUE && !water[idx] && j > 0 && topY[idx - T] != Integer.MIN_VALUE) {
                    int d = MathHelper.clamp(topY[idx] - topY[idx - T], -4, 4);
                    c = scale(c, 1f + d * 0.08f);
                }
                img.setColorArgb(i, j, 0xFF000000 | c);
            }
        }
        tex.upload();
    }

    private static int scale(int rgb, float f) {
        int r = MathHelper.clamp((int) (((rgb >> 16) & 255) * f), 0, 255);
        int g = MathHelper.clamp((int) (((rgb >> 8) & 255) * f), 0, 255);
        int b = MathHelper.clamp((int) ((rgb & 255) * f), 0, 255);
        return (r << 16) | (g << 8) | b;
    }

    // ---------------- отрисовка ----------------

    @Override
    public void renderHud(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.options.hudHidden || tex == null) return;

        boolean circle = shape.is("Circle");
        int sz = (int) size.value;
        float r = sz / 2f;
        int infoH = (coords.value ? 13 : 0) + (biomeName.value && !biome.isEmpty() ? 12 : 0);
        int x = ctx.getScaledWindowWidth() - sz - 8;
        int y = 8;
        float cx = x + r;
        float cy = y + r;
        float scale = r / range.value;
        boolean rot = rotate.value;
        float yawRad = (float) Math.toRadians(p.getYaw(tickDelta));
        float c = (float) Math.cos(yawRad);
        float s = (float) Math.sin(yawRad);
        var pp = p.getLerpedPos(tickDelta);

        HudLayout.push(ctx, win);
        ctx.draw(); // всё, что уже нарисовано через DrawContext, должно лечь под карту
        Matrix4f m = ctx.getMatrices().peek().getPositionMatrix();
        drawMap(m, cx, cy, r, circle, opacity.value, rot, c, s, pp.x, pp.z, scale);
        drawBorder(ctx, m, x, y, sz, cx, cy, r, circle);

        // метки
        if (waypoints.value) {
            for (Module mod : ModuleManager.MODULES) {
                if (!(mod instanceof WaypointSource src) || !mod.isEnabled()) continue;
                for (WaypointSource.Waypoint wp : src.waypoints()) {
                    drawWaypoint(ctx, wp, pp.x, pp.z, cx, cy, r, circle, rot, c, s, scale);
                }
            }
        }
        // сущности
        drawEntities(ctx, mc, p, tickDelta, pp.x, pp.z, cx, cy, r, circle, rot, c, s, scale);
        // компас
        if (compass.value) drawCompass(ctx, cx, cy, r, circle, rot, c, s);
        // стрелка игрока
        drawArrow(ctx, m, cx, cy, rot, yawRad);

        int ty = y + sz + 3;
        if (coords.value) {
            String t = p.getBlockX() + "  " + p.getBlockY() + "  " + p.getBlockZ();
            int tw = Hud.w(t) + 14;
            Hud.chip(ctx, Math.round(cx - tw / 2f), ty, tw, 12);
            Hud.text(ctx, t, Math.round(cx - tw / 2f) + 7, ty + 2, Ui.TEXT);
            ty += 13;
        }
        if (biomeName.value && !biome.isEmpty()) {
            Hud.text(ctx, biome, Math.round(cx - Hud.w(biome) / 2f), ty + 1, Ui.TEXT_DIM);
        }
        HudLayout.pop(ctx, win, x, y, sz, sz + infoH);
    }

    /** Карта как один веер треугольников с текстурой; вращение достигается поворотом UV, а не геометрии. */
    private void drawMap(Matrix4f m, float cx, float cy, float r, boolean circle, float alpha, boolean rot,
                         float c, float s, double ppx, double ppz, float scale) {
        RenderSystem.setShader(ShaderProgramKeys.POSITION_TEX_COLOR);
        RenderSystem.setShaderTexture(0, TEX_ID);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        int a = MathHelper.clamp((int) (alpha * 255f), 0, 255);
        BufferBuilder bb = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLE_FAN, VertexFormats.POSITION_TEXTURE_COLOR);
        uv(bb, m, cx, cy, 0f, 0f, scale, rot, c, s, ppx, ppz, a);
        if (circle) {
            int n = 72;
            for (int i = 0; i <= n; i++) {
                double ang = i * 2 * Math.PI / n;
                uv(bb, m, cx, cy, (float) Math.cos(ang) * r, (float) Math.sin(ang) * r, scale, rot, c, s, ppx, ppz, a);
            }
        } else {
            uv(bb, m, cx, cy, -r, -r, scale, rot, c, s, ppx, ppz, a);
            uv(bb, m, cx, cy, r, -r, scale, rot, c, s, ppx, ppz, a);
            uv(bb, m, cx, cy, r, r, scale, rot, c, s, ppx, ppz, a);
            uv(bb, m, cx, cy, -r, r, scale, rot, c, s, ppx, ppz, a);
            uv(bb, m, cx, cy, -r, -r, scale, rot, c, s, ppx, ppz, a);
        }
        BufferRenderer.drawWithGlobalProgram(bb.end());
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    /** Вершина на экране со смещением (ox, oy) от центра и текстурными координатами этого места мира. */
    private void uv(BufferBuilder bb, Matrix4f m, float cx, float cy, float ox, float oy, float scale,
                    boolean rot, float c, float s, double ppx, double ppz, int a) {
        float bx = ox / scale;
        float bz = oy / scale;
        double dx = rot ? -c * bx + s * bz : bx;
        double dz = rot ? -s * bx - c * bz : bz;
        float u = (float) ((T / 2.0 + (ppx + dx - centerX) * texPPB) / T);
        float v = (float) ((T / 2.0 + (ppz + dz - centerZ) * texPPB) / T);
        bb.vertex(m, cx + ox, cy + oy, 0f).texture(u, v).color(255, 255, 255, a);
    }

    private void drawBorder(DrawContext ctx, Matrix4f m, int x, int y, int sz, float cx, float cy, float r, boolean circle) {
        if (!circle) {
            Ui.outline(ctx, x - 1, y - 1, sz + 2, sz + 2, 2, Ui.ACCENT_LINE);
            return;
        }
        Batch.begin(false, false);
        Batch st = Batch.strip();
        int n = 72;
        int col = (Ui.ACCENT & 0xFFFFFF) | 0xCC000000;
        int out = 0x66000000;
        for (int i = 0; i <= n; i++) {
            double a = i * 2 * Math.PI / n;
            float co = (float) Math.cos(a), si = (float) Math.sin(a);
            st.v(m, cx + co * (r - 1f), cy + si * (r - 1f), 0, col);
            st.v(m, cx + co * (r + 1.5f), cy + si * (r + 1.5f), 0, col);
        }
        st.flush();
        Batch so = Batch.strip();
        for (int i = 0; i <= n; i++) {
            double a = i * 2 * Math.PI / n;
            float co = (float) Math.cos(a), si = (float) Math.sin(a);
            so.v(m, cx + co * (r + 1.5f), cy + si * (r + 1.5f), 0, out);
            so.v(m, cx + co * (r + 3f), cy + si * (r + 3f), 0, 0);
        }
        so.flush();
        Batch.end();
    }

    private void drawArrow(DrawContext ctx, Matrix4f m, float cx, float cy, boolean rot, float yawRad) {
        // направление «вперёд» на экране: вверх при вращении карты, иначе по взгляду
        float fx = rot ? 0f : (float) -Math.sin(yawRad);
        float fy = rot ? -1f : (float) Math.cos(yawRad);
        float px = -fy, py = fx;
        Batch.begin(false, false);
        Batch b = Batch.triangles();
        int col = 0xFFFFFFFF;
        int dark = 0xAA000000;
        float tip = 6.5f, back = 4f, wing = 4f;
        b.v(m, cx + fx * (tip + 1.5f), cy + fy * (tip + 1.5f), 0, dark);
        b.v(m, cx - fx * (back + 1.5f) + px * (wing + 1.5f), cy - fy * (back + 1.5f) + py * (wing + 1.5f), 0, dark);
        b.v(m, cx - fx * (back + 1.5f) - px * (wing + 1.5f), cy - fy * (back + 1.5f) - py * (wing + 1.5f), 0, dark);
        b.v(m, cx + fx * tip, cy + fy * tip, 0, col);
        b.v(m, cx - fx * back + px * wing, cy - fy * back + py * wing, 0, col);
        b.v(m, cx - fx * back - px * wing, cy - fy * back - py * wing, 0, col);
        b.flush();
        Batch.end();
    }

    private void drawCompass(DrawContext ctx, float cx, float cy, float r, boolean circle, boolean rot, float c, float s) {
        // направления мира (N, E, S, W) в экранных смещениях
        float[][] dirs = rot
                ? new float[][]{{s, c}, {-c, s}, {-s, -c}, {c, -s}}
                : new float[][]{{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        String[] names = {"N", "E", "S", "W"};
        for (int i = 0; i < 4; i++) {
            float[] pt = edge(dirs[i][0], dirs[i][1], r - 7f, circle);
            String t = names[i];
            Hud.text(ctx, t, Math.round(cx + pt[0] - Hud.w(t) / 2f), Math.round(cy + pt[1] - 4f), i == 0 ? 0xFFFF6B6B : 0xCCFFFFFF);
        }
    }

    /** Точка на окружности (или на рамке квадрата) в направлении (vx, vy) на расстоянии limit. */
    private static float[] edge(float vx, float vy, float limit, boolean circle) {
        float len = circle ? (float) Math.sqrt(vx * vx + vy * vy) : Math.max(Math.abs(vx), Math.abs(vy));
        if (len < 1.0e-5f) return new float[]{0f, -limit};
        return new float[]{vx / len * limit, vy / len * limit};
    }

    /** Смещение мира (dx, dz) в экранные пиксели относительно центра. */
    private static float[] toScreen(double dx, double dz, boolean rot, float c, float s, float scale) {
        double sx = rot ? -dx * c - dz * s : dx;
        double sy = rot ? dx * s - dz * c : dz;
        return new float[]{(float) (sx * scale), (float) (sy * scale)};
    }

    private void drawWaypoint(DrawContext ctx, WaypointSource.Waypoint wp, double ppx, double ppz, float cx, float cy, float r,
                              boolean circle, boolean rot, float c, float s, float scale) {
        float[] o = toScreen(wp.x() - ppx, wp.z() - ppz, rot, c, s, scale);
        float limit = r - 5f;
        float len = circle ? (float) Math.sqrt(o[0] * o[0] + o[1] * o[1]) : Math.max(Math.abs(o[0]), Math.abs(o[1]));
        if (len > limit) {
            o = edge(o[0], o[1], limit, circle);
        }
        int px = Math.round(cx + o[0]);
        int py = Math.round(cy + o[1]);
        int col = 0xFF000000 | wp.rgb();
        if (wp.death()) { // крестик
            ctx.fill(px - 3, py - 3, px + 4, py + 4, 0x99000000);
            for (int i = -2; i <= 2; i++) {
                ctx.fill(px + i, py + i, px + i + 1, py + i + 1, col);
                ctx.fill(px + i, py - i, px + i + 1, py - i + 1, col);
            }
        } else { // ромб
            for (int i = -3; i <= 3; i++) {
                int w = 3 - Math.abs(i);
                ctx.fill(px - w - 1, py + i, px + w + 2, py + i + 1, 0x99000000);
            }
            for (int i = -2; i <= 2; i++) {
                int w = 2 - Math.abs(i);
                ctx.fill(px - w, py + i, px + w + 1, py + i + 1, col);
            }
        }
    }

    private void drawEntities(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity self, float td, double ppx, double ppz,
                              float cx, float cy, float r, boolean circle, boolean rot, float c, float s, float scale) {
        boolean carts = chestCart.value || hopperCart.value || tntCart.value || otherCart.value || chestBoat.value || primedTnt.value;
        if (!players.value && !hostile.value && !passive.value && !items.value && !carts) return;
        float rr = range.value;
        long now = System.currentTimeMillis();
        int budget = 300;
        for (Entity e : mc.world.getEntities()) {
            if (e == self || e.isSpectator()) continue;
            if (--budget < 0) break;
            // 0 точка, 1 сундук-вагонетка, 2 воронка, 3 TNT-вагонетка, 4 другая вагонетка, 5 лодка с сундуком, 6 горящий TNT
            int kind = 0;
            int col = 0;
            int dotSize = 2;
            var type = e.getType();
            if (type == EntityType.CHEST_MINECART) {
                if (!chestCart.value) continue;
                kind = 1;
            } else if (type == EntityType.HOPPER_MINECART) {
                if (!hopperCart.value) continue;
                kind = 2;
            } else if (type == EntityType.TNT_MINECART) {
                if (!tntCart.value) continue;
                kind = 3;
            } else if (e instanceof AbstractMinecartEntity) {
                if (!otherCart.value) continue;
                kind = 4;
            } else if (e instanceof ChestBoatEntity) {
                if (!chestBoat.value) continue;
                kind = 5;
            } else if (e instanceof TntEntity) {
                if (!primedTnt.value) continue;
                kind = 6;
            } else if (e.isInvisible() || e instanceof ArmorStandEntity) {
                continue;
            } else if (e instanceof PlayerEntity pl) {
                if (!players.value) continue;
                boolean friend = ModuleManager.entityEsp != null && ModuleManager.entityEsp.isFriend(pl.getName().getString());
                if (playersFilter.is("Friends only") && !friend) continue;
                if (playersFilter.is("Enemies only") && friend) continue;
                if (friend) col = DOT_FRIEND;
                else if (ClientLink.isLinked(pl.getUuid())) col = Ui.ACCENT & 0xFFFFFF;
                else col = DOT_PLAYER[0];
                dotSize = 3;
            } else if (e instanceof Monster) {
                if (!hostile.value) continue;
                col = DOT_HOSTILE;
            } else if (e instanceof LivingEntity) {
                if (!passive.value) continue;
                col = DOT_PASSIVE;
            } else if (e instanceof ItemEntity) {
                if (!items.value) continue;
                col = DOT_ITEM;
                dotSize = 1;
            } else {
                continue;
            }
            var ep = e.getLerpedPos(td);
            double dx = ep.x - ppx, dz = ep.z - ppz;
            double dist2 = dx * dx + dz * dz;
            float[] o = toScreen(dx, dz, rot, c, s, scale);
            float limit = r - (kind == 0 ? 3f : 6f);
            float len = circle ? (float) Math.sqrt(o[0] * o[0] + o[1] * o[1]) : Math.max(Math.abs(o[0]), Math.abs(o[1]));
            boolean outside = dist2 > (double) rr * rr || len > limit;
            if (outside) {
                // сундуки на колёсах и лодки хочется видеть всегда: за пределами радиуса рисуем их на краю карты
                if (cartEdge.value && (kind == 1 || kind == 5)) o = edge(o[0], o[1], limit, circle);
                else continue;
            }
            int px = Math.round(cx + o[0]);
            int py = Math.round(cy + o[1]);
            if (kind == 0) {
                int h = dotSize / 2;
                ctx.fill(px - h - 1, py - h - 1, px - h + dotSize + 1, py - h + dotSize + 1, 0xAA000000);
                ctx.fill(px - h, py - h, px - h + dotSize, py - h + dotSize, 0xFF000000 | col);
                continue;
            }
            drawIcon(ctx, kind, px, py, now);
            if (cartDistance.value && (kind == 1 || kind == 5)) {
                String t = Text.translatableWithFallback("swinganimations.minimap.dist", "%sm", Math.round(Math.sqrt(dist2))).getString();
                int tw = Hud.w(t);
                int tx = o[0] > r * 0.35f ? px - Math.round(7 * iconScale.value) - tw : px + Math.round(7 * iconScale.value);
                Hud.text(ctx, t, tx, py - 4, 0xFFFFE066);
            }
        }
    }

    /** Значки техники и сундуков 7x5 пикселей, масштабируются настройкой «Icon scale». */
    private void drawIcon(DrawContext ctx, int kind, int px, int py, long now) {
        var m = ctx.getMatrices();
        m.push();
        m.translate(px, py, 0f);
        float sc = iconScale.value;
        m.scale(sc, sc, 1f);
        boolean chest = kind == 1 || kind == 5;
        if (chest && highlightCarts.value) {
            int a = 0x28 + (int) (0x48 * (0.5 + 0.5 * Math.sin(now / 260.0)));
            ctx.fill(-6, -5, 7, 6, (a << 24) | 0xFFE066);
        }
        ctx.fill(-4, -3, 5, 4, 0xCC000000); // тёмная обводка
        switch (kind) {
            case 1 -> { // вагонетка с сундуком: коричневый сундук с защёлкой
                ctx.fill(-3, -2, 4, 3, 0xFF9A6A32);
                ctx.fill(-3, -2, 4, -1, 0xFFBA8A4C);
                ctx.fill(-3, 0, 4, 1, 0xFF5E3C14);
                ctx.fill(0, -1, 1, 2, 0xFFFFE066);
            }
            case 2 -> { // вагонетка с воронкой
                ctx.fill(-3, -2, 4, 3, 0xFF6B7078);
                ctx.fill(-1, -1, 2, 2, 0xFF2B2E33);
            }
            case 3 -> { // вагонетка с TNT
                ctx.fill(-3, -2, 4, 3, 0xFFD13B3B);
                ctx.fill(-3, 0, 4, 1, 0xFFF2F2F2);
            }
            case 4 -> ctx.fill(-3, -2, 4, 3, 0xFF8E96A3); // прочие вагонетки
            case 5 -> { // лодка с сундуком: тёмный корпус и сундук сверху
                ctx.fill(-3, -1, 4, 3, 0xFF5B4630);
                ctx.fill(-1, -2, 2, 0, 0xFF9A6A32);
                ctx.fill(0, -2, 1, -1, 0xFFFFE066);
            }
            default -> { // горящий TNT: мигает красным и белым
                boolean on = (now / 160L) % 2L == 0L;
                ctx.fill(-3, -2, 4, 3, on ? 0xFFF2F2F2 : 0xFFD13B3B);
            }
        }
        m.pop();
    }
}
