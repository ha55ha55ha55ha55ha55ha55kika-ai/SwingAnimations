package com.kitkatvisuals.module;

import net.minecraft.util.math.MathHelper;

/**
 * ClearLiquids: чистая вода и лава. Убирает (или ослабляет) туман под водой и в лаве, синюю пелену воды и огонь на экране.
 * Ползунок прозрачности: 100% = тумана нет совсем, 0% = ванильный туман, значения между ними двигают дальность тумана
 * от ванильной до дальности прорисовки.
 */
public final class ClearLiquidsModule extends Module {
    private final Setting.Bool water = add(new Setting.Bool("water", "Clear water fog", true));
    private final Setting.Bool lava = add(new Setting.Bool("lava", "Clear lava fog", true));
    private final Setting.Bool waterOverlay = add(new Setting.Bool("water_overlay", "Hide water overlay", true));
    private final Setting.Bool fireOverlay = add(new Setting.Bool("fire_overlay", "Hide fire overlay", true));
    private final Setting.Num clarity = add(new Setting.Num("clarity", "Clarity (%)", 100f, 0f, 100f, 1f));

    public ClearLiquidsModule() {
        super("clear_liquids", "ClearLiquids", "render");
    }

    public boolean waterOverlayOff() { return isEnabled() && waterOverlay.value; }

    public boolean fireOverlayOff() { return isEnabled() && fireOverlay.value; }

    /**
     * Новая дальность тумана {start, end} для воды или лавы; null, если туман не трогаем.
     * viewDistance — дальность прорисовки в блоках.
     */
    public float[] fogRange(Object type, float start, float end, float viewDistance) {
        // type: CameraSubmersionType. Пакет класса менялся между версиями, поэтому сравниваем по имени константы.
        String n = type instanceof Enum<?> e ? e.name() : "";
        boolean w = n.equals("WATER") && water.value;
        boolean l = n.equals("LAVA") && lava.value;
        if (!w && !l) return null;
        float c = clarity.value / 100f;
        if (c >= 0.999f) return new float[]{Float.MAX_VALUE, Float.MAX_VALUE}; // как у «пустого» тумана
        return new float[]{MathHelper.lerp(c, start, viewDistance), MathHelper.lerp(c, end, viewDistance)};
    }
}
