package dev.ollefx.musicplayer.render;

import dev.ollefx.musicplayer.MusicPlayerMod;
import dev.ollefx.musicplayer.audio.MusicManager;
import dev.ollefx.musicplayer.config.MusicConfig;
import dev.ollefx.musicplayer.lyrics.LyricsManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Renders the Dynamic Island-style lyrics pill at the top-center of the HUD.
 *
 * Layout:
 *   ┌──────────────────────────────────────────────┐  ← top of screen, y = 8
 *   │  ◉ ♫  "current lyric line here..."           │
 *   └──────────────────────────────────────────────┘
 *
 * Animation:
 *   - Width lerps between compact (mini-player) and expanded (lyric line) states.
 *   - Lyric transitions cycle through the configured LyricTransition style.
 *   - Spring animation on width change, opacity fade on track change.
 *
 * Interaction:
 *   - mouseClicked() expands/collapses the pill.
 *   - Expanded mode shows previous/current/next lines.
 */
public class DynamicLyricsPill {

    private static final int PILL_Y          = 8;
    private static final int PILL_H          = 26;
    private static final int PILL_H_EXPANDED = 72;
    private static final int PILL_MIN_W      = 120;
    private static final int PILL_MAX_W      = 480;
    private static final int MUSIC_INDICATOR_W = 24;
    private static final int PADDING_H      = 10;
    private static final int PADDING_V      = 5;
    private static final int TEXT_COLOR      = 0xFFFFFFFF;
    private static final int TEXT_COLOR_DIM  = 0xAAFFFFFF;
    private static final int TEXT_COLOR_PREV = 0x66FFFFFF;

    // Animation state
    private final AnimationController.SpringFloat widthSpring;
    private final AnimationController.AnimatedFloat alphaAnim;
    private final AnimationController.AnimatedFloat lyricOffset;   // for slide-up transition
    private final AnimationController.AnimatedFloat lyricBlur;     // for blur-focus transition
    private final AnimationController.AnimatedFloat expandAnim;    // 0=compact, 1=expanded

    private boolean expanded = false;

    // Lyric state — updated in tick()
    private String displayLine = "";
    private String prevLine    = "";
    private String nextLine    = "";
    private String lastLine    = "";   // detect lyric change
    private long   lastChangeMs = 0;

    // Visualizer pulse
    private float[] bands = new float[32];

    private final AnimationController controller;
    private final LyricsManager       lyrics;
    private final MusicManager        music;

