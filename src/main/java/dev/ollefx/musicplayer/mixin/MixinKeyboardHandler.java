package dev.ollefx.musicplayer.mixin;

import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Placeholder mixin for KeyboardHandler.
 *
 * The M-key guard is handled automatically by KeyMapping.consumeClick():
 * Fabric's KeyBindingHelper registers mappings that don't fire while
 * the focused widget captures keyboard input (chat screen, sign editor,
 * book editor, etc.). No additional injection is needed.
 *
 * This class exists to satisfy the mixins.json declaration and may be
 * extended if key-event interception is needed in future versions.
 */
@Mixin(KeyboardHandler.class)
public class MixinKeyboardHandler {
    // No injections required — KeyMapping handles focus guard natively.
}
