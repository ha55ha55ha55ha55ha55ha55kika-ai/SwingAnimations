package com.swinganimations.module;

import com.mojang.blaze3d.systems.RenderSystem;
import com.swinganimations.gui.Hud;
import com.swinganimations.gui.HudLayout;
import com.swinganimations.gui.Ui;
import com.swinganimations.link.ClientLink;
import com.swinganimations.render.Batch;
import com.swinganimations.util.Perf;
import com.swinganimations.util.UserWaypoints;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.math.RotationAxis;
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
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.client.util.math.MatrixStack;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

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
    private final Setting.Num dotSize = add(new Setting.Num("dot_size", "Entity dot size", 2f, 1f, 5f, 1f));
    private final Setting.Mode dotStyle = add(new Setting.Mode("dot_style", "Dot style", "Square", "Round", "Arrow"));
    private final Setting.Bool playerHeads = add(new Setting.Bool("player_heads", "Player heads", true));
    private final Setting.Bool mobHeads = add(new Setting.Bool("mob_heads", "Mob head icons", true));
    private final Setting.Num mobIconSize = add(new Setting.Num("mob_icon_size", "Mob icon size", 9f, 6f, 16f, 1f).<Setting.Num>visibleWhen(() -> mobHeads.value));
    private final Setting.Bool itemIcons = add(new Setting.Bool("item_icons", "Dropped items as icons", true));
    private final Setting.Num arrowSize = add(new Setting.Num("arrow_size", "My arrow size", 1f, 0.5f, 3f, 0.25f));
    private final Setting.Num headSize = add(new Setting.Num("head_size", "Head size", 8f, 6f, 14f, 1f).<Setting.Num>visibleWhen(() -> playerHeads.value));
    private final Setting.Mode names = add(new Setting.Mode("names", "Names", "Off", "Players", "All"));
    private final Setting.Num heightFilter = add(new Setting.Num("height_filter", "Height filter (0 = off)", 0f, 0f, 128f, 4f));
    private final Setting.Bool heightHint = add(new Setting.Bool("height_hint", "Above/below markers", true));
    private final Setting.Num maxEntities = add(new Setting.Num("max_entities", "Max entities", 300f, 50f, 1000f, 50f));
    private final Setting.Mode caveZoom = add(new Setting.Mode("cave_zoom", "Zoom in caves", "Off", "2x", "3x"));
    private final Setting.Bool chunkGrid = add(new Setting.Bool("chunk_grid", "Chunk grid", false));
    private final Setting.Bool userWaypoints = add(new Setting.Bool("user_waypoints", "My waypoints (key B)", true));
    private final Setting.Bool waypointNames = add(new Setting.Bool("waypoint_names", "Waypoint names and distance", true));
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
    private float curRange = 48f; // дальность с учётом приближения в пещерах
    private int caveHold;

    public MinimapModule() {
        super("minimap", "Minimap", "hud");
        caveZoom.index = 1; // по умолчанию 2x, как у Xaero
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
        // Приближение под землёй: с задержкой, чтобы карта не дёргалась на границе открытого неба
        int zf = caveZoom.is("2x") ? 2 : (caveZoom.is("3x") ? 3 : 1);
        if (zf > 1 && !mc.world.getDimension().hasCeiling() && !mc.world.isSkyVisible(p.getBlockPos())) {
            if (caveHold < 20) caveHold++;
        } else if (caveHold > 0) {
            caveHold--;
        }
        curRange = MathHelper.clamp(zf > 1 && caveHold >= 10 ? range.value / zf : range.value, 8f, 160f);
        int every = Perf.quality() == 0 ? 4 : 8;
        // В Нижнем мире на каждый пиксель до 48 шагов вниз по блокам: строим вдвое реже.
        if (mc.world.getDimension().hasCeiling()) every *= 2;
        // Стоим на месте: карта почти не меняется (только подгружаются чанки), хватает раза в 1,5 с.
        double mdx = p.getX() - lastBuildX, mdz = p.getZ() - lastBuildZ;
        if (!(mdx * mdx + mdz * mdz > 0.25)) every = Math.max(every, 30);
        if (++ticks >= every || Math.abs(curRange - builtRange) > 0.01f || tex == null) {
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
        float r = curRange;
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
        float scale = r / curRange;
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
        if (chunkGrid.value) drawChunkGrid(m, cx, cy, r, circle, rot, c, s, pp.x, pp.z, scale);

        // метки
        if (waypoints.value) {
            for (Module mod : ModuleManager.MODULES) {
                if (!(mod instanceof WaypointSource src) || !mod.isEnabled()) continue;
                for (WaypointSource.Waypoint wp : src.waypoints()) {
                    drawWaypoint(ctx, wp, pp.x, pp.z, cx, cy, r, circle, rot, c, s, scale);
                }
            }
        }
        if (waypoints.value && userWaypoints.value) {
            for (UserWaypoints.Pt pt : UserWaypoints.list(mc)) {
                drawWaypoint(ctx, new WaypointSource.Waypoint(pt.x, pt.y, pt.z, pt.name, pt.rgb, false),
                        pp.x, pp.z, cx, cy, r, circle, rot, c, s, scale);
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
        float k = arrowSize.value;
        float tip = 6.5f * k, back = 4f * k, wing = 4f * k;
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
            if (waypointNames.value) {
                String nm = wp.name() == null ? "" : wp.name();
                if (nm.length() > 16) nm = nm.substring(0, 15) + ".";
                String dist = Text.translatableWithFallback("swinganimations.minimap.dist", "%sm",
                        Math.round(Math.hypot(wp.x() - ppx, wp.z() - ppz))).getString();
                label(ctx, nm.isEmpty() ? dist : nm + " " + dist, px, py + 5, wp.rgb());
            }
        }
    }

    private void drawEntities(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity self, float td, double ppx, double ppz,
                              float cx, float cy, float r, boolean circle, boolean rot, float c, float s, float scale) {
        boolean carts = chestCart.value || hopperCart.value || tntCart.value || otherCart.value || chestBoat.value || primedTnt.value;
        if (!players.value && !hostile.value && !passive.value && !items.value && !carts) return;
        float rr = curRange;
        long now = System.currentTimeMillis();
        int budget = (int) maxEntities.value;
        int labels = 0;
        double selfY = self.getY();
        float hf = heightFilter.value;
        for (Entity e : mc.world.getEntities()) {
            if (e == self || e.isSpectator()) continue;
            if (--budget < 0) break;
            // 0 точка, 1 сундук-вагонетка, 2 воронка, 3 TNT-вагонетка, 4 другая вагонетка, 5 лодка с сундуком, 6 горящий TNT
            int kind = 0;
            int col = 0;
            int dotSize = (int) this.dotSize.value;
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
                boolean friend = ModuleManager.hitboxes != null && ModuleManager.hitboxes.isFriend(pl.getName().getString());
                if (playersFilter.is("Friends only") && !friend) continue;
                if (playersFilter.is("Enemies only") && friend) continue;
                if (friend) col = DOT_FRIEND;
                else if (ClientLink.isLinked(pl.getUuid())) col = Ui.ACCENT & 0xFFFFFF;
                else col = DOT_PLAYER[0];
                dotSize = (int) this.dotSize.value + 1;
            } else if (e instanceof Monster) {
                if (!hostile.value) continue;
                col = DOT_HOSTILE;
            } else if (e instanceof LivingEntity) {
                if (!passive.value) continue;
                col = DOT_PASSIVE;
            } else if (e instanceof ItemEntity) {
                if (!items.value) continue;
                col = DOT_ITEM;
                dotSize = Math.max(1, (int) this.dotSize.value - 1);
            } else {
                continue;
            }
            var ep = e.getLerpedPos(td);
            if (hf > 0f && kind == 0 && Math.abs(ep.y - selfY) > hf) continue;
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
                boolean isItem = e instanceof ItemEntity;
                drawDot(ctx, e, px, py, dotSize, col, isItem, td, rot, c, s);
                if (heightHint.value && !isItem) {
                    double dy = ep.y - selfY;
                    if (dy > 4.0 || dy < -4.0) heightMark(ctx, px, py, dotSize, dy > 0, col);
                }
                if (!isItem && labels < 24 && (names.is("All") || (names.is("Players") && e instanceof PlayerEntity))) {
                    labels++;
                    label(ctx, e.getName().getString(), px, py + dotSize / 2 + 3, col);
                }
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

    // ---------------- точки сущностей ----------------

    private void drawDot(DrawContext ctx, Entity e, int px, int py, int ds, int col, boolean item,
                         float td, boolean rot, float c, float s) {
        if (item && itemIcons.value && e instanceof ItemEntity ie && !ie.getStack().isEmpty()) {
            drawItemIcon(ctx, ie.getStack(), px, py, Math.max(6, (int) mobIconSize.value - 1), col);
            return;
        }
        if (!item && mobHeads.value && e instanceof LivingEntity && !(e instanceof PlayerEntity)) {
            ItemStack icon = mobIcon(e.getType());
            if (!icon.isEmpty()) {
                drawItemIcon(ctx, icon, px, py, (int) mobIconSize.value, col);
                return;
            }
        }
        if (!item && playerHeads.value && e instanceof AbstractClientPlayerEntity acp) {
            int hs = (int) headSize.value;
            int x0 = px - hs / 2, y0 = py - hs / 2;
            ctx.fill(x0 - 1, y0 - 1, x0 + hs + 1, y0 + hs + 1, 0xFF000000 | col);
            Identifier skin = acp.getSkinTextures().texture();
            ctx.drawTexture(RenderLayer::getGuiTextured, skin, x0, y0, 8f, 8f, hs, hs, 8, 8, 64, 64);   // лицо
            ctx.drawTexture(RenderLayer::getGuiTextured, skin, x0, y0, 40f, 8f, hs, hs, 8, 8, 64, 64);  // второй слой (шапка)
            return;
        }
        if (item || dotStyle.is("Square")) {
            int h = ds / 2;
            ctx.fill(px - h - 1, py - h - 1, px - h + ds + 1, py - h + ds + 1, 0xAA000000);
            ctx.fill(px - h, py - h, px - h + ds, py - h + ds, 0xFF000000 | col);
            return;
        }
        if (dotStyle.is("Round")) {
            float rr = ds / 2f + 0.75f;
            float out = rr + 1f;
            int R = (int) Math.ceil(out);
            for (int dy = -R; dy <= R; dy++) {
                if (Math.abs(dy) <= out) {
                    int w = (int) Math.floor(Math.sqrt(Math.max(0f, out * out - dy * dy)));
                    ctx.fill(px - w, py + dy, px + w + 1, py + dy + 1, 0xAA000000);
                }
            }
            for (int dy = -R; dy <= R; dy++) {
                if (Math.abs(dy) <= rr) {
                    int w = (int) Math.floor(Math.sqrt(Math.max(0f, rr * rr - dy * dy)));
                    ctx.fill(px - w, py + dy, px + w + 1, py + dy + 1, 0xFF000000 | col);
                }
            }
            return;
        }
        // Arrow: стрелка по направлению взгляда существа (с учётом вращения карты)
        double yaw = Math.toRadians(e.getYaw(td));
        float[] u = toScreen(-Math.sin(yaw), Math.cos(yaw), rot, c, s, 1f);
        float ang = (float) Math.atan2(u[0], -u[1]);
        var m = ctx.getMatrices();
        m.push();
        m.translate(px, py, 0f);
        m.multiply(RotationAxis.POSITIVE_Z.rotation(ang));
        int L = ds + 2;
        for (int y = -L; y <= L / 2 + 1; y++) {
            int w = (y + L) / 2;
            ctx.fill(-w - 1, y, w + 2, y + 1, 0xAA000000);
        }
        for (int y = -L + 1; y <= L / 2; y++) {
            int w = (y + L - 1) / 2;
            ctx.fill(-w, y, w + 1, y + 1, 0xFF000000 | col);
        }
        m.pop();
    }

    // ---------------- иконки мобов и предметов ----------------

    private static final Map<EntityType<?>, ItemStack> MOB_ICONS = new IdentityHashMap<>();

    /**
     * «Голова» моба: настоящая голова там, где она есть в игре (зомби, скелет, крипер, пиглин, дракон),
     * у голема, снежного голема и Визера предмет, который их символизирует, у остальных яйцо призыва этого моба (цвета как в игре),
     * у мобов без яйца (в том числе из модов) бирка. Значок есть у каждого моба.
     */
    private static ItemStack mobIcon(EntityType<?> t) {
        ItemStack st = MOB_ICONS.get(t);
        if (st != null) return st;
        if (t == EntityType.ZOMBIE || t == EntityType.HUSK || t == EntityType.DROWNED || t == EntityType.ZOMBIE_VILLAGER) st = new ItemStack(Items.ZOMBIE_HEAD);
        else if (t == EntityType.SKELETON || t == EntityType.STRAY || t == EntityType.BOGGED) st = new ItemStack(Items.SKELETON_SKULL);
        else if (t == EntityType.WITHER_SKELETON) st = new ItemStack(Items.WITHER_SKELETON_SKULL);
        else if (t == EntityType.CREEPER) st = new ItemStack(Items.CREEPER_HEAD);
        else if (t == EntityType.PIGLIN || t == EntityType.PIGLIN_BRUTE || t == EntityType.ZOMBIFIED_PIGLIN) st = new ItemStack(Items.PIGLIN_HEAD);
        else if (t == EntityType.ENDER_DRAGON) st = new ItemStack(Items.DRAGON_HEAD);
        else if (t == EntityType.IRON_GOLEM) st = new ItemStack(Items.IRON_BLOCK);
        else if (t == EntityType.SNOW_GOLEM) st = new ItemStack(Items.CARVED_PUMPKIN);
        else if (t == EntityType.WITHER) st = new ItemStack(Items.NETHER_STAR);
        else if (t == EntityType.GIANT) st = new ItemStack(Items.ZOMBIE_HEAD);
        else if (t == EntityType.ILLUSIONER) st = new ItemStack(Items.ENDER_EYE);
        else {
            SpawnEggItem egg = SpawnEggItem.forEntity(t);
            // у мобов без яйца (в том числе из других модов) значок «бирка», чтобы ни один моб не остался просто точкой
            st = new ItemStack(egg != null ? egg : Items.NAME_TAG);
        }
        MOB_ICONS.put(t, st);
        return st;
    }

    /** Предмет как значок на карте: в рамке цвета категории (враг, моб, предмет) на тёмной подложке. */
    private void drawItemIcon(DrawContext ctx, ItemStack st, int px, int py, int size, int col) {
        int x0 = px - size / 2, y0 = py - size / 2;
        ctx.fill(x0 - 1, y0 - 1, x0 + size + 1, y0 + size + 1, 0xFF000000 | col);
        ctx.fill(x0, y0, x0 + size, y0 + size, 0xFF16181F);
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(x0, y0, 0f);
        float sc = size / 16f;
        m.scale(sc, sc, 1f);
        ctx.drawItem(st, 0, 0);
        m.pop();
    }

    /** Маленький треугольник над точкой (цель выше нас) или под ней (ниже). */
    private static void heightMark(DrawContext ctx, int px, int py, int ds, boolean up, int col) {
        int off = ds / 2 + 3;
        int cy = up ? py - off : py + off;
        int tip = up ? cy - 1 : cy + 1;
        int c = 0xFF000000 | col;
        ctx.fill(px, tip, px + 1, tip + 1, c);
        ctx.fill(px - 1, cy, px + 2, cy + 1, c);
    }

    /** Подпись по центру точки: мелкий текст на тёмной подложке. */
    private void label(DrawContext ctx, String t, int px, int py, int col) {
        if (t == null || t.isEmpty()) return;
        if (t.length() > 18) t = t.substring(0, 17) + ".";
        int w = Hud.w(t);
        var m = ctx.getMatrices();
        m.push();
        m.translate(px, py, 0f);
        m.scale(0.7f, 0.7f, 1f);
        ctx.fill(-w / 2 - 2, -1, w / 2 + w % 2 + 2, 9, 0x66000000);
        Hud.text(ctx, t, -w / 2, 0, 0xFF000000 | (col & 0xFFFFFF));
        m.pop();
    }

    // ---------------- сетка чанков ----------------

    /** Границы чанков (каждые 16 блоков) тонкими линиями, обрезанными по кругу или квадрату карты. */
    private void drawChunkGrid(Matrix4f m, float cx, float cy, float r, boolean circle, boolean rot,
                               float c, float s, double ppx, double ppz, float scale) {
        float ext = (r / scale) * 1.5f + 2f;
        int x0 = MathHelper.floor((ppx - ext) / 16.0), x1 = MathHelper.floor((ppx + ext) / 16.0) + 1;
        int z0 = MathHelper.floor((ppz - ext) / 16.0), z1 = MathHelper.floor((ppz + ext) / 16.0) + 1;
        Batch.begin(false, false);
        Batch b = Batch.triangles();
        int col = 0x3AFFFFFF;
        for (int gx = x0; gx <= x1; gx++) {
            double wx = gx * 16.0 - ppx;
            gridLine(b, m, cx, cy, r, circle, toScreen(wx, -ext, rot, c, s, scale), toScreen(wx, ext, rot, c, s, scale), col);
        }
        for (int gz = z0; gz <= z1; gz++) {
            double wz = gz * 16.0 - ppz;
            gridLine(b, m, cx, cy, r, circle, toScreen(-ext, wz, rot, c, s, scale), toScreen(ext, wz, rot, c, s, scale), col);
        }
        b.flush();
        Batch.end();
    }

    private static void gridLine(Batch b, Matrix4f m, float cx, float cy, float r, boolean circle,
                                 float[] p, float[] q, int col) {
        float x0 = p[0], y0 = p[1];
        float dx = q[0] - x0, dy = q[1] - y0;
        float t0 = 0f, t1 = 1f;
        if (circle) {
            float a = dx * dx + dy * dy;
            if (a < 1e-6f) return;
            float bq = 2f * (x0 * dx + y0 * dy);
            float cc = x0 * x0 + y0 * y0 - r * r;
            float disc = bq * bq - 4f * a * cc;
            if (disc <= 0f) return;
            float sq = (float) Math.sqrt(disc);
            t0 = Math.max(0f, (-bq - sq) / (2f * a));
            t1 = Math.min(1f, (-bq + sq) / (2f * a));
        } else { // Лян-Барский по квадрату [-r, r]
            float[] pp = {-dx, dx, -dy, dy};
            float[] qq = {x0 + r, r - x0, y0 + r, r - y0};
            for (int k = 0; k < 4; k++) {
                if (Math.abs(pp[k]) < 1e-6f) {
                    if (qq[k] < 0f) return;
                } else {
                    float t = qq[k] / pp[k];
                    if (pp[k] < 0f) {
                        if (t > t1) return;
                        if (t > t0) t0 = t;
                    } else {
                        if (t < t0) return;
                        if (t < t1) t1 = t;
                    }
                }
            }
        }
        if (t1 - t0 < 0.001f) return;
        float ax = x0 + dx * t0, ay = y0 + dy * t0;
        float bx = x0 + dx * t1, by = y0 + dy * t1;
        float len = (float) Math.hypot(bx - ax, by - ay);
        if (len < 0.5f) return;
        float nx = -(by - ay) / len * 0.5f, ny = (bx - ax) / len * 0.5f;
        b.v(m, cx + ax - nx, cy + ay - ny, 0, col);
        b.v(m, cx + ax + nx, cy + ay + ny, 0, col);
        b.v(m, cx + bx + nx, cy + by + ny, 0, col);
        b.v(m, cx + ax - nx, cy + ay - ny, 0, col);
        b.v(m, cx + bx + nx, cy + by + ny, 0, col);
        b.v(m, cx + bx - nx, cy + by - ny, 0, col);
    }

    // ---------------- свои метки ----------------

    /** Ставит метку на текущую позицию игрока (клавиша из «Управления»). */
    public void addWaypointHere(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return;
        String name = Text.translatableWithFallback("swinganimations.minimap.mark_default", "Mark %s",
                UserWaypoints.list(mc).size() + 1).getString();
        UserWaypoints.add(mc, name, p.getX(), p.getY(), p.getZ());
        if (mc.inGameHud != null) {
            mc.inGameHud.setOverlayMessage(Text.translatableWithFallback("swinganimations.minimap.mark_added",
                    "Waypoint \"%s\" added", name), false);
        }
    }

    /** Удаляет ближайшую свою метку в радиусе 12 блоков. */
    public void removeNearestWaypoint(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return;
        UserWaypoints.Pt gone = UserWaypoints.removeNearest(mc, p.getX(), p.getZ(), 12.0);
        if (mc.inGameHud == null) return;
        mc.inGameHud.setOverlayMessage(gone != null
                ? Text.translatableWithFallback("swinganimations.minimap.mark_removed", "Waypoint \"%s\" removed", gone.name)
                : Text.translatableWithFallback("swinganimations.minimap.mark_none", "No waypoints nearby"), false);
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
