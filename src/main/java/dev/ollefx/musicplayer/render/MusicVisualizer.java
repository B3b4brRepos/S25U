package dev.ollefx.musicplayer.render;

import dev.ollefx.musicplayer.audio.MusicManager;
import dev.ollefx.musicplayer.config.MusicConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Draws the frequency-band music visualizer.
 *
 * Uses real PCM magnitude data from AudioBackend.getVisualizerBands().
 * 32 bands mapped to a bar chart with smooth decay.
 *
 * Halloween mode: orange-to-purple gradient bars.
 * Standard mode:  white-to-blue gradient.
 */
public class MusicVisualizer {

    private static final int BAR_COUNT  = 32;
    private static final float DECAY    = 0.85f;  // per frame decay multiplier

    private final float[] smoothed = new float[BAR_COUNT];
    private final float[] peak     = new float[BAR_COUNT];
    private final float[] peakHold = new float[BAR_COUNT];

    private final MusicManager music;
    private final MusicConfig  config;

    public MusicVisualizer(MusicManager music, MusicConfig config) {
        this.music  = music;
        this.config = config;
    }

    // ── Render ────────────────────────────────────────────────────────────────

    /**
     * Draw visualizer bars inside the given rect.
     *
     * @param gfx    renderer
     * @param x, y  top-left
     * @param w, h  size
     * @param alpha  overall opacity 0–1
     */
    public void render(GuiGraphicsExtractor gfx, int x, int y, int w, int h, float alpha) {
        boolean hw     = config.halloweenTheme;
        float[] bands  = music != null ? music.getVisualizerBands() : new float[BAR_COUNT];
        boolean playing = music != null && music.getState() == MusicManager.State.PLAYING;

        int barCount = Math.min(BAR_COUNT, bands.length);
        int barW     = (w - barCount) / barCount;
        if (barW < 1) barW = 1;
        int gap      = 1;

        for (int i = 0; i < barCount; i++) {
            float raw = playing ? bands[i] : 0f;

            // Smooth: take max of raw and decayed previous
            smoothed[i] = Math.max(raw, smoothed[i] * DECAY);

            // Peak hold
            if (smoothed[i] >= peak[i]) {
                peak[i]     = smoothed[i];
                peakHold[i] = 20; // hold for 20 frames
            } else {
                if (peakHold[i] > 0) peakHold[i]--;
                else peak[i] *= 0.95f;
            }

            int barH = (int)(smoothed[i] * h);
            barH = Math.max(1, Math.min(h, barH));

            int bx = x + i * (barW + gap);
            int by = y + h - barH;

            // Bar color: gradient by height
            float t = (float) i / barCount;
            int barColor = hw
                    ? lerpColor(0xFFFF4400, 0xFF8800FF, t)  // orange → purple
                    : lerpColor(0xFF4488FF, 0xFFFFFFFF, t); // blue → white
            barColor = LiquidGlassRenderer.withAlpha(barColor & 0xFFFFFF,
                    (int)(((barColor >> 24) & 0xFF) * alpha));

            LiquidGlassRenderer.fill(gfx, bx, by, barW, barH, barColor);

            // Peak indicator
            if (peak[i] > 0.01f) {
                int peakY = y + h - (int)(peak[i] * h) - 1;
                int pc    = LiquidGlassRenderer.withAlpha(hw ? 0xFF8800 : 0xFFFFFF,
                        (int)(180 * alpha));
                LiquidGlassRenderer.fill(gfx, bx, peakY, barW, 1, pc);
            }
        }
    }

    private static int lerpColor(int a, int b, float t) {
        int ra = (a >> 16) & 0xFF, ga = (a >> 8) & 0xFF, ba = a & 0xFF, aa = (a >> 24) & 0xFF;
        int rb = (b >> 16) & 0xFF, gb = (b >> 8) & 0xFF, bb = b & 0xFF, ab = (b >> 24) & 0xFF;
        return ((aa + (int)((ab-aa)*t)) << 24)
             | ((ra + (int)((rb-ra)*t)) << 16)
             | ((ga + (int)((gb-ga)*t)) << 8)
             |  (ba + (int)((bb-ba)*t));
    }
}
