package dev.ollefx.musicplayer.audio;

import dev.ollefx.musicplayer.MusicPlayerMod;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import javax.sound.sampled.*;
import java.util.Arrays;

/**
 * Environment audio processor — applies per-biome reverb/filter to the music stream.
 *
 * DSP chain (matches Dango's EnvironmentAudio logic):
 *   Input PCM → Comb filters (4 parallel) → Allpass filters (2 serial) → Mix wet/dry → Output
 *
 * Biome detection (tick-driven, every 20 ticks):
 *   - Underwater  → heavy low-pass + heavy wet
 *   - Cave/no-sky → medium reverb + slight low-pass
 *   - Indoors     → slight reverb + light muffle
 *   - Rain        → slight high-cut
 *
 * This runs on the audio decode thread — PCM float arrays in, same arrays out.
 * All filter state is float[] for GC-free hot path.
 */
public class EnvironmentAudio {

    // ── Dango's exact constants ────────────────────────────────────────────────
    private static final int   SAMPLE_RATE = 44100;
    private static final int   CHANNELS    = 2;
    private static final int   COMB_COUNT  = 4;
    private static final int   ALLPASS_COUNT = 2;

    // Comb delay times in ms — Schroeder reverberator tuning
    private static final int[] COMB_MS    = { 29, 37, 41, 43 };
    private static final int[] ALLPASS_MS = { 5, 11 };

    // ── Biome targets (updated from game thread, applied on audio thread) ─────
    private volatile float targetCutoff = 1.0f;  // LP coefficient [0,1], 1 = bypass
    private volatile float targetWet    = 0.0f;  // reverb wet mix [0,1]
    private volatile float targetGain   = 1.0f;  // output gain

    // ── Enable flags (from config/settings) ───────────────────────────────────
    public volatile boolean enabled  = true;
    public volatile boolean water    = true;
    public volatile boolean cave     = true;
    public volatile boolean indoor   = true;
    public volatile boolean rain     = true;
    public volatile float   strength = 1.0f;  // 0–1 effect multiplier

    // ── DSP state ─────────────────────────────────────────────────────────────
    private final float[][] combBuf;
    private final int[]     combPos;
    private final float[]   combStore;    // per-comb feedback store
    private final float[][] allpassBuf;
    private final int[]     allpassPos;

    // Current filter values (smoothed toward targets)
    private float curCutoff  = 1.0f;
    private float curWet     = 0.0f;
    private float curGain    = 1.0f;

    // LP state per channel
    private final float[] lpState = new float[CHANNELS];

    // Biome tick
    private int    ticksSinceBiomeCheck = 0;
    private static final int BIOME_CHECK_INTERVAL = 20;

    public EnvironmentAudio() {
        // Allocate comb delay buffers
        combBuf   = new float[COMB_COUNT][];
        combPos   = new int[COMB_COUNT];
        combStore = new float[COMB_COUNT];
        for (int i = 0; i < COMB_COUNT; i++) {
            int len = (int)(COMB_MS[i] * SAMPLE_RATE / 1000.0) * CHANNELS;
            combBuf[i] = new float[Math.max(1, len)];
        }
        // Allpass delay buffers
        allpassBuf = new float[ALLPASS_COUNT][];
        allpassPos = new int[ALLPASS_COUNT];
        for (int i = 0; i < ALLPASS_COUNT; i++) {
            int len = (int)(ALLPASS_MS[i] * SAMPLE_RATE / 1000.0) * CHANNELS;
            allpassBuf[i] = new float[Math.max(1, len)];
        }
    }

    // ── Game thread: biome update ─────────────────────────────────────────────

