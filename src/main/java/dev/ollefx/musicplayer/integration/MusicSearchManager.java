package dev.ollefx.musicplayer.integration;

import dev.ollefx.musicplayer.MusicPlayerMod;
import dev.ollefx.musicplayer.audio.Track;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Fans out search requests to all three sources in priority order:
 *   1. Spotify (primary — where we listen)
 *   2. YouTube Data API (secondary)
 *   3. YouTube Music innertube (tertiary)
 *
 * Results from each source stream in as they arrive via onResults callbacks.
 * Maintains a 32-entry LRU cache keyed by lowercase query.
 */
public class MusicSearchManager {

    private static final int CACHE_SIZE   = 32;
    private static final int MAX_PER_SRC  = 15;

    private final SpotifyIntegration spotify;
    private final YouTubeIntegration youtube;
    private final YouTubeMusicSource ytMusic;

    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "musicplayer-search");
        t.setDaemon(true);
        return t;
    });

    // Simple LRU cache: query → results
    private final Map<String, List<Track>> cache = new LinkedHashMap<>(CACHE_SIZE, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<Track>> e) {
            return size() > CACHE_SIZE;
        }
    };

    private volatile Future<?> activeFuture;

    public MusicSearchManager(SpotifyIntegration spotify,
                              YouTubeIntegration youtube,
                              YouTubeMusicSource ytMusic) {
        this.spotify = spotify;
        this.youtube = youtube;
        this.ytMusic = ytMusic;
    }

    /**
     * Async search. Calls {@code onResults} on results arrival (may be called twice:
     * once for each source). Calls {@code onError} if both sources fail.
     * Cancels any in-progress search.
     */
    public void search(String query,
                       Consumer<List<Track>> onResults,
                       Consumer<String>      onError) {
        if (query == null || query.isBlank()) return;

        // Cancel previous
        if (activeFuture != null && !activeFuture.isDone()) activeFuture.cancel(true);

        String cacheKey = query.toLowerCase().trim();
        List<Track> cached = cache.get(cacheKey);
        if (cached != null) { onResults.accept(cached); return; }

        activeFuture = executor.submit(() -> {
            List<Track> combined = new ArrayList<>();
            boolean anySuccess = false;

            // Spotify
            if (spotify.isConfigured() && spotify.isAuthenticated()) {
                try {
                    List<Track> sp = spotify.searchTracks(query, MAX_PER_SRC);
                    if (!sp.isEmpty()) {
                        combined.addAll(sp);
                        onResults.accept(List.copyOf(combined));
                        anySuccess = true;
                    }
                } catch (Exception e) { /* handled below */ }
            }

            // YouTube Data API (priority 2)
            if (youtube.isConfigured()) {
                try {
                    List<Track> yt = youtube.searchTracks(query, MAX_PER_SRC);
                    if (!yt.isEmpty()) {
                        combined.addAll(yt);
                        onResults.accept(List.copyOf(combined));
                        anySuccess = true;
                    }
                } catch (Exception ignored) {}
            }

            // YouTube Music innertube (priority 3 — no key required)
            if (ytMusic != null && MusicPlayerMod.config.ytMusicEnabled) {
                try {
                    List<Track> ytm = ytMusic.search(query, MAX_PER_SRC);
                    if (!ytm.isEmpty()) {
                        combined.addAll(ytm);
                        onResults.accept(List.copyOf(combined));
                        anySuccess = true;
                    }
                } catch (Exception ignored) {}
            }

            if (!anySuccess) {
                onError.accept("No results — check your API keys in Settings");
            } else {
                cache.put(cacheKey, List.copyOf(combined));
            }
        });
    }

    public void shutdown() { executor.shutdownNow(); }
}
