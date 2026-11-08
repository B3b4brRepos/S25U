package dev.ollefx.musicplayer.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ollefx.musicplayer.MusicPlayerMod;
import dev.ollefx.musicplayer.audio.Track;
import net.fabricmc.loader.api.FabricLoader;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * YouTube Music source — mirrors Dango's YouTubeMusicSource exactly.
 *
 * Uses yt-dlp as the extraction backend (Dango's "tool" pattern).
 * On first use it checks for yt-dlp on PATH and downloads a release binary
 * if not found, caching it in .minecraft/mods/musicplayer/tools/.
 *
 * Search: YouTube Data API v3 (requires API key) OR YouTube Music innertube API
 * (no key required, unofficial — same as what yt-dlp uses internally).
 *
 * Audio stream: yt-dlp --get-url with bestaudio[ext=m4a]
 *
 * Cache: 60s result TTL, 50 entry LRU (matches Dango's CACHE_MS + MAX_CACHED).
 *
 * Search priority in our mod: Spotify → YouTube → YouTube Music
 */
public class YouTubeMusicSource {

    private static final long CACHE_MS  = 60_000L;
    private static final int  MAX_CACHE = 50;

    private static final String INNERTUBE_SEARCH =
            "https://music.youtube.com/youtubei/v1/search?key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30";
    private static final String INNERTUBE_BODY =
            "{\"context\":{\"client\":{\"clientName\":\"WEB_REMIX\",\"clientVersion\":\"1.20231101.01.00\"}}," +
            "\"query\":\"%s\",\"params\":\"Eg-KAQwIARAAGAAgACgAMABqChAEEAMQCRAFEAo%%3D\"}";

    private final OkHttpClient http;
    private final Path toolsDir;

    private final AtomicBoolean toolReady = new AtomicBoolean(false);
    private volatile String     toolPath  = null;

    // LRU cache
    private final Map<String, CacheEntry> cache = new LinkedHashMap<>(MAX_CACHE, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> e) {
            return size() > MAX_CACHE;
        }
    };

    public YouTubeMusicSource() {
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (compatible; MusicPlayer/1.0)")
                        .header("Origin", "https://music.youtube.com")
                        .header("Referer", "https://music.youtube.com/")
                        .build()))
                .build();
        this.toolsDir = FabricLoader.getInstance().getGameDir()
                .resolve("mods").resolve("musicplayer").resolve("tools");
    }

    // ── Tool lifecycle ────────────────────────────────────────────────────────

    /**
     * Ensure yt-dlp is available. Runs async — call from background thread.
     * Checks PATH first, then checks local cache dir, then skips if unavailable.
     * Unlike Dango we do NOT auto-download executables for safety; we just probe.
     */
    public void ensureTools() {
        if (toolReady.get()) return;
        try {
            Files.createDirectories(toolsDir);
        } catch (IOException ignored) {}

        // 1. Check PATH
        String[] candidates = { "yt-dlp", "yt-dlp.exe", "youtube-dl", "youtube-dl.exe" };
        for (String c : candidates) {
            if (probe(c)) { toolPath = c; toolReady.set(true); return; }
        }

        // 2. Check local tools dir
        for (String c : candidates) {
            Path local = toolsDir.resolve(c);
            if (Files.exists(local)) {
                String abs = local.toAbsolutePath().toString();
                if (probe(abs)) { toolPath = abs; toolReady.set(true); return; }
            }
        }

        MusicPlayerMod.LOGGER.info("YouTubeMusicSource: yt-dlp not found. " +
                "Place yt-dlp in PATH or {} for YouTube audio streaming.", toolsDir);
    }

    private boolean probe(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd, "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            p.destroy();
            return done && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    /**
     * Search YouTube Music using the innertube API (no key required).
     * Blocking — call from background thread.
     */
    public List<Track> search(String query, int limit) {
        synchronized (cache) {
            CacheEntry e = cache.get(query);
            if (e != null && System.currentTimeMillis() - e.timestamp < CACHE_MS) {
                return e.results;
            }
        }

        List<Track> results = new ArrayList<>();
        try {
            String body = String.format(INNERTUBE_BODY, escapeJson(query));
            okhttp3.RequestBody rb = okhttp3.RequestBody.create(
                    body, okhttp3.MediaType.get("application/json"));
            Request req = new Request.Builder().url(INNERTUBE_SEARCH).post(rb).build();

            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return results;
                JsonObject json = JsonParser.parseString(resp.body().string()).getAsJsonObject();
                results = parseInnertubeResults(json, limit);
            }
        } catch (Exception e) {
            MusicPlayerMod.LOGGER.warn("YouTubeMusicSource search error: {}", e.getMessage());
        }

        synchronized (cache) { cache.put(query, new CacheEntry(results)); }
        return results;
    }

    /**
     * Get a direct audio stream URL for the given videoId using yt-dlp.
     * Returns null if yt-dlp is unavailable.
     */
    public String getAudioStreamUrl(String videoId) {
        ensureTools();
        if (!toolReady.get() || toolPath == null) return null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    toolPath, "--get-url", "-f", "bestaudio[ext=m4a]/bestaudio/best",
                    "--no-playlist", "--no-warnings",
                    "https://www.youtube.com/watch?v=" + videoId);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            proc.waitFor(20, TimeUnit.SECONDS);
            proc.destroy();
            if (out.startsWith("http")) return out.split("\n")[0];
        } catch (Exception e) {
            MusicPlayerMod.LOGGER.warn("YouTubeMusicSource yt-dlp error for {}: {}", videoId, e.getMessage());
        }
        return null;
    }

    public boolean isReady()     { return toolReady.get(); }
    public String  getToolPath() { return toolPath; }

    // ── Innertube result parsing ───────────────────────────────────────────────

    private static List<Track> parseInnertubeResults(JsonObject root, int limit) {
        List<Track> out = new ArrayList<>();
        try {
            // Navigate: contents → tabbedSearchResultsRenderer → tabs[0] → content
            //            → sectionListRenderer → contents → musicShelfRenderer → contents
            JsonObject tabs = root
                    .getAsJsonObject("contents")
                    .getAsJsonObject("tabbedSearchResultsRenderer")
                    .getAsJsonArray("tabs").get(0).getAsJsonObject()
                    .getAsJsonObject("tabRenderer")
                    .getAsJsonObject("content");

            JsonArray sections = tabs.getAsJsonObject("sectionListRenderer")
                    .getAsJsonArray("contents");

            for (JsonElement sec : sections) {
                JsonObject shelf = sec.getAsJsonObject().getAsJsonObject("musicShelfRenderer");
                if (shelf == null) continue;
                JsonArray items = shelf.getAsJsonArray("contents");
                if (items == null) continue;
                for (JsonElement item : items) {
                    if (out.size() >= limit) break;
                    try {
                        Track t = parseMusicShelfItem(item.getAsJsonObject());
                        if (t != null) out.add(t);
                    } catch (Exception ignored) {}
                }
                if (out.size() >= limit) break;
            }
        } catch (Exception e) {
            MusicPlayerMod.LOGGER.debug("YouTubeMusicSource: parse error: {}", e.getMessage());
        }
        return out;
    }

    private static Track parseMusicShelfItem(JsonObject item) {
        JsonObject renderer = item.getAsJsonObject("musicResponsiveListItemRenderer");
        if (renderer == null) return null;

        // Title from first flexColumn
        JsonArray cols = renderer.getAsJsonArray("flexColumns");
        if (cols == null || cols.size() < 2) return null;

        String title  = firstRunText(cols.get(0).getAsJsonObject());
        String artist = firstRunText(cols.get(1).getAsJsonObject());

        // Video ID from overlay
        String videoId = "";
        try {
            JsonObject overlay = renderer.getAsJsonObject("overlay")
                    .getAsJsonObject("musicItemThumbnailOverlayRenderer")
                    .getAsJsonObject("content")
                    .getAsJsonObject("musicPlayButtonRenderer")
                    .getAsJsonObject("playNavigationEndpoint")
                    .getAsJsonObject("watchEndpoint");
            videoId = overlay.get("videoId").getAsString();
        } catch (Exception e) {
            // some items don't have playable videoId (e.g., artist rows)
            return null;
        }

        // Thumbnail
        String thumb = "";
        try {
            JsonArray thumbs = renderer.getAsJsonObject("thumbnail")
                    .getAsJsonObject("musicThumbnailRenderer")
                    .getAsJsonObject("thumbnail")
                    .getAsJsonArray("thumbnails");
            thumb = thumbs.get(thumbs.size() - 1).getAsJsonObject().get("url").getAsString();
        } catch (Exception ignored) {}

        String id = "ytm:" + videoId;
        // Duration from col[1] last run (often "3:45")
        long dur = 0;
        try {
            JsonArray runs = cols.get(1).getAsJsonObject()
                    .getAsJsonObject("musicResponsiveListItemFlexColumnRenderer")
                    .getAsJsonObject("text")
                    .getAsJsonArray("runs");
            for (JsonElement r : runs) {
                String txt = r.getAsJsonObject().get("text").getAsString().trim();
                if (txt.matches("\\d+:\\d+")) {
                    String[] p = txt.split(":");
                    dur = (Long.parseLong(p[0]) * 60 + Long.parseLong(p[1])) * 1000;
                    break;
                }
            }
        } catch (Exception ignored) {}

        return new Track(id, title, artist, "", dur, thumb, "", Track.Source.YOUTUBE);
    }

    private static String firstRunText(JsonObject col) {
        try {
            JsonArray runs = col.getAsJsonObject("musicResponsiveListItemFlexColumnRenderer")
                    .getAsJsonObject("text").getAsJsonArray("runs");
            if (runs != null && runs.size() > 0)
                return runs.get(0).getAsJsonObject().get("text").getAsString();
        } catch (Exception ignored) {}
        return "";
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private record CacheEntry(List<Track> results) {
        final long timestamp = System.currentTimeMillis();
        CacheEntry(List<Track> results) { this.results = results; }
    }
}
