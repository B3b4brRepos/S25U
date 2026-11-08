package dev.ollefx.musicplayer.screen;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;

public final class MusicKeybindings {

    // Category translation key
    private static final String CATEGORY = "musicplayer.key.category";

    /**
     * M key — opens/closes the Music Player screen.
     * Uses KeyMapping constructor: (name, type, keyCode, category)
     * Registered via KeyBindingHelper before use.
     */
    public static final KeyMapping KEY_OPEN_MUSIC = new KeyMapping(
            "musicplayer.key.open",
            InputConstants.Type.KEYBOARD,
            77,  // GLFW_KEY_M
            CATEGORY
    );

    private MusicKeybindings() {}
}
