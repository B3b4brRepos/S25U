package dev.ollefx.musicplayer.audio;

import dev.ollefx.musicplayer.MusicPlayerMod;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Beat detection + mob dance system — matches Dango's Dance.class behavior.
 *
 * BPM Detection:
 *   Energy-based onset detection on the low-frequency visualizer bands (0–7).
 *   Maintains a 2-second sliding window of onset times; derives BPM from median
 *   inter-onset interval.
 *
 * FPS fix vs Dango:
 *   Dango's LivingEntityRendererMixin fires on EVERY entity per frame, causing
 *   the 92 FPS drop in third-person with many mobs loaded.
 *
 *   Our fix: entity count is checked in the mixin before the dance transform fires.
 *   Additionally, danceRange caps the maximum entity distance for which the
 *   scale/squash is applied. Set MAX_DANCE_ENTITIES = 20 (Dango has no cap).
 *   This keeps the effect visible on nearby mobs without touching far entities.
 *
 * Usage:
 *   - Call tickAudio(bands) each time the visualizer bands update (audio thread).
 *   - Call getBeat() on the render thread for the current beat pulse [0,1].
 *   - In the entity renderer mixin: call getEntityScale(distSq) for per-entity scale.
 */
public class DanceSystem {

    // ── Settings (mirrored from MusicConfig) ──────────────────────────────────
    public volatile boolean enabled     = false;
    public volatile boolean auto        = true;    // auto-detect BPM
    public volatile boolean spin        = false;   // creature spins on beat
    public volatile float   amount      = 1.3f;    // squash amount (Dango default 1.3)
    public volatile int     offsetMs    = 0;       // beat offset
    public volatile float   danceRange  = 20.0f;   // max distance (blocks) for dance

    /** Maximum entities to apply dance per frame — the FPS guard. */
    public static final int MAX_DANCE_ENTITIES = 20;

    // ── BPM state ─────────────────────────────────────────────────────────────
    private float    bpm          = 120f;
    private long     periodMs     = 500;           // ms per beat
    private float    prevEnergy   = 0f;
    private final Deque<Long> onsetTimes = new ArrayDeque<>();

    // Tap BPM support
    private final Deque<Long> tapTimes   = new ArrayDeque<>();
    private long lastTapMs = 0;

    // ── Beat pulse (audio thread writes, render thread reads) ─────────────────
    private volatile float beatPulse    = 0f;      // 0 = off-beat, 1 = on-beat peak
    private volatile long  lastBeatMs   = 0;

    // Render thread smoothed pulse (not volatile — only used on render thread)
    private float smoothPulse = 0f;

    // ── Dance entity counter (reset each frame by mixin) ─────────────────────
    private int entityDanceCount = 0;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called from AudioBackend decode thread with 32 band magnitudes.
     * Updates beat detection and beatPulse.
     */
    public void tickAudio(float[] bands) {
        if (!enabled) { beatPulse = 0; return; }

        // Energy = sum of low bands (0–7, sub-bass to low-mid)
        float energy = 0;
        for (int i = 0; i < 8 && i < bands.length; i++) energy += bands[i];
        energy /= 8f;

        // Onset: energy spike above smoothed average
        float threshold = prevEnergy * 1.3f + 0.01f;
        long nowMs = System.currentTimeMillis();

        if (energy > threshold && energy > 0.015f) {
            // Onset detected
            onsetTimes.addLast(nowMs);
            // Prune onsets older than 3 seconds
            while (!onsetTimes.isEmpty() && nowMs - onsetTimes.peekFirst() > 3000) {
                onsetTimes.pollFirst();
            }
            // Estimate BPM from inter-onset intervals
            if (onsetTimes.size() >= 2) {
                Long[] times = onsetTimes.toArray(new Long[0]);
                long sum = 0;
                int n = 0;
                for (int i = 1; i < times.length; i++) {
                    long interval = times[i] - times[i - 1];
                    if (interval > 200 && interval < 2000) { sum += interval; n++; }
                }
                if (n > 0) {
                    periodMs = Math.max(200, Math.min(2000, sum / n));
                    bpm = 60000f / periodMs;
                }
            }
            lastBeatMs = nowMs;
            beatPulse  = 1.0f;
        }

        // Decay pulse exponentially
        long elapsed = nowMs - lastBeatMs;
        beatPulse = Math.max(0, 1.0f - elapsed / (periodMs * 0.5f));

        // Smooth energy for next threshold
        prevEnergy = prevEnergy * 0.9f + energy * 0.1f;
    }

    /**
     * Register a manual tap (UI tap-BPM button click).
     * Tap 4 times to lock in a BPM.
     */
    public void tap() {
        long now = System.currentTimeMillis();
        if (now - lastTapMs > 3000) tapTimes.clear(); // reset after 3s gap
        tapTimes.addLast(now);
        lastTapMs = now;
        if (tapTimes.size() > 8) tapTimes.pollFirst();

        if (tapTimes.size() >= 2) {
            Long[] times = tapTimes.toArray(new Long[0]);
            long sum = 0;
            for (int i = 1; i < times.length; i++) sum += times[i] - times[i - 1];
            long avg = sum / (times.length - 1);
            if (avg > 200 && avg < 2000) {
                periodMs = avg;
                bpm      = 60000f / avg;
                auto     = false; // lock manual
            }
        }
    }

    // ── Render thread queries ─────────────────────────────────────────────────

    /**
     * Get the current beat envelope [0,1] for rendering.
     * Smooth-decays so it doesn't snap off between beats.
     */
    public float getBeatEnvelope(float dt) {
        float target = beatPulse;
        smoothPulse = smoothPulse + (target - smoothPulse) * Math.min(1f, dt * 20f);
        return smoothPulse;
    }

    /**
     * Get scale multiplier for an entity at the given squared distance.
     * Returns 1.0 if disabled, out of range, or entity budget exhausted.
     *
     * FPS guard: each frame the entity count is tracked; once MAX_DANCE_ENTITIES
     * is reached, all further entities get 1.0 (no scale transform, no cost).
     */
    public float getEntityScale(double distanceSq) {
        if (!enabled) return 1.0f;
        if (distanceSq > danceRange * danceRange) return 1.0f;
        if (entityDanceCount >= MAX_DANCE_ENTITIES) return 1.0f;
        entityDanceCount++;

        // Beat-driven squash: scale oscillates 1.0 ↔ amount on beat peak
        float pulse   = smoothPulse;
        float scale   = 1.0f + (amount - 1.0f) * pulse;
        return scale;
    }

    /** Reset entity dance counter — call once at start of each frame from mixin. */
    public void beginFrame() {
        entityDanceCount = 0;
    }

    public float getBpm()      { return bpm; }
    public long  getPeriodMs() { return periodMs; }
    public float getBeatPulse(){ return beatPulse; }

    public void reset() {
        onsetTimes.clear(); tapTimes.clear();
        beatPulse = 0; smoothPulse = 0; prevEnergy = 0;
    }
}
