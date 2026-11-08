package dev.ollefx.musicplayer.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ollefx.musicplayer.MusicPlayerMod;
import dev.ollefx.musicplayer.audio.Track;
import dev.ollefx.musicplayer.config.MusicConfig;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * YouTube Data API v3 integration.
 *
 * Search: uses the public /search endpoint — requires API key (free quota: 10,000 units/day).
 *
 * Audio playback:
 *   YouTube does not provide a public audio stream endpoint.
 *   getAudioStreamUrl() returns an empty string — the mod surfaces the
 *   YouTube link so the user can open it externally, and displays metadata/thumbnails.
 *   Full audio streaming would require yt-dlp (a local binary the user installs),
 *   which this method can invoke if present on PATH.
 *
 * Setup (required for search):
 *   1. Enable YouTube Data API v3 in Google Cloud Console.
 *   2. Create an API key (no OAuth needed for search).
 *   3. Enter the key in mod settings → YouTube API Key.
 */
public class YouTubeIntegration {

    private static final String YT_API    = "https://www.googleapis.com/youtube/v3";
    private static final String YT_WATCH  = "https://www.youtube.com/watch?v=";

    private final MusicConfig  config;
    private final OkHttpClient http;

    public YouTubeIntegration(MusicConfig config) {
        this.config = config;
        this.http   = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    public boolean isConfigured() {
        return config.youtubeApiKey != null && !config.youtubeApiKey.isBlank();
    }

    // ── Search ────────────────────────────────────────────────────────────────

    /**
     * Search YouTube for music videos matching {@code query}.
     * Blocking — call from background thread.
     */
    public List<Track> searchTracks(String query, int limit) {
        if (!isConfigured()) return List.of();
        List<Track> results = new ArrayList<>();
        try {
            String url = YT_API + "/search"
                    + "?part=snippet"
                    + "&type=video"
                    + "&videoCategoryId=10"   // Music category
                    + "&maxResults=" + Math.min(limit, 50)
                    + "&q=" + encode(query)
                    + "&key=" + config.youtubeApiKey;

            JsonObject body = httpGet(url);
            if (body == null) return results;

            JsonArray items = body.getAsJsonArray("items");
            if (items == null) return results;

            // Collect video IDs for a batch details request (for duration)
            List<String> ids = new ArrayList<>();
            for (JsonElement el : items) {
                try {
                    JsonObject id = el.getAsJsonObject().getAsJsonObject("id");
                    if (id.has("videoId")) ids.add(id.get("videoId").getAsString());
                } catch (Exception ignored) {}
            }

            // Fetch durations in one batch
            java.util.Map<String, Long> durations = fetchDurations(ids);

            for (JsonElement el : items) {
                try { results.add(parseYtItem(el.getAsJsonObject(), durations)); }
                catch (Exception ignored) {}
            }
        } catch (Exception e) {
            MusicPlayerMod.LOGGER.warn("YouTube search error: {}", e.getMessage());
        }
        return results;
    }

    /**
     * Attempts to get a direct audio stream URL for a YouTube video.
     *
     * Strategy:
     *   1. Try to invoke yt-dlp on PATH for a direct audio URL.
     *   2. If unavailable, return the YouTube watch URL as a fallback
     *      (can be opened in a browser; not playable in-game).
     */
    public String getAudioStreamUrl(String videoId) {
        // Try yt-dlp
        String ytdlp = resolveViaYtDlp(videoId);
        if (ytdlp != null && !ytdlp.isBlank()) return ytdlp;
        // Fallback: watch URL — not directly playable by AudioDecoder
        MusicPlayerMod.LOGGER.info("YouTube: no yt-dlp available for {}; returning watch URL", videoId);
        return YT_WATCH + videoId;
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private Track parseYtItem(JsonObject item, java.util.Map<String, Long> durations) {
        String videoId = item.getAsJsonObject("id").get("videoId").getAsString();
        JsonObject snip = item.getAsJsonObject("snippet");

        String title   = snip.get("title").getAsString();
        String channel = snip.get("channelTitle").getAsString();
        String thumb   = "";

        JsonObject thumbs = snip.getAsJsonObject("thumbnails");
        if (thumbs != null) {
            JsonObject hq = thumbs.getAsJsonObject("high");
            if (hq != null) thumb = hq.get("url").getAsString();
            else {
                JsonObject med = thumbs.getAsJsonObject("medium");
                if (med != null) thumb = med.get("url").getAsString();
            }
        }

        long dur = durations.getOrDefault(videoId, 0L);
        String id = "yt:" + videoId;
        return new Track(id, title, channel, "", dur, thumb, "", Track.Source.YOUTUBE);
    }

    /** Batch duration fetch via videos?part=contentDetails */
    private java.util.Map<String, Long> fetchDurations(List<String> videoIds) {
        java.util.Map<String, Long> map = new java.util.HashMap<>();
        if (videoIds.isEmpty()) return map;
        try {
            String ids  = String.join(",", videoIds);
            String url  = YT_API + "/videos"
                    + "?part=contentDetails"
                    + "&id=" + encode(ids)
                    + "&key=" + config.youtubeApiKey;
            JsonObject body = httpGet(url);
            if (body == null) return map;
            JsonArray items = body.getAsJsonArray("items");
            if (items == null) return map;
            for (JsonElement el : items) {
                JsonObject obj = el.getAsJsonObject();
                String vid  = obj.get("id").getAsString();
                String iso  = obj.getAsJsonObject("contentDetails").get("duration").getAsString();
                map.put(vid, parseIso8601Duration(iso));
            }
        } catch (Exception e) {
            MusicPlayerMod.LOGGER.warn("YouTube duration fetch error: {}", e.getMessage());
        }
        return map;
    }

    /** ISO 8601 duration → millis. E.g. PT3M45S → 225000 */
    private static long parseIso8601Duration(String iso) {
        long ms = 0;
        // Strip PT prefix
        String s = iso.replace("PT", "");
        int hIdx = s.indexOf('H');
        int mIdx = s.indexOf('M');
        int sIdx = s.indexOf('S');
        try {
            if (hIdx > 0) { ms += Long.parseLong(s.substring(0, hIdx)) * 3600000; s = s.substring(hIdx + 1); mIdx = s.indexOf('M'); sIdx = s.indexOf('S'); }
            if (mIdx > 0) { ms += Long.parseLong(s.substring(0, mIdx)) * 60000;   s = s.substring(mIdx + 1); sIdx = s.indexOf('S'); }
            if (sIdx > 0) { ms += Long.parseLong(s.substring(0, sIdx)) * 1000; }
        } catch (NumberFormatException ignored) {}
        return ms;
    }

    // ── yt-dlp integration ────────────────────────────────────────────────────

    private static String resolveViaYtDlp(String videoId) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "yt-dlp",
                    "--get-url",
                    "-f", "bestaudio[ext=m4a]/bestaudio/best",
                    "--no-playlist",
                    YT_WATCH + videoId
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            proc.waitFor(15, TimeUnit.SECONDS);
            if (!out.isBlank() && out.startsWith("http")) return out;
        } catch (Exception e) {
            // yt-dlp not on PATH — expected; logged at INFO level once
        }
        return null;
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private JsonObject httpGet(String url) {
        try {
            Request req = new Request.Builder().url(url).build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    MusicPlayerMod.LOGGER.warn("YouTube API HTTP {}: {}", resp.code(), url);
                    return null;
                }
                return JsonParser.parseString(resp.body().string()).getAsJsonObject();
            }
        } catch (Exception e) {
            MusicPlayerMod.LOGGER.warn("YouTube HTTP error: {}", e.getMessage());
            return null;
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
