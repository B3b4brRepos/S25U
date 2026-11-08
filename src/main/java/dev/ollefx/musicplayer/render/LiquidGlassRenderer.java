package dev.ollefx.musicplayer.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * Draws the iOS 27-inspired "Liquid Glass" UI elements.
 *
 * Since MC 26.3 uses the renderpearl pipeline, we draw via GuiGraphicsExtractor.
 * True GPU-side backdrop blur requires a custom framebuffer pass not feasible
 * in a standard Fabric mixin without a render pipeline mod; we simulate it with
 * layered translucent fills + specular highlights — consistent with Sodium.
 *
 * Color palette constants:
 *   GLASS_BASE   : near-black with low alpha → frosted glass feel
 *   GLASS_TINT   : slight blue-purple tint for depth
 *   HIGHLIGHT    : white top specular highlight strip
 *   BORDER       : semi-transparent bright border
 *   SHADOW       : drop-shadow beneath panels
 *
 * Halloween palette overrides:
 *   HW_GLASS     : deep dark-purple tinted glass
 *   HW_BORDER    : pumpkin-orange glow border
 *   HW_ACCENT    : blood-red accent
 */
public final class LiquidGlassRenderer {

    // Standard glass palette
    public static final int GLASS_BASE   = color(0.06f, 0.06f, 0.10f, 0.72f);
    public static final int GLASS_TINT   = color(0.15f, 0.10f, 0.25f, 0.30f);
    public static final int HIGHLIGHT    = color(1.00f, 1.00f, 1.00f, 0.10f);
    public static final int BORDER_STD   = color(1.00f, 1.00f, 1.00f, 0.15f);
    public static final int SHADOW       = color(0.00f, 0.00f, 0.00f, 0.40f);

    // Halloween palette
    public static final int HW_GLASS     = color(0.06f, 0.02f, 0.12f, 0.80f);
    public static final int HW_BORDER    = color(1.00f, 0.55f, 0.00f, 0.70f); // pumpkin orange
    public static final int HW_BORDER2   = color(0.60f, 0.00f, 0.80f, 0.60f); // purple
    public static final int HW_ACCENT    = color(0.70f, 0.00f, 0.10f, 0.80f); // blood red
    public static final int HW_GLOW      = color(1.00f, 0.40f, 0.00f, 0.20f); // orange ambient

    private LiquidGlassRenderer() {}

    // ── Core panel ────────────────────────────────────────────────────────────

    /**
     * Draws a layered glass panel (rounded visually via layering; true GPU rounded
     * rects require a custom shader which we approximate with 3-layer fill).
     *
     * @param gfx     GuiGraphicsExtractor
     * @param x, y   top-left corner
     * @param w, h   size
     * @param radius  corner radius hint (not GPU-enforced — cosmetic only via highlights)
     * @param halloween use Halloween color palette
     * @param alpha   overall alpha multiplier 0–1
     */
    public static void drawPanel(GuiGraphicsExtractor gfx,
                                 int x, int y, int w, int h,
                                 int radius, boolean halloween, float alpha) {
        int base   = halloween ? multiplyAlpha(HW_GLASS,   alpha) : multiplyAlpha(GLASS_BASE, alpha);
        int tint   = halloween ? multiplyAlpha(HW_GLOW,    alpha * 0.5f) : multiplyAlpha(GLASS_TINT, alpha * 0.5f);
        int shadow = multiplyAlpha(SHADOW, alpha * 0.6f);
        int border = halloween ? multiplyAlpha(HW_BORDER,  alpha * 0.8f) : multiplyAlpha(BORDER_STD, alpha);
        int hi     = multiplyAlpha(HIGHLIGHT, alpha);

        // Drop shadow
        fill(gfx, x + 3, y + 3, w, h, shadow);
        fill(gfx, x + 2, y + 2, w, h, multiplyAlpha(SHADOW, alpha * 0.3f));

        // Base glass fill
        fill(gfx, x, y, w, h, base);

        // Inner tint layer
        fill(gfx, x + 1, y + 1, w - 2, h - 2, tint);

        // Top specular highlight strip
        fill(gfx, x + radius, y, w - radius * 2, Math.min(2, h), hi);
        fill(gfx, x + radius, y + 1, w - radius * 2, 1, multiplyAlpha(HIGHLIGHT, alpha * 0.4f));

        // Left edge specular
        fill(gfx, x, y + radius, 1, h - radius * 2, multiplyAlpha(HIGHLIGHT, alpha * 0.06f));

        // Border
        drawBorder(gfx, x, y, w, h, 1, border);

        // Halloween second border glow
        if (halloween) {
            drawBorder(gfx, x - 1, y - 1, w + 2, h + 2, 1, multiplyAlpha(HW_BORDER2, alpha * 0.4f));
        }
    }

