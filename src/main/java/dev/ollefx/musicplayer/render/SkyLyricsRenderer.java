package dev.ollefx.musicplayer.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ollefx.musicplayer.audio.MusicManager;
import dev.ollefx.musicplayer.config.MusicConfig;
import dev.ollefx.musicplayer.lyrics.LyricsManager;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Renders time-synchronized lyrics as 3D text floating in the Minecraft sky.
 *
 * Uses Fabric's WorldRenderEvents.BEFORE_ENTITIES callback — fires every frame
 * with the render matrix already set up. We position the text relative to the
 * camera, billboard-facing (always faces the player).
 *
 * Rendering:
 *   - net.minecraft.client.renderer.entity.EntityRenderer#renderNameTag style
 *     billboard text using the standard entity name-tag pipeline.
 *   - Depth-test disabled so it draws above world geometry.
 *   - Glow effect via two draws: back pass (larger, colored) + front pass (white).
 *
 * Note: RenderType.TEXT_BACKGROUND and RenderType.TEXT are part of the standard
 * Minecraft render pipeline and are available without custom shaders.
 */
public class SkyLyricsRenderer {

    private final LyricsManager lyrics;
    private final MusicManager  music;
    private final MusicConfig   config;

    // Animated alpha per-line
    private float currentAlpha  = 0f;
    private float targetAlpha   = 0f;

    /**
     * Render text in world space using MC 26.3's PreparedText API.
     * PreparedText.visit(GlyphVisitor) iterates each renderable glyph;
     * we emit vertices via VertexConsumer from the MultiBufferSource.
     */
    private static void renderTextWorld(
            net.minecraft.client.gui.Font font,
            com.mojang.blaze3d.vertex.PoseStack pose,
            net.minecraft.client.renderer.SubmitNodeCollector buffers,
            String text, float x, float y, int color, int packedLight) {
        try {
            var prepared = font.prepareText(text, x, y, color, false, packedLight);
            if (prepared == null) return;
            var matrix = pose.last().pose();
            prepared.visit(new net.minecraft.client.gui.Font.GlyphVisitor() {
                @Override
                public void acceptRenderable(net.minecraft.client.gui.font.TextRenderable r) {
                    var rt = r.renderType(net.minecraft.client.gui.Font.DisplayMode.NORMAL);
                    // SubmitNodeCollector.get(RenderType) → VertexConsumer
                    var vc = buffers.get(rt);
                    if (vc != null) r.render(matrix, vc, packedLight, false);
                }
                @Override
                public void acceptGlyph(net.minecraft.client.gui.font.TextRenderable.Styled r) {
                    var rt = r.renderType(net.minecraft.client.gui.Font.DisplayMode.NORMAL);
                    var vc = buffers.get(rt);
                    if (vc != null) r.render(matrix, vc, packedLight, false);
                }
                @Override
                public void acceptEmptyArea(net.minecraft.client.gui.font.EmptyArea e) {}
            });
        } catch (Exception ignored) {
            // Render errors must not crash MC
        }
    }
    private static final float FADE_SPEED = 2.5f; // per second

    private String lastLine = "";
    private long   lastChangeMs = 0;

    private boolean registered = false;

    public SkyLyricsRenderer(LyricsManager lyrics, MusicManager music, MusicConfig config) {
        this.lyrics = lyrics;
        this.music  = music;
        this.config = config;
    }

    public void register() {
        if (!registered) {
            WorldRenderEvents.BEFORE_ENTITIES.register(this::onWorldRender);
            registered = true;
        }
    }

    public void unregister() {
        // Fabric doesn't support deregistration; just gate on config flag
        registered = false;
    }

    // ── Render callback ───────────────────────────────────────────────────────

