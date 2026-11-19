package com.kitkatvisuals.render;

import com.kitkatvisuals.module.ItemPhysicsModule;

/** Анимация ItemPhysics, прикреплённая к состоянию рендера предмета. */
public interface ItemPhysicsHolder {
    ItemPhysicsModule.Anim swa$anim();

    void swa$setAnim(ItemPhysicsModule.Anim a);
}
