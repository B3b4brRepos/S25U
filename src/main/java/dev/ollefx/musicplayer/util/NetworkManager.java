package dev.ollefx.musicplayer.util;

import dev.ollefx.musicplayer.MusicPlayerMod;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.concurrent.TimeUnit;

/**
 * Shared OkHttpClient singleton with connection pooling.
 * All integrations should use this for consistent timeouts and pooling.
 */
public final class NetworkManager {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    private NetworkManager() {}

    public static OkHttpClient client() { return CLIENT; }

    /** Simple GET → response body string. Returns null on failure. */
    public static String get(String url) {
        return get(url, null);
    }

    public static String get(String url, String bearerToken) {
        try {
            Request.Builder rb = new Request.Builder().url(url);
            if (bearerToken != null) rb.header("Authorization", "Bearer " + bearerToken);
            rb.header("User-Agent", "MusicPlayerMod/1.0 (Minecraft Fabric)");
            try (Response resp = CLIENT.newCall(rb.build()).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    MusicPlayerMod.LOGGER.warn("NetworkManager: HTTP {} for {}", resp.code(), url);
                    return null;
                }
                return resp.body().string();
            }
        } catch (Exception e) {
            MusicPlayerMod.LOGGER.warn("NetworkManager: GET failed for {}: {}", url, e.getMessage());
            return null;
        }
    }
}
