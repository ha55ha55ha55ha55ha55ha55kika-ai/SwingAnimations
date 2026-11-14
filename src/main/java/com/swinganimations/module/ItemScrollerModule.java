package com.swinganimations.module;

import net.minecraft.screen.slot.Slot;

/** Shift + зажатая ЛКМ и проводка мыши по слотам: быстро перекладывает стаки (как shift-клик по каждому). */
public final class ItemScrollerModule extends Module {
    private final Setting.Num delay = add(new Setting.Num("delay", "Delay (ms)", 40f, 0f, 300f, 10f));

    /** Состояние текущего перетаскивания (используется миксином HandledScreen). */
    public Slot lastSlot;
    public long lastTime;

    public ItemScrollerModule() {
        super("item_scroller", "ItemScroller", "player");
    }

    public long delayMs() { return (long) delay.value; }

    @Override
    public void onDisable() {
        lastSlot = null;
    }
}
