package com.kitkatvisuals.render;

/** Добавляется миксином к LivingEntityRenderState: id сущности, чья броня рисуется (ArmorColor: «только я / только другие»). */
public interface ArmorOwnerAccess {
    int kitkat$getOwnerId();

    void kitkat$setOwnerId(int id);
}
