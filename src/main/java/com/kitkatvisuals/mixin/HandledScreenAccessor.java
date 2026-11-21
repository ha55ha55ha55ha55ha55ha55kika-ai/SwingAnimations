package com.kitkatvisuals.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Левый верхний угол фона контейнера: нужен, чтобы подсвечивать слоты в экране инвентаря (ItemPickupLogger). */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
    @Accessor("x")
    int kitkat$getX();

    @Accessor("y")
    int kitkat$getY();
}
