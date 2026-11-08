package dev.ollefx.musicplayer.render;

import dev.ollefx.musicplayer.config.MusicConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Halloween ambient effects for the music player HUD and screen overlay.
 *
 * Effects:
 *   - Floating pumpkin particles (orange ◇ shapes with face detail via pixels)
 *   - Tiny drifting ghost sprites (ASCII-art-style ψ drawn in white)
 *   - Subtle fog layer at screen bottom (gradient fill)
 *   - Glowing particle embers
 *
 * All positions in screen-space [0,1] normalized — scaled on render.
 */
public class HalloweenEffects {

    private final AnimationController controller;
    private final MusicConfig         config;
    private final Random              rng = new Random();

    private final List<Particle> particles = new ArrayList<>();
    private final List<Ghost>    ghosts    = new ArrayList<>();

    private long tickCount = 0;

    public HalloweenEffects(AnimationController controller, MusicConfig config) {
        this.controller = controller;
        this.config     = config;
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    public void tick() {
        if (!config.halloweenTheme) return;
        tickCount++;

        Minecraft mc = Minecraft.getInstance();
        int screenW  = mc.getWindow().getGuiScaledWidth();
        int screenH  = mc.getWindow().getGuiScaledHeight();

        // Spawn particles
        if (config.halloweenParticles && particles.size() < config.particleCount) {
            if (tickCount % 3 == 0) {
                particles.add(new Particle(
                        rng.nextFloat() * screenW,       // x
                        screenH + 10,                    // start below screen
                        (rng.nextFloat() - 0.5f) * 0.4f,// vx drift
                        -(0.3f + rng.nextFloat() * 0.5f),// vy upward
                        rng.nextInt(3),                  // type: 0=pumpkin, 1=ember, 2=leaf
                        0.6f + rng.nextFloat() * 0.4f   // alpha
                ));
            }
        }

        // Update particles
        Iterator<Particle> pit = particles.iterator();
        while (pit.hasNext()) {
            Particle p = pit.next();
            p.x  += p.vx + (float)Math.sin(tickCount * 0.05f + p.phase) * 0.3f;
            p.y  += p.vy;
            p.vy += 0.005f; // slight gravity pull (reduces upward speed)
            p.life++;
            if (p.y < -20 || p.life > 600) pit.remove();
        }

        // Spawn ghosts
        if (config.halloweenGhosts && ghosts.size() < 8) {
            if (tickCount % 80 == 0) {
                ghosts.add(new Ghost(
                        rng.nextFloat() < 0.5f ? -20 : screenW + 20, // start L or R
                        30 + rng.nextFloat() * (screenH * 0.5f),
                        rng.nextFloat() < 0.5f ? 0.5f : -0.5f,       // direction
                        0.0f                                           // initial alpha
                ));
            }
        }

        Iterator<Ghost> git = ghosts.iterator();
        while (git.hasNext()) {
            Ghost g = git.next();
            g.x  += g.speed;
            g.y  += (float)Math.sin(tickCount * 0.04f + g.phase) * 0.4f;
            g.alpha = Math.min(0.6f, g.alpha + 0.01f); // fade in
            g.life++;
            boolean offscreen = g.x < -40 || g.x > screenW + 40;
            if (offscreen || g.life > 500) git.remove();
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────

    public void renderHud(GuiGraphicsExtractor gfx, Minecraft mc, float partialTick) {
        if (!config.halloweenTheme) return;

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        // Fog layer at bottom
        if (config.halloweenFog) {
            int fogH = screenH / 5;
            LiquidGlassRenderer.fillGradientV(gfx,
                    0, screenH - fogH, screenW, fogH,
                    0x00000000,
                    LiquidGlassRenderer.withAlpha(0x180030, 120));
        }

        // Particles
        if (config.halloweenParticles) {
            for (Particle p : List.copyOf(particles)) {
                int a = (int)(p.alpha * 255);
                switch (p.type) {
                    case 0 -> drawPumpkin(gfx, (int)p.x, (int)p.y, a);
                    case 1 -> drawEmber(gfx, (int)p.x, (int)p.y, a);
                    case 2 -> drawLeaf(gfx, (int)p.x, (int)p.y, a);
                }
            }
        }

        // Ghosts
        if (config.halloweenGhosts) {
            for (Ghost g : List.copyOf(ghosts)) {
                drawGhost(gfx, mc, (int)g.x, (int)g.y, g.alpha);
            }
        }
    }

    // ── Particle shapes ───────────────────────────────────────────────────────

    private static void drawPumpkin(GuiGraphicsExtractor gfx, int x, int y, int alpha) {
        // 7x7 mini pumpkin: orange body + green stem + black eyes/mouth
        int orange = LiquidGlassRenderer.withAlpha(0xFF6600, alpha);
        int green  = LiquidGlassRenderer.withAlpha(0x228800, alpha);
        int black  = LiquidGlassRenderer.withAlpha(0x000000, alpha);

        // Body
        LiquidGlassRenderer.fill(gfx, x, y + 1, 7, 5, orange);
        LiquidGlassRenderer.fill(gfx, x + 1, y, 5, 7, orange);
        // Stem
        LiquidGlassRenderer.fill(gfx, x + 3, y - 2, 1, 2, green);
        // Eyes
        LiquidGlassRenderer.fill(gfx, x + 1, y + 2, 1, 1, black);
        LiquidGlassRenderer.fill(gfx, x + 5, y + 2, 1, 1, black);
        // Mouth
        LiquidGlassRenderer.fill(gfx, x + 2, y + 4, 3, 1, black);
    }

    private static void drawEmber(GuiGraphicsExtractor gfx, int x, int y, int alpha) {
        int color1 = LiquidGlassRenderer.withAlpha(0xFF4400, alpha);
        int color2 = LiquidGlassRenderer.withAlpha(0xFFAA00, (int)(alpha * 0.6f));
        LiquidGlassRenderer.fill(gfx, x, y, 2, 2, color1);
        LiquidGlassRenderer.fill(gfx, x - 1, y - 1, 1, 1, color2);
    }

    private static void drawLeaf(GuiGraphicsExtractor gfx, int x, int y, int alpha) {
        int color = LiquidGlassRenderer.withAlpha(0x884400, alpha);
        LiquidGlassRenderer.fill(gfx, x, y, 1, 3, color);
        LiquidGlassRenderer.fill(gfx, x + 1, y + 1, 1, 1, color);
    }

    private static void drawGhost(GuiGraphicsExtractor gfx, Minecraft mc, int x, int y, float alpha) {
        // Simple ghost: white blob + wavy bottom + dark eyes
        int white = LiquidGlassRenderer.withAlpha(0xEEEEFF, (int)(alpha * 200));
        int dark  = LiquidGlassRenderer.withAlpha(0x222244, (int)(alpha * 200));

        // Ghost body
        LiquidGlassRenderer.fill(gfx, x, y, 10, 8, white);
        LiquidGlassRenderer.fill(gfx, x + 2, y - 2, 6, 2, white); // round top
        // Wavy bottom (3 bumps)
        LiquidGlassRenderer.fill(gfx, x, y + 8, 3, 2, white);
        LiquidGlassRenderer.fill(gfx, x + 7, y + 8, 3, 2, white);
        // Eyes
        LiquidGlassRenderer.fill(gfx, x + 2, y + 2, 2, 2, dark);
        LiquidGlassRenderer.fill(gfx, x + 6, y + 2, 2, 2, dark);
    }

    // ── Data classes ──────────────────────────────────────────────────────────

    private static class Particle {
        float x, y, vx, vy, alpha, phase;
        int type, life;
        Particle(float x, float y, float vx, float vy, int type, float alpha) {
            this.x = x; this.y = y; this.vx = vx; this.vy = vy;
            this.type = type; this.alpha = alpha;
            this.phase = (float)(Math.random() * Math.PI * 2);
        }
    }

    private static class Ghost {
        float x, y, speed, alpha, phase;
        int life;
        Ghost(float x, float y, float speed, float alpha) {
            this.x = x; this.y = y; this.speed = speed; this.alpha = alpha;
            this.phase = (float)(Math.random() * Math.PI * 2);
        }
    }
}
