package dev.ollefx.musicplayer.mixin;

import dev.ollefx.musicplayer.MusicPlayerMod;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks GameRenderer to intercept the game shutdown signal
 * so AudioBackend tears down cleanly before the OpenAL device closes.
 *
 * We use the existing Fabric WorldRenderEvents API for the 3D render injection,
 * so this mixin only handles the shutdown lifecycle.
 */
@Mixin(GameRenderer.class)
public class MixinGameRenderer {

    /**
     * Called when the game renderer is being torn down (game close).
     * Ensures our AudioBackend is shut down before Minecraft's own
     * SoundEngine closes the default OpenAL device.
     */
    @Inject(method = "close", at = @At("HEAD"))
    private void onClose(CallbackInfo ci) {
        MusicPlayerMod.shutdown();
    }
}
