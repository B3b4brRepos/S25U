package dev.ollefx.musicplayer.render;

import dev.ollefx.musicplayer.MusicPlayerMod;
import dev.ollefx.musicplayer.audio.MusicManager;
import dev.ollefx.musicplayer.audio.Track;
import dev.ollefx.musicplayer.config.MusicConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * Song card overlay — the "Now Playing" panel at the top-left of the screen.
 *
 * Layout:
 *   ┌──────┬────────────────────────┐
 *   │ ART  │ Song Title             │
 *   │ 48px │ Artist                 │
 *   └──────┴────────────────────────┘
 *
 * Matches Dango's SongCardModule:
 *   - showCover toggle (MusicConfig.nowPlayingCover)
 *   - scale factor (MusicConfig.songCardScale)
 *   - showWhenPaused (MusicConfig.songCardShowPaused)
 *   - draggable position (songCardPosX / songCardPosY)
 *   - song notify: expands for 3.5s on track change, then collapses to mini
 *
 * Animation: slides in from left on track change, fades out after notifyTime.
 */
public class SongCardRenderer {

    private static final int ART_SIZE   = 48;
    private static final int PAD        = 6;
    private static final int CARD_H     = ART_SIZE + PAD * 2;
    private static final int CARD_W_FULL = 240;
    private static final int CARD_W_MINI = 60;

    private final AnimationController.AnimatedFloat alpha;
    private final AnimationController.SpringFloat   width;
    private final AnimationController.AnimatedFloat slideX;

    private String lastTrackId = "";
    private long   notifyUntilMs = 0;
    private boolean expanded = false;

    public SongCardRenderer(AnimationController ac) {
        alpha  = ac.newFloat(0f);
        width  = ac.newSpring(CARD_W_MINI, 200f, 22f);
        slideX = ac.newFloat(-CARD_W_FULL);
    }

    public void onTrackChanged(Track t) {
        if (t == null) return;
        if (t.id().equals(lastTrackId)) return;
        lastTrackId   = t.id();
        notifyUntilMs = System.currentTimeMillis()
                + (long)(MusicPlayerMod.config.dynamicIslandNotifyTime * 1000);
        expanded = true;
        // Animate in
        slideX.set(-CARD_W_FULL);
        slideX.animateTo(0f, 0.3f, AnimationController.EasingType.EASE_OUT_BACK);
    }

    public void tick() {
        MusicConfig cfg  = MusicPlayerMod.config;
        MusicManager mm  = MusicPlayerMod.musicManager;
        if (mm == null || cfg == null) return;

        boolean playing = mm.getState() == MusicManager.State.PLAYING;
        boolean paused  = mm.getState() == MusicManager.State.PAUSED;
        boolean visible = playing || (paused && cfg.songCardShowPaused);

        alpha.animateTo(visible ? 1f : 0f, 0.4f);

        // Notify expansion: full card for notifyTime, then mini
        if (System.currentTimeMillis() > notifyUntilMs && expanded) {
            expanded = false;
        }
        boolean wantFull = expanded || cfg.dynamicIslandNotify && expanded;
        int targetW = (int)((wantFull ? CARD_W_FULL : CARD_W_MINI) * cfg.songCardScale);
        width.setTarget(targetW);

        // Check for track change
        Track now = mm.getNowPlaying();
        if (now != null) onTrackChanged(now);
    }

    public void render(GuiGraphicsExtractor gfx, Minecraft mc, float pt) {
        float a = alpha.get();
        if (a < 0.01f) return;

        MusicConfig cfg   = MusicPlayerMod.config;
        MusicManager mm   = MusicPlayerMod.musicManager;
        Track now         = mm != null ? mm.getNowPlaying() : null;
        boolean hw        = cfg.halloweenTheme;

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        // Position from config
        int baseX = (int)(screenW * cfg.songCardPosX);
        int baseY = (int)(screenH * cfg.songCardPosY);
        int cardX = baseX + (int)slideX.get();
        int cardY = baseY;
        int cardW = (int)width.get();
        int cardH = (int)(CARD_H * cfg.songCardScale);

        if (cardW < 2) return;

        // Panel background
        LiquidGlassRenderer.drawPanel(gfx, cardX, cardY, cardW, cardH, 6, hw, a);

        // Album art square
        if (cfg.nowPlayingCover) {
            int artX = cardX + PAD;
            int artY = cardY + PAD;
            int artS = (int)(ART_SIZE * cfg.songCardScale);

            // Art placeholder / loaded texture
            Identifier artTex = now != null && MusicPlayerMod.artworkManager != null
                    ? MusicPlayerMod.artworkManager.getOrLoad(
                            now.artworkUrl(),
                            id -> { /* repaint next frame */ })
                    : null;

            if (artTex != null) {
                // Draw registered texture via renderpearl blit
                // MC 26.3: blit(RenderPipeline, Identifier, destX, destY, srcX, srcY, destW, destH, srcW, srcH)
                // MC 26.3 blit: (RenderPipeline, Identifier, destX, destY, srcX(f), srcY(f), destW, destH, srcW, srcH, texW, texH)
                gfx.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                        artTex, artX, artY, 0.0f, 0.0f, artS, artS, artS, artS, artS, artS);
            } else {
                // Colored placeholder
                int bg = hw ? LiquidGlassRenderer.withAlpha(0x2A0040, (int)(220 * a))
                            : LiquidGlassRenderer.withAlpha(0x1A1A2E, (int)(220 * a));
                LiquidGlassRenderer.fill(gfx, artX, artY, artS, artS, bg);
                // Music note
                if (now != null && !now.title().isEmpty()) {
                    String letter = String.valueOf(now.title().charAt(0));
                    int lw = mc.font.width(letter);
                    gfx.drawString(mc.font, letter,
                            artX + (artS - lw) / 2,
                            artY + artS / 2 - mc.font.lineHeight / 2,
                            LiquidGlassRenderer.withAlpha(0xFFFFFF, (int)(180 * a)), false);
                }
            }
        }

        // Text area — only shown when card is wide enough
        int textX     = cardX + PAD + (cfg.nowPlayingCover ? (int)(ART_SIZE * cfg.songCardScale) + PAD : 0);
        int textAreaW = cardW - (textX - cardX) - PAD;
        if (textAreaW > 20 && now != null) {
            String title  = truncate(mc, now.title(),  textAreaW);
            String artist = truncate(mc, now.artist(), textAreaW);
            int titleColor = LiquidGlassRenderer.withAlpha(0xFFFFFF, (int)(230 * a));
            int artColor   = LiquidGlassRenderer.withAlpha(hw ? 0xCC8800 : 0xAAAAAA, (int)(180 * a));
            int midY = cardY + cardH / 2;
            gfx.drawString(mc.font, title,  textX, midY - mc.font.lineHeight - 1, titleColor);
            gfx.drawString(mc.font, artist, textX, midY + 2, artColor);
        }
    }

    private static String truncate(Minecraft mc, String text, int maxW) {
        if (mc.font.width(text) <= maxW) return text;
        return mc.font.plainSubstrByWidth(text, maxW - mc.font.width("…")) + "…";
    }
}
