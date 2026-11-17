package com.swinganimations.module;

import com.swinganimations.gui.Ui;
import com.swinganimations.render.Batch;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Hitboxes: свои хитбоксы вместо ванильных (F3+B). Для каждой группы (игроки, друзья, мобы, предметы) свой цвет:
 * готовые цвета, акцент, радуга или свой RGB. Линия взгляда и линия на уровне глаз выключены по умолчанию.
 * Рисуются только то, что видно (с проверкой глубины): сквозь блоки хитбоксы не просвечивают, невидимые существа не показываются.
 * Только градиентные треугольники, без GLSL-шейдеров, поэтому работает и на Pojav.
 * Здесь же список друзей (клавиша «Добавить/убрать друга» на игрока в прицеле): друзья отдельная группа со своим цветом.
 */
public final class HitboxesModule extends Module {
    private static final String[] PRESETS = {"Accent", "Rainbow", "Red", "Orange", "Yellow", "Green", "Cyan", "Blue", "Purple", "Pink", "White", "Custom"};
    private static final int[] PRESET_RGB = {0, 0, 0xFF4040, 0xFF9A3C, 0xFFE066, 0x3CFF6E, 0x4DE0FF, 0x5B8CFF, 0xB56CFF, 0xFF6BC9, 0xFFFFFF, 0};

    /** Группа сущностей: включена ли и каким цветом рисуется. Свой RGB показывается только при выборе «Custom». */
    private final class Group {
        final Setting.Bool on;
        final Setting.Mode color;
        final Setting.Num r;
        final Setting.Num g;
        final Setting.Num b;

        Group(String id, String title, boolean def, String preset, int customRgb) {
            on = add(new Setting.Bool(id, title, def));
            color = add(new Setting.Mode(id + "_color", title + " color", PRESETS));
            for (int i = 0; i < PRESETS.length; i++) if (PRESETS[i].equals(preset)) color.index = i;
            r = add(new Setting.Num(id + "_red", "Red", (customRgb >> 16) & 255, 0f, 255f, 1f).<Setting.Num>visibleWhen(this::custom));
            g = add(new Setting.Num(id + "_green", "Green", (customRgb >> 8) & 255, 0f, 255f, 1f).<Setting.Num>visibleWhen(this::custom));
            b = add(new Setting.Num(id + "_blue", "Blue", customRgb & 255, 0f, 255f, 1f).<Setting.Num>visibleWhen(this::custom));
        }

        boolean custom() { return color.is("Custom"); }

        int rgb(long now, float offset) {
            if (color.is("Rainbow")) {
                float hue = (((now / 25L) % 360L) / 360f + offset) % 1f;
                return MathHelper.hsvToRgb(hue, 0.65f, 1f);
            }
            if (color.is("Accent")) return Ui.ACCENT & 0xFFFFFF;
            if (custom()) return ((int) r.value << 16) | ((int) g.value << 8) | (int) b.value;
            return PRESET_RGB[color.index];
        }
    }

    private final Group players = new Group("players", "Players", true, "Red", 0xFF4040);
    private final Group friends = new Group("friends", "Friends", true, "Green", 0x3CFF6E);
    private final Group mobs = new Group("mobs", "Mobs", false, "Orange", 0xFF9A3C);
    private final Group items = new Group("items", "Items", false, "Cyan", 0x4DE0FF);
    private final Setting.Mode style = add(new Setting.Mode("style", "Style", "Outline", "Fill", "Both"));
    private final Setting.Num width = add(new Setting.Num("width", "Line width", 2f, 1f, 6f, 0.5f));
    private final Setting.Num opacity = add(new Setting.Num("opacity", "Opacity", 0.9f, 0.1f, 1f, 0.05f));
    private final Setting.Num range = add(new Setting.Num("range", "Range", 48f, 8f, 128f, 4f));
    private final Setting.Bool eyeLine = add(new Setting.Bool("eye_line", "Eye height line", false));
    private final Setting.Bool lookLine = add(new Setting.Bool("look_line", "Look direction line", false));
    private final Setting.Bool own = add(new Setting.Bool("own", "Own hitbox (third person)", false));
    private final Setting.Bool hideVanilla = add(new Setting.Bool("hide_vanilla", "Hide vanilla hitboxes (F3+B)", true));
    private final Setting.Str friendList = add(new Setting.Str("friend_list", "Friends list", ""));

    private final Set<String> friendSet = new HashSet<>();
    private String friendSrc = null;

    public HitboxesModule() {
        super("hitboxes", "Hitboxes", "render");
    }

    @Override
    public boolean wantsWorldRender() { return true; }