    /** Called from client tick — detects environment and sets targets. */
    public void tick() {
        if (!enabled) { targetWet = 0; targetCutoff = 1; targetGain = 1; return; }

        ticksSinceBiomeCheck++;
        if (ticksSinceBiomeCheck < BIOME_CHECK_INTERVAL) return;
        ticksSinceBiomeCheck = 0;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        Level level = mc.level;
        BlockPos pos = mc.player.blockPosition();

        boolean isUnderwater = mc.player.isUnderWater();
        boolean isRaining    = rain && level.isRainingAt(pos);
        boolean hasSky       = level.canSeeSky(pos.above());
        boolean isIndoor     = !hasSky && !isUnderwater;

        float newCutoff = 1.0f;
        float newWet    = 0.0f;
        float newGain   = 1.0f;

        if (isUnderwater && water) {
            newCutoff = 0.08f;   // heavy low-pass: muffled
            newWet    = 0.55f;   // lots of reverb
            newGain   = 0.75f;
        } else if (!hasSky && cave) {
            newCutoff = 0.6f;    // slight muffle
            newWet    = 0.30f;   // cave echo
            newGain   = 0.95f;
        } else if (isIndoor && indoor) {
            newCutoff = 0.82f;
            newWet    = 0.12f;
            newGain   = 1.0f;
        } else if (isRaining) {
            newCutoff = 0.88f;
            newWet    = 0.05f;
            newGain   = 0.98f;
        }

        // Apply strength multiplier
        newWet = newWet * strength;
        newCutoff = 1.0f - (1.0f - newCutoff) * strength;

        targetCutoff = newCutoff;
        targetWet    = newWet;
        targetGain   = newGain;
    }

    // ── Audio thread: DSP processing ──────────────────────────────────────────

    /**
     * Process a buffer of interleaved stereo PCM samples in-place.
     * Called from AudioBackend's decode thread — must be allocation-free.
     *
     * @param pcm   interleaved stereo float PCM [-1,1]
     * @param count samples to process (must be even: L,R pairs)
     */
    public void process(float[] pcm, int count) {
        if (!enabled || (targetWet < 0.001f && Math.abs(curWet) < 0.001f && Math.abs(1.0f - curCutoff) < 0.001f)) {
            // Bypass path: no allocation, early exit
            if (Math.abs(curGain - 1.0f) > 0.001f) {
                for (int i = 0; i < count; i++) pcm[i] *= curGain;
            }
            return;
        }

        // Smooth parameters toward targets (rate ~50ms at 44100 sr)
        float rate = 0.001f;
        curCutoff = curCutoff + (targetCutoff - curCutoff) * rate * 20;
        curWet    = curWet    + (targetWet    - curWet)    * rate * 20;
        curGain   = curGain   + (targetGain   - curGain)   * rate * 20;

        for (int n = 0; n < count; n += CHANNELS) {
            // Per-channel processing
            for (int ch = 0; ch < CHANNELS; ch++) {
                float x = pcm[n + ch];

                // Low-pass filter: y[n] = (1-c)*x + c*y[n-1], where c = 1-cutoff
                float lpCoeff = 1.0f - curCutoff;
                lpState[ch] = (1.0f - lpCoeff) * x + lpCoeff * lpState[ch];
                x = lpState[ch];

                // Comb filter bank (4 parallel combs, feedback coefficient 0.84)
                float reverbOut = 0;
                for (int ci = 0; ci < COMB_COUNT; ci++) {
                    float[] buf = combBuf[ci];
                    int len = buf.length;
                    // Channel interleave: advance by CHANNELS per sample
                    int idx = (combPos[ci] + ch) % len;
                    float delayed = buf[idx];
                    float feedback = 0.84f;
                    buf[idx] = x + delayed * feedback;
                    combPos[ci] = (combPos[ci] + CHANNELS) % len;
                    reverbOut += delayed;
                }
                reverbOut /= COMB_COUNT;

                // Allpass filters (2 serial)
                for (int ai = 0; ai < ALLPASS_COUNT; ai++) {
                    float[] buf = allpassBuf[ai];
                    int len = buf.length;
                    int idx = (allpassPos[ai] + ch) % len;
                    float delayed = buf[idx];
                    float g = 0.7f;
                    buf[idx] = reverbOut + g * delayed;
                    allpassPos[ai] = (allpassPos[ai] + CHANNELS) % len;
                    reverbOut = delayed - g * reverbOut;
                }

                // Mix dry + wet
                pcm[n + ch] = (x * (1.0f - curWet) + reverbOut * curWet) * curGain;

                // Safety clamp
                if (pcm[n + ch] >  1.0f) pcm[n + ch] =  1.0f;
                if (pcm[n + ch] < -1.0f) pcm[n + ch] = -1.0f;
            }
        }
    }

    /** Reset all filter state (call on seek/track-change to avoid residual reverb tail). */
    public void reset() {
        for (float[] b : combBuf)    Arrays.fill(b, 0f);
        for (float[] b : allpassBuf) Arrays.fill(b, 0f);
        Arrays.fill(combPos,    0);
        Arrays.fill(allpassPos, 0);
        Arrays.fill(lpState,    0f);
        curWet = 0;
    }
}
