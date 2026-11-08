package dev.ollefx.musicplayer.render;

import dev.ollefx.musicplayer.audio.MusicManager;
import dev.ollefx.musicplayer.config.MusicConfig;
import dev.ollefx.musicplayer.lyrics.LyricsManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 2D HUD lyrics display — shows current, previous, and upcoming lines
 * centered at a configurable screen position.
 *
 * Layout (bottom-of-screen centered):
 *   prev lyric  (dim, small)
 *   CURRENT LYRIC  (bright, slightly larger)
 *   next lyric  (dim, small)
 *
 * Uses AnimatedFloat for smooth scroll and alpha transitions.
 */
public class HudLyricsRenderer {

    private static final int MAX_LINE_W = 400;

    private final AnimationController.AnimatedFloat scrollOffset;
    private final AnimationController.AnimatedFloat alpha;

    private final AnimationController controller;
    private final LyricsManager       lyrics;
    private final MusicManager        music;
    private final MusicConfig         config;

    public HudLyricsRenderer(AnimationController controller,
                              LyricsManager lyrics,
                              MusicManager music,
                              MusicConfig config) {
        this.controller = controller;
        this.lyrics     = lyrics;
        this.music      = music;
        this.config     = config;
        this.scrollOffset = controller.newFloat(0f);
        this.alpha        = controller.newFloat(0f);
    }

    public void render(GuiGraphicsExtractor gfx, Minecraft mc, float partialTick) {
        boolean playing = music.getState() == MusicManager.State.PLAYING;
        boolean available = lyrics.isAvailable();

        alpha.animateTo((playing && available) ? config.hudLyricsOpacity : 0f, 0.4f);
        float a = alpha.get();
        if (a < 0.01f) return;

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int font    = mc.font.lineHeight;

        // Position from config (0–1 relative)
        int cx = (int)(screenW * config.hudLyricsX);
        int cy = (int)(screenH * config.hudLyricsY);

        LyricsManager.LyricsLine cur  = lyrics.getCurrentLine();
        LyricsManager.LyricsLine prev = lyrics.getPreviousLine();
        LyricsManager.LyricsLine next = lyrics.getNextLine();

        String curText  = cur  != null ? cur.text()  : "";
        String prevText = prev != null ? prev.text() : "";
        String nextText = next != null ? next.text() : "";

        int hallowColor  = LiquidGlassRenderer.withAlpha(
                config.halloweenTheme ? 0xFFFF8C00 : config.hudCurrentColor,
                (int)(255 * a));
        int dimColor     = LiquidGlassRenderer.withAlpha(0xFFFFFFFF, (int)(180 * a));
        int dimmerColor  = LiquidGlassRenderer.withAlpha(0xFFFFFFFF, (int)(110 * a));

        int lineSpacing  = font + 6;
        float scroll     = scrollOffset.get();

        // Semi-transparent background behind lyrics
        if (!curText.isEmpty()) {
            int bgW  = Math.min(MAX_LINE_W, mc.font.width(curText) + 24);
            int bgH  = lineSpacing * 3 + 8;
            int bgX  = cx - bgW / 2;
            int bgY  = cy - lineSpacing - 4;
            LiquidGlassRenderer.fill(gfx, bgX, bgY, bgW, bgH,
                    LiquidGlassRenderer.withAlpha(0x000000,
                            (int)(130 * a)));
        }

        // Previous line
        if (!prevText.isEmpty()) {
            String clipped = clip(mc, prevText, MAX_LINE_W);
            int tw = mc.font.width(clipped);
            gfx.drawString(mc.font, clipped,
                    cx - tw / 2,
                    (int)(cy - lineSpacing + scroll) - font,
                    dimmerColor, false);
        }

        // Current line
        if (!curText.isEmpty()) {
            String clipped = clip(mc, curText, MAX_LINE_W);
            int tw = mc.font.width(clipped);
            // Glow: draw 4 times with tiny offsets then solid on top
            if (config.halloweenTheme) {
                int glow = LiquidGlassRenderer.withAlpha(0xFFFF4400, (int)(60 * a));
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;
                    gfx.drawString(mc.font, clipped,
                            cx - tw / 2 + dx, (int)(cy + scroll) + dy, glow, false);
                }
            }
            gfx.drawStringShadow(mc.font, clipped, cx - tw / 2, (int)(cy + scroll), hallowColor);
        }

        // Next line
        if (!nextText.isEmpty()) {
            String clipped = clip(mc, nextText, MAX_LINE_W);
            int tw = mc.font.width(clipped);
            gfx.drawString(mc.font, clipped,
                    cx - tw / 2,
                    (int)(cy + lineSpacing + scroll) + font,
                    dimColor, false);
        }
    }

    private static String clip(Minecraft mc, String text, int maxW) {
        if (mc.font.width(text) <= maxW) return text;
        return mc.font.plainSubstrByWidth(text, maxW - mc.font.width("…")) + "…";
    }
}