    /** Пока модуль включён, ванильные хитбоксы (F3+B) не рисуем: вместо них наши, без лишних линий. */
    @Override
    public void tick(MinecraftClient mc) {
        if (hideVanilla.value && mc.getEntityRenderDispatcher() != null) {
            mc.getEntityRenderDispatcher().setRenderHitboxes(false);
        }
    }

    // ---------------- друзья ----------------

    private void syncFriends() {
        if (friendList.value.equals(friendSrc)) return;
        friendSrc = friendList.value;
        friendSet.clear();
        for (String s : friendSrc.split(",")) {
            String n = s.trim().toLowerCase(Locale.ROOT);
            if (!n.isEmpty()) friendSet.add(n);
        }
    }

    public boolean isFriend(String name) {
        syncFriends();
        return friendSet.contains(name.toLowerCase(Locale.ROOT));
    }

    /** Добавляет или убирает друга. Возвращает true, если игрок теперь друг. */
    public boolean toggleFriend(String name) {
        syncFriends();
        String n = name.toLowerCase(Locale.ROOT);
        boolean added;
        if (friendSet.contains(n)) {
            friendSet.remove(n);
            added = false;
        } else {
            friendSet.add(n);
            added = true;
        }
        friendList.value = String.join(",", friendSet);
        friendSrc = friendList.value;
        ModuleManager.save();
        return added;
    }

    // ---------------- рендер ----------------

    @Override
    public void render(RenderContext c) {
        MinecraftClient mc = c.mc;
        if (mc.world == null || mc.player == null) return;
        Vec3d cam = c.camera.getPos();
        double r2 = (double) range.value * range.value;
        float op = opacity.value;
        boolean outline = !style.is("Fill");
        boolean fill = !style.is("Outline");
        double w = width.value * 0.01;
        boolean third = !mc.options.getPerspective().isFirstPerson();
        Batch tris = null;
        for (Entity e : mc.world.getEntities()) {
            if (e.isRemoved() || e.isInvisible()) continue; // невидимых не показываем
            Group g;
            if (e == mc.player) {
                if (!own.value || !third) continue;
                g = players;
            } else if (e instanceof PlayerEntity p) {
                if (p.isSpectator()) continue;
                g = isFriend(p.getName().getString()) ? friends : players;
            } else if (e instanceof ItemEntity) {
                g = items;
            } else if (e instanceof LivingEntity && !(e instanceof ArmorStandEntity)) {
                g = mobs;
            } else {
                continue;
            }
            if (!g.on.value || e.squaredDistanceTo(cam) > r2) continue;
            if (tris == null) {
                Batch.begin(true, true); // проверка глубины включена: сквозь блоки не просвечивает
                tris = Batch.triangles();
            }
            int rgb = g.rgb(c.now, (e.getId() % 17) / 17f);
            double dx = MathHelper.lerp(c.tickDelta, e.lastRenderX, e.getX()) - e.getX();
            double dy = MathHelper.lerp(c.tickDelta, e.lastRenderY, e.getY()) - e.getY();
            double dz = MathHelper.lerp(c.tickDelta, e.lastRenderZ, e.getZ()) - e.getZ();
            Box raw = e.getBoundingBox().offset(dx, dy, dz);
            Box b = raw.expand(0.01);
            if (fill) fillBox(tris, c.m, b, rgb, 0.22f * op);
            if (outline) outlineBox(tris, c.m, b, rgb, op, w);
            if ((eyeLine.value || lookLine.value) && e instanceof LivingEntity) {
                double ey = raw.minY + e.getEyeHeight(e.getPose());
                double cx = (raw.minX + raw.maxX) / 2.0, cz = (raw.minZ + raw.maxZ) / 2.0;
                if (eyeLine.value) {
                    int ec = Batch.argb(Batch.mix(rgb, 0xFFFFFF, 0.35f), op);
                    hEdgeX(tris, c.m, b.minX, b.maxX, ey, b.minZ, w, ec);
                    hEdgeX(tris, c.m, b.minX, b.maxX, ey, b.maxZ, w, ec);
                    hEdgeZ(tris, c.m, b.minX, ey, b.minZ, b.maxZ, w, ec);
                    hEdgeZ(tris, c.m, b.maxX, ey, b.minZ, b.maxZ, w, ec);
                }
                if (lookLine.value) {
                    Vec3d look = e.getRotationVec(c.tickDelta);
                    line(tris, c.m, cx, ey, cz, cx + look.x * 2.0, ey + look.y * 2.0, cz + look.z * 2.0, w,
                            Batch.argb(Batch.mix(rgb, 0xFFFFFF, 0.35f), op));
                }
            }
        }
        if (tris != null) {
            tris.flush();
            Batch.end();
        }
    }

    // ---------------- примитивы ----------------