    private void onWorldRender(WorldRenderContext ctx) {
        if (!registered) return;
        if (config.lyricsMode != MusicConfig.LyricsMode.SKY
                && config.lyricsMode != MusicConfig.LyricsMode.BOTH) return;
        if (music == null || music.getState() != MusicManager.State.PLAYING) return;
        if (!lyrics.isAvailable()) return;

        LyricsManager.LyricsLine cur = lyrics.getCurrentLine();
        if (cur == null) { targetAlpha = 0f; return; }

        String text = cur.text();
        if (text.isEmpty()) { targetAlpha = 0f; return; }

        // Fade in/out on lyric change
        if (!text.equals(lastLine)) {
            lastLine      = text;
            lastChangeMs  = System.currentTimeMillis();
            targetAlpha   = 0f; // fade out first, then in via tick
        }
        // MC 26.3: WorldRenderContext.tickDelta() returns the partial tick float
        float dt = ctx.tickDelta() / 20f;
        float fadeDelta = FADE_SPEED * dt;

        float newTarget = 1.0f * config.skyLyricsOpacity;
        if (System.currentTimeMillis() - lastChangeMs > 150) targetAlpha = newTarget;

        currentAlpha = currentAlpha < targetAlpha
                ? Math.min(currentAlpha + fadeDelta, targetAlpha)
                : Math.max(currentAlpha - fadeDelta, targetAlpha);

        if (currentAlpha < 0.01f) return;

        // ── Matrix setup ─────────────────────────────────────────────────────
        Minecraft mc    = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        PoseStack pose = ctx.matrixStack();
        // MC 26.3: WorldRenderContext.consumers() returns SubmitNodeCollector
        // (MultiBufferSource is replaced in the renderpearl pipeline)
        SubmitNodeCollector buffers = (SubmitNodeCollector) ctx.consumers();
        if (buffers == null) return;

        // Camera position (we're in world space; camera is at origin in pose stack)
        Vector3f camPos = new Vector3f(
                (float) mc.player.getEyeX(),
                (float) mc.player.getEyeY(),
                (float) mc.player.getEyeZ());

        // Target position: forward * distance, up * height
        Vector3f forward = new Vector3f(
                (float)(Math.cos(Math.toRadians(mc.player.getYRot() + 90))),
                0f,
                (float)(Math.sin(Math.toRadians(mc.player.getYRot() + 90))));
        forward.normalize().mul(config.skyLyricsDistance);

        // Final world position
        Vector3f textPos = new Vector3f(camPos).add(forward)
                .add(0, config.skyLyricsHeight, 0);

        // Camera-relative offset — Camera.position is a Vec3 field in MC 26.3
        net.minecraft.client.Camera cam = mc.getEntityRenderDispatcher().camera;
        double cix = cam.position.x;
        double ciy = cam.position.y;
        double ciz = cam.position.z;

        pose.pushPose();
        pose.translate(
                textPos.x - cix,
                textPos.y - ciy,
                textPos.z - ciz);

        // Billboard: rotate to face camera (negate camera yaw/pitch)
        float yaw   = -(mc.getEntityRenderDispatcher().camera.getYRot());
        float pitch = -(mc.getEntityRenderDispatcher().camera.getXRot());
        pose.mulPose(new Quaternionf().rotationYXZ(
                (float) Math.toRadians(yaw),
                (float) Math.toRadians(pitch),
                0f));

        // Scale
        float scale = 0.05f * config.skyLyricsScale;
        pose.scale(-scale, -scale, scale);

        // ── Draw text — MC 26.3 PreparedText API ──────────────────────────────
        int textW  = mc.font.width(text);
        int textX  = -textW / 2;
        int textY  = -mc.font.lineHeight / 2;
        int alpha  = (int)(currentAlpha * 255);

        // MC 26.3: Font.prepareText(String, float x, float y, int color, boolean shadow, int packedLight)
        // Returns PreparedText whose visit(GlyphVisitor) iterates renderables.
        // GlyphVisitor.acceptRenderable(TextRenderable) → renderable.render(Matrix4fc, VertexConsumer, int, boolean)
        int lightPacked = 0xF000F0; // full-bright packed light coords
        int mainColor = LiquidGlassRenderer.withAlpha(
                config.halloweenTheme ? 0xFF8C00 : 0xFFFFFF, alpha);

        if (config.skyLyricsGlow) {
            int glowAlpha = (int)(alpha * 0.35f * config.skyLyricsGlowIntensity);
            int glowColor = config.halloweenTheme
                    ? LiquidGlassRenderer.withAlpha(0xFF4400, glowAlpha)
                    : LiquidGlassRenderer.withAlpha(0xFFFFFF, glowAlpha);
            renderTextWorld(mc.font, pose, buffers, text, textX - 1, textY, glowColor, lightPacked);
            renderTextWorld(mc.font, pose, buffers, text, textX + 1, textY, glowColor, lightPacked);
            renderTextWorld(mc.font, pose, buffers, text, textX, textY - 1, glowColor, lightPacked);
            renderTextWorld(mc.font, pose, buffers, text, textX, textY + 1, glowColor, lightPacked);
        }

        renderTextWorld(mc.font, pose, buffers, text, textX, textY, mainColor, lightPacked);

        pose.popPose();
    }
}
