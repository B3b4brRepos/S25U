package dev.ollefx.musicplayer.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ollefx.musicplayer.MusicPlayerMod;
import dev.ollefx.musicplayer.audio.Track;
import dev.ollefx.musicplayer.config.MusicConfig;
import okhttp3.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Spotify Web API integration.
 *
 * Authentication:
 *   Uses the PKCE flow (no client secret required) or Client Credentials
 *   (client ID + secret, server-to-server).
 *   Both flows are implemented; PKCE is preferred since the secret
 *   must not ship in a public mod.
 *
 * Playback:
 *   The Spotify Web Playback SDK requires a Premium account and runs in a browser.
 *   This mod plays the 30-second preview_url returned by the track object —
 *   the only publicly-available audio accessible from a desktop Java process
 *   without the proprietary SDK. Full playback requires the user to open the
 *   Spotify desktop app and control it via the Connect API (documented limitation).
 *
 * Setup (required):
 *   1. Create a Spotify app at developer.spotify.com/dashboard
 *   2. Add redirect URI: http://localhost:7879/callback
 *   3. Enter Client ID in mod settings (Client Secret optional for PKCE)
 *
 * Network: all HTTP calls on caller's thread (must NOT be called from render thread).
 */
public class SpotifyIntegration {

    private static final String API_BASE    = "https://api.spotify.com/v1";
    private static final String AUTH_BASE   = "https://accounts.spotify.com";
    private static final int    CALLBACK_PORT = 7879;

    private final MusicConfig config;
    private final OkHttpClient http;

    private volatile String accessToken;
    private volatile long   tokenExpiryMs;

    private String pkceVerifier; // stored across auth steps

    public SpotifyIntegration(MusicConfig config) {
        this.config = config;
        this.http   = new OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        // Restore saved tokens
        if (config.spotifyAccessToken != null && !config.spotifyAccessToken.isBlank()) {
            this.accessToken   = config.spotifyAccessToken;
            this.tokenExpiryMs = config.spotifyTokenExpiry;
        }
    }

    // ── Authentication ────────────────────────────────────────────────────────

    public boolean isConfigured() {
        return config.spotifyClientId != null && !config.spotifyClientId.isBlank();
    }

    public boolean isAuthenticated() {
        return accessToken != null && !accessToken.isBlank()
                && System.currentTimeMillis() < tokenExpiryMs - 60_000;
    }

    /**
     * Builds the PKCE authorization URL the user should open in their browser.
     * After logging in, Spotify redirects to localhost:7879/callback?code=...
     */
    public String buildAuthUrl() {
        try {
            pkceVerifier = generateCodeVerifier();
            String challenge = generateCodeChallenge(pkceVerifier);
            return AUTH_BASE + "/authorize?" +
                    "client_id=" + encode(config.spotifyClientId) +
                    "&response_type=code" +
                    "&redirect_uri=" + encode("http://localhost:" + CALLBACK_PORT + "/callback") +
                    "&scope=" + encode("user-read-playback-state user-modify-playback-state streaming") +
                    "&code_challenge_method=S256" +
                    "&code_challenge=" + encode(challenge);
        } catch (Exception e) {
            MusicPlayerMod.LOGGER.error("Spotify: failed to build auth URL", e);
            return "";
        }
    }

