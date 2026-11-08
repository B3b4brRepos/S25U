package dev.ollefx.musicplayer.util;

import dev.ollefx.musicplayer.MusicPlayerMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.net.URI;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Downloads album artwork from HTTP URLs, registers them as DynamicTextures,
 * and returns their Minecraft texture Identifier for rendering.
 *
 * Cache: LRU, 50 entries max. Each entry holds the Identifier (registered
 * in Minecraft's TextureManager) which persists until eviction.
 *
 * Threading: downloads on background executor, registration on render thread
 * via Minecraft.getInstance().execute().
 */
public class ArtworkManager {

    private static final int CACHE_SIZE = 50;

    private final ExecutorService downloader = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "musicplayer-artwork");
        t.setDaemon(true);
        return t;
    });

    // url → registered Identifier (null while pending)
    private final Map<String, Identifier> cache = new LinkedHashMap<>(CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Identifier> e) {
            if (size() > CACHE_SIZE) {
                // Unregister oldest texture
                if (e.getValue() != null) {
                    Minecraft.getInstance().execute(() ->
                            Minecraft.getInstance().getTextureManager().release(e.getValue()));
                }
                return true;
            }
            return false;
        }
    };

    private final Object lock = new Object();

    /**
     * Returns the texture identifier for the given URL.
     * If not cached, starts an async download and calls {@code onLoaded}
     * when the texture is ready (on the render thread).
     * Returns null if not yet available.
     */
    public Identifier getOrLoad(String url, Consumer<Identifier> onLoaded) {
        if (url == null || url.isBlank()) return null;
        synchronized (lock) {
            Identifier cached = cache.get(url);
            if (cached != null) return cached;
            // Sentinel: mark as pending so we don't double-download
            cache.put(url, null);
        }

        downloader.submit(() -> {
            try {
                URL imageUrl  = URI.create(url).toURL();
                // MC 26.3: NativeImage.read(InputStream) reads directly from stream
                // This avoids the BufferedImage intermediate and Format parameter
                com.mojang.blaze3d.platform.NativeImage ni;
                try (var stream = imageUrl.openStream()) {
                    ni = com.mojang.blaze3d.platform.NativeImage.read(stream);
                }
                if (ni == null) return;

                Minecraft.getInstance().execute(() -> {
                    try {
                        // MC 26.3: DynamicTexture(Supplier<String> debugName, NativeImage)
                        DynamicTexture tex = new DynamicTexture(() -> "musicplayer/artwork", ni);
                        // Build a unique identifier for this URL
                        String path = "artwork/" + Math.abs(url.hashCode());
                        Identifier id = Identifier.withDefaultNamespace("musicplayer:" + path);
                        Minecraft.getInstance().getTextureManager().register(id, tex);
                        synchronized (lock) { cache.put(url, id); }
                        onLoaded.accept(id);
                    } catch (Exception e) {
                        MusicPlayerMod.LOGGER.warn("ArtworkManager: register failed for {}: {}", url, e.getMessage());
                    }
                });
            } catch (Exception e) {
                MusicPlayerMod.LOGGER.warn("ArtworkManager: download failed for {}: {}", url, e.getMessage());
                synchronized (lock) { cache.remove(url); }
            }
        });
        return null;
    }

    public void shutdown() {
        downloader.shutdownNow();
        // Release all registered textures on render thread
        Map<String, Identifier> snapshot;
        synchronized (lock) { snapshot = Map.copyOf(cache); }
        Minecraft.getInstance().execute(() -> {
            for (Identifier id : snapshot.values()) {
                if (id != null) Minecraft.getInstance().getTextureManager().release(id);
            }
        });
    }

}
