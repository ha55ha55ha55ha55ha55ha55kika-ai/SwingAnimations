package com.swinganimations.animation;

import com.swinganimations.config.HandProfile;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Arm;

/** Чтобы добавить режим: новая константа + ключ swinganimations.mode.<имя_в_нижнем_регистре> в lang-файлах. */
public enum SwingMode implements SwingAnimation {

    /** Копия ванильной математики вращения (в игре для VANILLA вызывается оригинальный метод). */
    VANILLA {
        @Override
        public void apply(MatrixStack m, float p, Arm arm, HandProfile s) {
            float side = Xf.sign(arm);
            float f = (float) Math.sin(p * p * Math.PI);
            float g = (float) Math.sin(Math.sqrt(p) * Math.PI);
            Xf.rotY(m, side * (45f + f * -20f));
            Xf.rotZ(m, side * g * -20f);
            Xf.rotX(m, g * -80f);
            Xf.rotY(m, side * -45f);
        }
    },

    BLOCK_HIT {
        @Override
        public void apply(MatrixStack m, float p, Arm arm, HandProfile s) {
            float side = Xf.sign(arm);
            float a = Xf.bell(p);
            float b = Xf.clamp01(a * 1.8f); // быстро входим в позу блока и держим
            Xf.translate(m, side * -0.10f * b, 0.04f * b, 0.02f * b);
            Xf.rotY(m, side * 28f * b);
            Xf.rotX(m, -60f * b - 20f * a);
            Xf.rotZ(m, side * -18f * b);
        }
    },

    SMOOTH {
        @Override
        public void apply(MatrixStack m, float p, Arm arm, HandProfile s) {
            float side = Xf.sign(arm);
            float e = Xf.easeOutCubic(p);
            float g = Xf.bell(e);
            Xf.translate(m, side * -0.12f * g, 0.04f * g, -0.08f * g);
            Xf.rotY(m, side * -25f * g);
            Xf.rotZ(m, side * -22f * g);
            Xf.rotX(m, -60f * g);
        }
    },

    SLASH {
        @Override
        public void apply(MatrixStack m, float p, Arm arm, HandProfile s) {
            float side = Xf.sign(arm);
            float a = Xf.bell(p);
            float sweep = (Xf.smooth(p) - 0.5f) * 2f; // -1..1
            Xf.translate(m, 0f, 0f, -0.08f * a);
            Xf.rotY(m, side * sweep * -55f);
            Xf.rotZ(m, side * -40f * a);
            Xf.rotX(m, -25f * a);
        }
    },

    SPIN {
        @Override
        public void apply(MatrixStack m, float p, Arm arm, HandProfile s) {
            float side = Xf.sign(arm);
            float a = Xf.bell(p);
            Xf.translate(m, 0f, 0.02f * a, -0.12f * a);
            Xf.rotX(m, -25f * a);
            Xf.rotZ(m, side * 360f * Xf.smooth(p)); // полный оборот вокруг оси предмета
        }
    },

    PUNCH {
        @Override
        public void apply(MatrixStack m, float p, Arm arm, HandProfile s) {
            float side = Xf.sign(arm);
            float q = Xf.pulse(p);
            Xf.translate(m, side * -0.04f * q, 0.03f * q, -0.55f * q);
            Xf.rotX(m, -12f * q);
        }
    },

    SWONG {
        @Override
        public void apply(MatrixStack m, float p, Arm arm, HandProfile s) {
            float side = Xf.sign(arm);
            float angle;
            if (p < 0.3f) {
                angle = Xf.lerp(0f, 28f, Xf.smooth(p / 0.3f));                     // замах назад
            } else if (p < 0.55f) {
                angle = Xf.lerp(28f, -80f, Xf.easeOutCubic((p - 0.3f) / 0.25f));   // быстрый удар
            } else {
                angle = Xf.lerp(-80f, 0f, Xf.smooth((p - 0.55f) / 0.45f));         // возврат
            }
            float strike = p < 0.3f ? 0f : Xf.bell((p - 0.3f) / 0.7f);
            float windup = Xf.bell(Math.min(1f, p / 0.6f));
            Xf.translate(m, side * -0.05f * strike, 0.03f * strike, 0.08f * windup - 0.22f * strike);
            Xf.rotX(m, angle);
            Xf.rotZ(m, side * -18f * Xf.bell(p));
            Xf.rotY(m, side * -10f * Xf.bell(p));
        }
    },

    STAB {
        @Override
        public void apply(MatrixStack m, float p, Arm arm, HandProfile s) {
            float side = Xf.sign(arm);
            float q = Xf.pulse(p);
            Xf.translate(m, side * -0.02f * q, -0.02f * q, -0.65f * q);
            Xf.rotX(m, -78f * q); // остриём вперёд
            Xf.rotY(m, side * 10f * q);
        }
    },

    EXHIBITION {
        @Override
        public void apply(MatrixStack m, float p, Arm arm, HandProfile s) {
            float side = Xf.sign(arm);
            float a = Xf.bell(p);
            float wave2 = (float) Math.sin(p * Math.PI * 2.0);
            float wave3 = (float) Math.sin(p * Math.PI * 3.0);
            Xf.translate(m, side * -0.08f * a, -0.05f * a + 0.03f * wave2, 0f);
            Xf.rotX(m, -35f * a);
            Xf.rotY(m, side * wave2 * 22f);
            Xf.rotZ(m, side * (wave3 * 18f - 20f * a));
        }
    };

    public String translationKey() {
        return "swinganimations.mode." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