    /** Draw a pill-shaped capsule (used for Dynamic Island + buttons). */
    public static void drawCapsule(GuiGraphicsExtractor gfx,
                                   int x, int y, int w, int h,
                                   boolean halloween, float alpha) {
        int r = h / 2;
        drawPanel(gfx, x, y, w, h, r, halloween, alpha);
    }

    /** Animated gradient background behind the main screen. */
    public static void drawScreenBackground(GuiGraphicsExtractor gfx,
                                            int screenW, int screenH,
                                            boolean halloween, float alpha) {
        int bg;
        if (halloween) {
            // Purple-to-black diagonal gradient via two fills
            bg = multiplyAlpha(color(0.06f, 0.01f, 0.12f, 0.88f), alpha);
        } else {
            bg = multiplyAlpha(color(0.04f, 0.04f, 0.08f, 0.82f), alpha);
        }
        fill(gfx, 0, 0, screenW, screenH, bg);

        if (halloween) {
            // Purple vignette corners
            int v = multiplyAlpha(color(0.25f, 0f, 0.4f, 0.35f), alpha);
            // Top-left
            fillGradientV(gfx, 0, 0, screenW / 3, screenH / 3, v, 0x00000000);
            // Bottom-right
            fillGradientV(gfx, screenW * 2 / 3, screenH * 2 / 3,
                    screenW / 3, screenH / 3, 0x00000000, v);
        }
    }

    // ── Primitive helpers ─────────────────────────────────────────────────────

    /** Fill a solid rectangle. */
    public static void fill(GuiGraphicsExtractor gfx, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0) return;
        gfx.fill(x, y, x + w, y + h, color);
    }

    /** Vertical gradient fill (top-to-bottom). */
    public static void fillGradientV(GuiGraphicsExtractor gfx,
                                     int x, int y, int w, int h,
                                     int colorTop, int colorBot) {
        if (w <= 0 || h <= 0) return;
        gfx.fillGradient(x, y, x + w, y + h, colorTop, colorBot);
    }

    /** 1-pixel border. */
    public static void drawBorder(GuiGraphicsExtractor gfx,
                                  int x, int y, int w, int h,
                                  int thickness, int color) {
        fill(gfx, x, y, w, thickness, color);                // top
        fill(gfx, x, y + h - thickness, w, thickness, color);// bottom
        fill(gfx, x, y + thickness, thickness, h - thickness * 2, color); // left
        fill(gfx, x + w - thickness, y + thickness, thickness, h - thickness * 2, color); // right
    }

    /** Progress / seek bar in Liquid Glass style. */
    public static void drawProgressBar(GuiGraphicsExtractor gfx,
                                       int x, int y, int w, int h,
                                       float progress,
                                       boolean halloween, float alpha) {
        int track  = multiplyAlpha(halloween ? HW_GLASS : GLASS_BASE, alpha);
        int filled = halloween ? multiplyAlpha(HW_BORDER, alpha) : multiplyAlpha(color(1f, 1f, 1f, 0.9f), alpha);
        int knob   = halloween ? multiplyAlpha(HW_BORDER, alpha) : multiplyAlpha(color(1f, 1f, 1f, 1f), alpha);

        fill(gfx, x, y, w, h, track);
        fill(gfx, x, y, (int)(w * progress), h, filled);

        // Knob
        int kx = x + (int)(w * progress) - h / 2;
        fill(gfx, kx, y - 2, h + 1, h + 4, knob);
    }

    // ── Color math ────────────────────────────────────────────────────────────

    private static int color(float r, float g, float b, float a) {
        int ai = (int)(a * 255) & 0xFF;
        int ri = (int)(r * 255) & 0xFF;
        int gi = (int)(g * 255) & 0xFF;
        int bi = (int)(b * 255) & 0xFF;
        return (ai << 24) | (ri << 16) | (gi << 8) | bi;
    }

    public static int multiplyAlpha(int color, float alpha) {
        int a = (int)(((color >> 24) & 0xFF) * Math.max(0, Math.min(1, alpha)));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    /** Linear interpolation between two ARGB colors. */
    public static int lerpColor(int a, int b, float t) {
        int aa = (a >> 24) & 0xFF, ra = (a >> 16) & 0xFF, ga = (a >> 8) & 0xFF, ba = a & 0xFF;
        int ab = (b >> 24) & 0xFF, rb = (b >> 16) & 0xFF, gb = (b >> 8) & 0xFF, bb = b & 0xFF;
        int ar = aa + (int)((ab - aa) * t);
        int rr = ra + (int)((rb - ra) * t);
        int gr = ga + (int)((gb - ga) * t);
        int br = ba + (int)((bb - ba) * t);
        return (ar << 24) | (rr << 16) | (gr << 8) | br;
    }
}
