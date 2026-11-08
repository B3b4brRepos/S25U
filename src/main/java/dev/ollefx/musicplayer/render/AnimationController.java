package dev.ollefx.musicplayer.render;

import dev.ollefx.musicplayer.MusicPlayerMod;
import dev.ollefx.musicplayer.config.MusicConfig;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central animation engine.  All animated values run through here.
 *
 * Animation types:
 *   - AnimatedFloat  : linear lerp or cubic easing toward a target
 *   - SpringFloat    : physically-based spring (stiffness + damping)
 *
 * Usage:
 *   AnimatedFloat a = controller.newFloat(0f);
 *   a.animateTo(1f, 0.3f);           // 300 ms easing
 *   // in render: float v = a.get();
 *
 * All values update in tick() — called once per client tick.
 * get() interpolates between tick values using partialTick for smoothness.
 */
public class AnimationController {

    // All live animations — CopyOnWrite so render thread reads are safe
    private final CopyOnWriteArrayList<AnimatedFloat> floats = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<SpringFloat>   springs = new CopyOnWriteArrayList<>();

    private long lastNanos = System.nanoTime();
    private float intensity = 1.0f; // from config — 0 = instant, 1 = full

    public void setIntensity(float v) { intensity = Math.max(0f, Math.min(1f, v)); }

    // ── Tick ─────────────────────────────────────────────────────────────────

    public void tick(Minecraft mc) {
        long nowNanos = System.nanoTime();
        float dt = (nowNanos - lastNanos) / 1_000_000_000f; // delta-time in seconds
        dt = Math.min(dt, 0.1f);                            // clamp: cap at 100ms
        lastNanos = nowNanos;

        // If reduced motion, skip animations and snap values
        boolean snap = intensity < 0.01f;

        for (AnimatedFloat a : floats) a.update(dt, intensity, snap);
        for (SpringFloat  s : springs) s.update(dt, intensity, snap);
    }

    // ── Factories ─────────────────────────────────────────────────────────────

    public AnimatedFloat newFloat(float initial) {
        AnimatedFloat a = new AnimatedFloat(initial);
        floats.add(a);
        return a;
    }

    public SpringFloat newSpring(float initial, float stiffness, float damping) {
        SpringFloat s = new SpringFloat(initial, stiffness, damping);
        springs.add(s);
        return s;
    }

    public void remove(AnimatedFloat a)  { floats.remove(a); }
    public void remove(SpringFloat s)    { springs.remove(s); }

    // ── AnimatedFloat ─────────────────────────────────────────────────────────

    public static final class AnimatedFloat {
        private float current;
        private float target;
        private float duration;   // seconds
        private float elapsed;
        private EasingType easing = EasingType.EASE_OUT_CUBIC;

        AnimatedFloat(float initial) { current = target = initial; }

        public void set(float v) {
            startVal = current = target = v;
            elapsed  = duration;
        }

        public void animateTo(float t, float durationSec) {
            animateTo(t, durationSec, EasingType.EASE_OUT_CUBIC);
        }

        public void animateTo(float t, float durationSec, EasingType e) {
            startVal = current;   // capture current as start each time
            target   = t;
            duration = durationSec;
            elapsed  = 0;
            easing   = e;
        }

        void update(float dt, float intensity, boolean snap) {
            if (snap) { current = startVal = target; return; }
            float effectiveDur = Math.max(0.001f, duration / Math.max(0.01f, intensity));
            if (elapsed < effectiveDur) {
                elapsed += dt;
                float t = Math.min(1f, elapsed / effectiveDur);
                current = lerp(startVal, target, ease(t, easing));
            } else {
                current = target;
            }
        }

        private float startVal;
        private float start() { return startVal; }

        public float get() { return current; }
        public float getTarget() { return target; }
        public boolean isDone() { return current == target; }
    }

    // ── SpringFloat ───────────────────────────────────────────────────────────

    public static final class SpringFloat {
        private float value;
        private float velocity;
        private float target;
        private final float stiffness; // e.g. 200
        private final float damping;   // e.g. 20

        SpringFloat(float initial, float stiffness, float damping) {
            value = target = initial;
            this.stiffness = stiffness;
            this.damping   = damping;
        }

        public void setTarget(float t) { target = t; }
        public void set(float v)       { value = target = v; velocity = 0; }

        void update(float dt, float intensity, boolean snap) {
            if (snap) { value = target; velocity = 0; return; }
            float scaledDt = dt * intensity;
            // Verlet integration: F = -k*x - d*v
            float force = -stiffness * (value - target) - damping * velocity;
            velocity += force * scaledDt;
            value    += velocity * scaledDt;
        }

        public float get()  { return value; }
        public float vel()  { return velocity; }
    }

    // ── Easing ────────────────────────────────────────────────────────────────

    public enum EasingType {
        LINEAR, EASE_IN_QUAD, EASE_OUT_QUAD,
        EASE_OUT_CUBIC, EASE_IN_OUT_CUBIC,
        EASE_OUT_BACK, EASE_OUT_ELASTIC
    }

    public static float ease(float t, EasingType type) {
        return switch (type) {
            case LINEAR          -> t;
            case EASE_IN_QUAD    -> t * t;
            case EASE_OUT_QUAD   -> 1 - (1 - t) * (1 - t);
            case EASE_OUT_CUBIC  -> 1 - (float)Math.pow(1 - t, 3);
            case EASE_IN_OUT_CUBIC -> t < 0.5f
                    ? 4 * t * t * t
                    : 1 - (float)Math.pow(-2 * t + 2, 3) / 2;
            case EASE_OUT_BACK   -> {
                float c1 = 1.70158f, c3 = c1 + 1;
                yield 1 + c3 * (float)Math.pow(t - 1, 3) + c1 * (float)Math.pow(t - 1, 2);
            }
            case EASE_OUT_ELASTIC -> {
                if (t == 0 || t == 1) yield t;
                float c4 = (float)(2 * Math.PI / 3);
                yield (float)(Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * c4) + 1);
            }
        };
    }

    public static float lerp(float a, float b, float t) { return a + (b - a) * t; }
}
