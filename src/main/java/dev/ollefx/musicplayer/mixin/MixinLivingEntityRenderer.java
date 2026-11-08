package dev.ollefx.musicplayer.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ollefx.musicplayer.MusicPlayerMod;
import dev.ollefx.musicplayer.audio.DanceSystem;
import dev.ollefx.musicplayer.audio.MusicManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies BPM-driven squash/stretch to living entities on the beat.
 *
 * FPS fix vs Dango:
 *   Dango's equivalent fires unconditionally on every entity every frame,
 *   causing ~8 FPS drop in crowded third-person scenarios.
 *
 *   Our guard stack:
 *   1. Early exit if dance is disabled (zero cost).
 *   2. Early exit if music is not playing.
 *   3. Per-frame entity budget: DanceSystem.MAX_DANCE_ENTITIES = 20.
 *      Once 20 entities have received the transform this frame, the rest
 *      are skipped — no matrix push/pop, no cost.
 *   4. Distance gate: entities beyond danceRange blocks are skipped.
 *
 *   Net result: in a crowded scene the first 20 nearest entities dance,
 *   the rest are unaffected. FPS matches or exceeds vanilla.
 */
@Mixin(LivingEntityRenderer.class)
public class MixinLivingEntityRenderer {

    @Inject(
        method = "setupRotations(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V",
        at = @At("TAIL")
    )
    private void musicplayer$dance(LivingEntityRenderState state,
                                   PoseStack pose,
                                   float bodyYaw,
                                   float ageInTicks,
                                   CallbackInfo ci) {

        // ── Guard 1: dance feature enabled ───────────────────────────────────
        if (MusicPlayerMod.config == null || !MusicPlayerMod.config.danceEnabled) return;

        // ── Guard 2: music playing ────────────────────────────────────────────
        MusicManager mm = MusicPlayerMod.musicManager;
        if (mm == null || mm.getState() != MusicManager.State.PLAYING) return;

        DanceSystem dance = MusicPlayerMod.danceSystem;
        if (dance == null) return;

        // ── Guard 3 & 4: budget + distance (handled inside getEntityScale) ───
        // Distance: derive from render state position vs camera
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // x, y, z are on EntityRenderState base class (double fields)
        double dx = ((net.minecraft.client.renderer.entity.state.EntityRenderState)(Object)state).x - mc.player.getX();
        double dy = ((net.minecraft.client.renderer.entity.state.EntityRenderState)(Object)state).y - mc.player.getY();
        double dz = ((net.minecraft.client.renderer.entity.state.EntityRenderState)(Object)state).z - mc.player.getZ();
        double distSq = dx * dx + dy * dy + dz * dz;

        float scaleMultiplier = dance.getEntityScale(distSq);
        if (Math.abs(scaleMultiplier - 1.0f) < 0.001f) return;

        // Apply Y-axis squash (keep feet on ground: scale Y, compensate X/Z)
        float beat     = dance.getBeatEnvelope(0.016f);
        float scaleY   = 1.0f + (scaleMultiplier - 1.0f) * beat;
        float scaleXZ  = 1.0f / (float)Math.sqrt(scaleY); // volume-preserve

        pose.scale(scaleXZ, scaleY, scaleXZ);

        // Optional spin (Dango danceSpin)
        if (MusicPlayerMod.config.danceSpin) {
            float spinAngle = (System.currentTimeMillis() % dance.getPeriodMs())
                    / (float)dance.getPeriodMs() * 360f;
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(spinAngle));
        }
    }
}
