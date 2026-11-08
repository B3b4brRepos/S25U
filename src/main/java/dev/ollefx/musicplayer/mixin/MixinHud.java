package dev.ollefx.musicplayer.mixin;

import dev.ollefx.musicplayer.MusicPlayerMod;
import dev.ollefx.musicplayer.render.SongCardRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects the song card (now-playing overlay) + Dynamic Island pill into the HUD.
 *
 * Hook point: Hud#extractCameraOverlays TAIL — matches Dango's HudMixin exactly,
 * ensuring our overlays render after vanilla HUD elements (health, hotbar, etc.)
 * but still participate in the same render pass without an extra framebuffer.
 */
@Mixin(Hud.class)
public class MixinHud {

    @Inject(
        method = "extractCameraOverlays(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
        at = @At("TAIL")
    )
    private void musicplayer$overlay(GuiGraphicsExtractor graphics,
                                     DeltaTracker deltaTracker,
                                     CallbackInfo ci) {
        if (MusicPlayerMod.config == null) return;
        float pt = deltaTracker.getGameTimeDeltaPartialTick(true);
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();

        // Song card (cover + title, top-left — Dango "Now Playing" overlay)
        if (MusicPlayerMod.config.nowPlayingOverlay && MusicPlayerMod.songCardRenderer != null) {
            MusicPlayerMod.songCardRenderer.render(graphics, mc, pt);
        }

        // Dynamic Island pill (top-center)
        if (MusicPlayerMod.lyricsPill != null) {
            MusicPlayerMod.lyricsPill.render(graphics, mc, pt);
        }
    }
}