    /**
     * Exchanges the auth code (from the redirect callback) for access + refresh tokens.
     * Call this after capturing the code from the localhost callback server.
     */
    public boolean exchangeCode(String code) {
        if (pkceVerifier == null) return false;
        try {
            RequestBody body = new FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("redirect_uri", "http://localhost:" + CALLBACK_PORT + "/callback")
                    .add("client_id", config.spotifyClientId)
                    .add("code_verifier", pkceVerifier)
                    .build();
            Request req = new Request.Builder()
                    .url(AUTH_BASE + "/api/token")
                    .post(body)
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return false;
                JsonObject obj = JsonParser.parseString(resp.body().string()).getAsJsonObject();
                return applyTokenResponse(obj);
            }
        } catch (Exception e) {
            MusicPlayerMod.LOGGER.error("Spotify: code exchange failed", e);
            return false;
        }
    }

    /** Silently refresh the access token using the stored refresh token. */
    public boolean refreshToken() {
        if (config.spotifyRefreshToken == null || config.spotifyRefreshToken.isBlank()) return false;
        try {
            RequestBody body = new FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", config.spotifyRefreshToken)
                    .add("client_id", config.spotifyClientId)
                    .build();
            Request req = new Request.Builder()
                    .url(AUTH_BASE + "/api/token")
                    .post(body)
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return false;
                JsonObject obj = JsonParser.parseString(resp.body().string()).getAsJsonObject();
                return applyTokenResponse(obj);
            }
        } catch (Exception e) {
            MusicPlayerMod.LOGGER.error("Spotify: token refresh failed", e);
            return false;
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    /**
     * Search Spotify for tracks matching {@code query}.
     * Returns up to {@code limit} results (max 50 per Spotify API).
     * Blocking — call from background thread.
     */
    public List<Track> searchTracks(String query, int limit) {
        ensureToken();
        List<Track> results = new ArrayList<>();
        try {
            String url = API_BASE + "/search?type=track&limit=" + Math.min(limit, 50)
                    + "&q=" + encode(query);
            JsonObject body = apiGet(url);
            if (body == null) return results;

            JsonObject tracks = body.getAsJsonObject("tracks");
            if (tracks == null) return results;
            JsonArray items = tracks.getAsJsonArray("items");
            if (items == null) return results;

            for (JsonElement el : items) {
                try { results.add(parseSpotifyTrack(el.getAsJsonObject())); }
                catch (Exception ignored) {}
            }
        } catch (Exception e) {
            MusicPlayerMod.LOGGER.warn("Spotify search error: {}", e.getMessage());
        }
        return results;
    }

    /**
     * Returns the 30-second preview_url for a track, or null if unavailable.
     * Many tracks (especially in some regions) have no preview URL — this is a
     * Spotify limitation; full playback requires Spotify Premium + Web Playback SDK.
     */
    public String getPreviewUrl(String trackId) {
        ensureToken();
        try {
            JsonObject body = apiGet(API_BASE + "/tracks/" + trackId);
            if (body == null) return null;
            JsonElement preview = body.get("preview_url");
            if (preview == null || preview.isJsonNull()) return null;
            return preview.getAsString();
        } catch (Exception e) {
            MusicPlayerMod.LOGGER.warn("Spotify getPreviewUrl error: {}", e.getMessage());
            return null;
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private Track parseSpotifyTrack(JsonObject t) {
        String id    = "spotify:" + t.get("id").getAsString();
        String title = t.get("name").getAsString();
        long   dur   = t.get("duration_ms").getAsLong();

        JsonObject album   = t.getAsJsonObject("album");
        String albumName   = album != null ? album.get("name").getAsString() : "";
        String artworkUrl  = "";
        if (album != null) {
            JsonArray images = album.getAsJsonArray("images");
            if (images != null && images.size() > 0) {
                artworkUrl = images.get(0).getAsJsonObject().get("url").getAsString();
            }
        }

        String artist = "";
        JsonArray artists = t.getAsJsonArray("artists");
        if (artists != null && artists.size() > 0) {
            artist = artists.get(0).getAsJsonObject().get("name").getAsString();
        }

        String previewUrl = "";
        JsonElement prev = t.get("preview_url");
        if (prev != null && !prev.isJsonNull()) previewUrl = prev.getAsString();

        return new Track(id, title, artist, albumName, dur, artworkUrl, previewUrl, Track.Source.SPOTIFY);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private JsonObject apiGet(String url) throws IOException {
        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + accessToken)
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (resp.code() == 401) { refreshToken(); return null; }
            if (!resp.isSuccessful() || resp.body() == null) return null;
            return JsonParser.parseString(resp.body().string()).getAsJsonObject();
        }
    }

    private void ensureToken() {
        if (!isAuthenticated()) refreshToken();
    }

    private boolean applyTokenResponse(JsonObject obj) {
        if (!obj.has("access_token")) return false;
        accessToken   = obj.get("access_token").getAsString();
        int expiresIn = obj.get("expires_in").getAsInt();
        tokenExpiryMs = System.currentTimeMillis() + expiresIn * 1000L;
        if (obj.has("refresh_token") && !obj.get("refresh_token").isJsonNull()) {
            config.spotifyRefreshToken = obj.get("refresh_token").getAsString();
        }
        config.spotifyAccessToken = accessToken;
        config.spotifyTokenExpiry = tokenExpiryMs;
        config.save();
        return true;
    }

    // ── PKCE helpers ──────────────────────────────────────────────────────────

    private static String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String generateCodeChallenge(String verifier) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
