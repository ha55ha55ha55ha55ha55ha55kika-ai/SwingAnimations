package com.kitkatvisuals.module;

import net.minecraft.client.MinecraftClient;
import net.minecraft.world.World;

/**
 * NoFog: убирает ванильный туман на расстоянии в обычном мире, Незере и Энде (каждое измерение включается отдельно).
 * Туман в воде и лаве остаётся за ClearLiquids, слепота и тьма туман сохраняют.
 * Подмену делает BackgroundRendererMixin (читает fogOff()).
 */
public final class NoFogModule extends Module {
    private final Setting.Bool overworld = add(new Setting.Bool("overworld", "Overworld", true));
    private final Setting.Bool nether = add(new Setting.Bool("nether", "Nether", true));
    private final Setting.Bool end = add(new Setting.Bool("end", "The End", true));

    public NoFogModule() {
        super("no_fog", "NoFog", "render");
    }

    /** true: в текущем измерении туман на расстоянии нужно убрать. */
    public boolean fogOff() {
        if (!isEnabled()) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return false;
        var dim = mc.world.getRegistryKey();
        if (dim == World.OVERWORLD) return overworld.value;
        if (dim == World.NETHER) return nether.value;
        if (dim == World.END) return end.value;
        return true; // моды с другими измерениями
    }
}