    private static void quad(Batch t, Matrix4f m,
                             double ax, double ay, double az,
                             double bx, double by, double bz,
                             double cx, double cy, double cz,
                             double dx, double dy, double dz, int col) {
        t.v(m, ax, ay, az, col);
        t.v(m, bx, by, bz, col);
        t.v(m, cx, cy, cz, col);
        t.v(m, ax, ay, az, col);
        t.v(m, cx, cy, cz, col);
        t.v(m, dx, dy, dz, col);
    }

    private static void vEdge(Batch t, Matrix4f m, double cx, double cz, double y0, double y1, double w, int col) {
        quad(t, m, cx - w, y0, cz, cx + w, y0, cz, cx + w, y1, cz, cx - w, y1, cz, col);
        quad(t, m, cx, y0, cz - w, cx, y0, cz + w, cx, y1, cz + w, cx, y1, cz - w, col);
    }

    private static void hEdgeX(Batch t, Matrix4f m, double x0, double x1, double y, double z, double w, int col) {
        quad(t, m, x0, y, z - w, x1, y, z - w, x1, y, z + w, x0, y, z + w, col);
        quad(t, m, x0, y - w, z, x1, y - w, z, x1, y + w, z, x0, y + w, z, col);
    }

    private static void hEdgeZ(Batch t, Matrix4f m, double x, double y, double z0, double z1, double w, int col) {
        quad(t, m, x - w, y, z0, x - w, y, z1, x + w, y, z1, x + w, y, z0, col);
        quad(t, m, x, y - w, z0, x, y - w, z1, x, y + w, z1, x, y + w, z0, col);
    }

    /** Рёбра коробки: 4 вертикальных и по 4 горизонтальных сверху и снизу. */
    private static void outlineBox(Batch t, Matrix4f m, Box b, int rgb, float op, double w) {
        int col = Batch.argb(rgb, op);
        vEdge(t, m, b.minX, b.minZ, b.minY, b.maxY, w, col);
        vEdge(t, m, b.maxX, b.minZ, b.minY, b.maxY, w, col);
        vEdge(t, m, b.maxX, b.maxZ, b.minY, b.maxY, w, col);
        vEdge(t, m, b.minX, b.maxZ, b.minY, b.maxY, w, col);
        for (double y : new double[]{b.minY, b.maxY}) {
            hEdgeX(t, m, b.minX, b.maxX, y, b.minZ, w, col);
            hEdgeX(t, m, b.minX, b.maxX, y, b.maxZ, w, col);
            hEdgeZ(t, m, b.minX, y, b.minZ, b.maxZ, w, col);
            hEdgeZ(t, m, b.maxX, y, b.minZ, b.maxZ, w, col);
        }
    }

    /** Полупрозрачные грани коробки. */
    private static void fillBox(Batch t, Matrix4f m, Box b, int rgb, float alpha) {
        int col = Batch.argb(rgb, alpha);
        double x0 = b.minX, x1 = b.maxX, y0 = b.minY, y1 = b.maxY, z0 = b.minZ, z1 = b.maxZ;
        quad(t, m, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, col);
        quad(t, m, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, col);
        quad(t, m, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, col);
        quad(t, m, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, col);
        quad(t, m, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, col);
        quad(t, m, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, col);
    }

    /** Тонкая линия в пространстве: две перекрещенные ленты, видна с любой стороны. */
    private static void line(Batch t, Matrix4f m, double x0, double y0, double z0,
                             double x1, double y1, double z1, double w, int col) {
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) return;
        dx /= len;
        dy /= len;
        dz /= len;
        double ux = 0, uy = 1, uz = 0;
        if (Math.abs(dy) > 0.99) {
            ux = 1;
            uy = 0;
        }
        double ax = dy * uz - dz * uy, ay = dz * ux - dx * uz, az = dx * uy - dy * ux;
        double al = Math.sqrt(ax * ax + ay * ay + az * az);
        ax /= al;
        ay /= al;
        az /= al;
        double bx = dy * az - dz * ay, by = dz * ax - dx * az, bz = dx * ay - dy * ax;
        ax *= w;
        ay *= w;
        az *= w;
        bx *= w;
        by *= w;
        bz *= w;
        quad(t, m, x0 - ax, y0 - ay, z0 - az, x0 + ax, y0 + ay, z0 + az,
                x1 + ax, y1 + ay, z1 + az, x1 - ax, y1 - ay, z1 - az, col);
        quad(t, m, x0 - bx, y0 - by, z0 - bz, x0 + bx, y0 + by, z0 + bz,
                x1 + bx, y1 + by, z1 + bz, x1 - bx, y1 - by, z1 - bz, col);
    }
}