    public DynamicLyricsPill(AnimationController controller,
                              LyricsManager lyrics,
                              MusicManager music) {
        this.controller = controller;
        this.lyrics     = lyrics;
        this.music      = music;

        widthSpring = controller.newSpring(PILL_MIN_W, 180f, 22f);
        alphaAnim   = controller.newFloat(0f);
        lyricOffset = controller.newFloat(0f);
        lyricBlur   = controller.newFloat(0f);
        expandAnim  = controller.newFloat(0f);
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    public void tick() {
        boolean playing = music != null && music.getState() == MusicManager.State.PLAYING;
        alphaAnim.animateTo(playing ? 1.0f : 0.0f, 0.4f);

        if (lyrics == null) return;

        LyricsManager.LyricsLine cur  = lyrics.getCurrentLine();
        LyricsManager.LyricsLine prev = lyrics.getPreviousLine();
        LyricsManager.LyricsLine next = lyrics.getNextLine();

        displayLine = cur  != null ? cur.text()  : (music != null && music.getNowPlaying() != null
                        ? music.getNowPlaying().label() : "");
        prevLine    = prev != null ? prev.text() : "";
        nextLine    = next != null ? next.text() : "";

        // Lyric change transition
        if (!displayLine.equals(lastLine)) {
            lastLine      = displayLine;
            lastChangeMs  = System.currentTimeMillis();
            triggerTransition();
        }

        // Width: driven by text length
        Minecraft mc = Minecraft.getInstance();
        if (mc.font != null) {
            int textW = mc.font.width(displayLine) + MUSIC_INDICATOR_W + PADDING_H * 2;
            int targetW = Math.max(PILL_MIN_W, Math.min(PILL_MAX_W, textW));
            widthSpring.setTarget(targetW);
        }

        // Expand animation
        expandAnim.animateTo(expanded ? 1.0f : 0.0f, 0.35f);

        // Visualizer bands
        if (music != null) bands = music.getVisualizerBands();
    }

    // ── Render ────────────────────────────────────────────────────────────────

    public void render(GuiGraphicsExtractor gfx, Minecraft mc, float partialTick) {
        float alpha = alphaAnim.get();
        if (alpha < 0.01f) return;

        boolean hw = MusicPlayerMod.config.halloweenTheme;

        int screenW = mc.getWindow().getGuiScaledWidth();
        int pillW   = (int) widthSpring.get();
        int pillH   = PILL_H + (int)(expandAnim.get() * (PILL_H_EXPANDED - PILL_H));
        int pillX   = (screenW - pillW) / 2;
        int pillY   = PILL_Y;

        // Drop shadow
        LiquidGlassRenderer.fill(gfx, pillX + 2, pillY + 2, pillW, pillH,
                LiquidGlassRenderer.multiplyAlpha(0x80000000, alpha * 0.6f));

        // Pill base — deep black glass
        LiquidGlassRenderer.drawCapsule(gfx, pillX, pillY, pillW, pillH, hw, alpha);

        // Halloween glow pulse: orange/purple border animation
        if (hw) {
            float pulse = 0.5f + 0.5f * (float)Math.sin(System.currentTimeMillis() / 800.0);
            LiquidGlassRenderer.drawBorder(gfx, pillX, pillY, pillW, pillH, 1,
                    LiquidGlassRenderer.multiplyAlpha(LiquidGlassRenderer.HW_BORDER, alpha * 0.5f * pulse));
            LiquidGlassRenderer.drawBorder(gfx, pillX - 1, pillY - 1, pillW + 2, pillH + 2, 1,
                    LiquidGlassRenderer.multiplyAlpha(LiquidGlassRenderer.HW_BORDER2, alpha * 0.3f * (1 - pulse)));
        }

        int textX   = pillX + MUSIC_INDICATOR_W + PADDING_H;
        int centerY = pillY + PILL_H / 2 - mc.font.lineHeight / 2;

        // Music waveform indicator (left side of pill)
        drawMusicIndicator(gfx, pillX + 6, pillY + 6, PILL_H - 12, alpha, hw);

        // Lyric offset for slide-up transition
        float lyricY = lyricOffset.get();

        // Current lyric line
        if (!displayLine.isEmpty()) {
            int maxTextW = pillW - MUSIC_INDICATOR_W - PADDING_H * 2;
            String clipped = mc.font.width(displayLine) > maxTextW
                    ? mc.font.plainSubstrByWidth(displayLine, maxTextW) + "…"
                    : displayLine;

            // Apply blur effect via alpha modulation (approximate)
            float blurAlpha = alpha * (1.0f - lyricBlur.get() * 0.7f);
            int color = LiquidGlassRenderer.withAlpha(TEXT_COLOR,
                    (int)(((TEXT_COLOR >> 24) & 0xFF) * blurAlpha));

            gfx.drawString(mc.font, clipped, textX,
                    (int)(centerY + lyricY), color, false);
        }

        // Expanded mode — previous and next lines
        if (expandAnim.get() > 0.01f) {
            float expT = expandAnim.get();

            // Previous line (above current)
            if (!prevLine.isEmpty()) {
                int color = LiquidGlassRenderer.multiplyAlpha(TEXT_COLOR_PREV, alpha * expT);
                gfx.drawString(mc.font, prevLine, textX,
                        centerY - mc.font.lineHeight - 4, color, false);
            }

            // Next line (below current)
            if (!nextLine.isEmpty()) {
                int color = LiquidGlassRenderer.multiplyAlpha(TEXT_COLOR_DIM, alpha * expT);
                gfx.drawString(mc.font, nextLine, textX,
                        centerY + mc.font.lineHeight + 4, color, false);
            }
        }
    }

    // ── Interaction ───────────────────────────────────────────────────────────

    public boolean mouseClicked(double mx, double my, int button) {
        if (!MusicPlayerMod.config.pillClickThrough) {
            Minecraft mc = Minecraft.getInstance();
            int screenW  = mc.getWindow().getGuiScaledWidth();
            int pillW    = (int) widthSpring.get();
            int pillX    = (screenW - pillW) / 2;
            int pillH    = PILL_H + (int)(expandAnim.get() * (PILL_H_EXPANDED - PILL_H));

            if (mx >= pillX && mx <= pillX + pillW && my >= PILL_Y && my <= PILL_Y + pillH) {
                expanded = !expanded;
                return true;
            }
        }
        return false;
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void triggerTransition() {
        MusicConfig.LyricTransition style = MusicPlayerMod.config.lyricTransition;
        switch (style) {
            case SLIDE_UP -> {
                lyricOffset.set(12f);
                lyricOffset.animateTo(0f, 0.28f, AnimationController.EasingType.EASE_OUT_CUBIC);
            }
            case BLUR_FOCUS -> {
                lyricBlur.set(1f);
                lyricBlur.animateTo(0f, 0.35f, AnimationController.EasingType.EASE_OUT_CUBIC);
            }
            case FADE_SCALE -> {
                alphaAnim.animateTo(0.2f, 0.08f);
                alphaAnim.animateTo(1.0f, 0.2f);
            }
            case SCROLL -> {
                lyricOffset.set(8f);
                lyricOffset.animateTo(0f, 0.22f, AnimationController.EasingType.EASE_OUT_QUAD);
            }
            default -> {} // LETTER_REVEAL, WORD_REVEAL, LIQUID_MORPH — handled via tick
        }
    }

    /** Animated waveform bars representing music playback. */
    private void drawMusicIndicator(GuiGraphicsExtractor gfx,
                                    int x, int y, int h,
                                    float alpha, boolean halloween) {
        int barCount = 5;
        int barW     = 2;
        int barGap   = 2;
        int color    = halloween
                ? LiquidGlassRenderer.multiplyAlpha(LiquidGlassRenderer.HW_BORDER, alpha)
                : LiquidGlassRenderer.withAlpha(0xFFFFFFFF, (int)(alpha * 200));

        boolean playing = music != null && music.getState() == MusicManager.State.PLAYING;

        for (int i = 0; i < barCount; i++) {
            float band = bands.length > i * 6 ? bands[i * 6] : 0f;
            float barH;
            if (playing) {
                // Animated based on visualizer + time offset
                float t = (float)(System.currentTimeMillis() / 300.0 + i * 0.7);
                barH = h * 0.3f + (band * h * 0.5f) + h * 0.15f * (float)Math.sin(t);
            } else {
                barH = h * 0.3f; // static mini bars when paused
            }
            barH = Math.max(2, Math.min(h, barH));
            int bx = x + i * (barW + barGap);
            int by = y + (int)((h - barH) / 2);
            LiquidGlassRenderer.fill(gfx, bx, by, barW, (int)barH, color);
        }
    }
}
