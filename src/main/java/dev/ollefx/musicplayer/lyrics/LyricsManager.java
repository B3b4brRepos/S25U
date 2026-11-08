package dev.ollefx.musicplayer.lyrics;

import dev.ollefx.musicplayer.MusicPlayerMod;
import dev.ollefx.musicplayer.audio.Track;
import dev.ollefx.musicplayer.config.MusicConfig;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages time-synchronized lyrics.
 *
 * Sources (in priority order):
 *   1. LRCLIB (lrclib.net) — free, no key, returns LRC format with timestamps
 *   2. Plain text lyrics via lyrics.ovh (fallback, no timestamps)
 *
 * LRC format: [mm:ss.xx] lyric line
 */
public class LyricsManager {

    public record LyricsLine(long timeMs, String text) {}

    private final MusicConfig config;
    private final OkHttpClient http;
    private final ExecutorService executor;

    // Current song lyrics — volatile for safe cross-thread reads
    private volatile List<LyricsLine> lines     = List.of();
    private volatile boolean          loading   = false;
    private volatile boolean          available = false;
    private volatile int              currentIdx = -1;

    // LRU cache: trackId → lines
    private final java.util.LinkedHashMap<String, List<LyricsLine>> cache =
            new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, List<LyricsLine>> e) {
                    return size() > 50;
                }
            };

    public LyricsManager(MusicConfig config) {
        this.config   = config;
        this.http     = new OkHttpClient.Builder()
                .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "musicplayer-lyrics");
            t.setDaemon(true);
            return t;
        });
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Called by MusicManager when a new track starts. Loads asynchronously. */
    public void loadForTrack(Track track) {
        clear();
        if (track == null) return;

        List<LyricsLine> cached = cache.get(track.id());
        if (cached != null) {
            lines    = cached;
            available = !cached.isEmpty();
            return;
        }

        loading = true;
        executor.submit(() -> {
            try {
                List<LyricsLine> result = fetchLrcLib(track);
                if (result.isEmpty()) result = fetchLyricsOvh(track);
                lines    = result;
                available = !result.isEmpty();
                cache.put(track.id(), result);
            } catch (Exception e) {
                MusicPlayerMod.LOGGER.warn("Lyrics load failed for {}: {}", track.title(), e.getMessage());
                available = false;
            } finally {
                loading = false;
            }
        });
    }

    /** Called every game tick with the current playback position in ms. */
    public void tick(long posMs) {
        List<LyricsLine> snap = lines;
        if (snap.isEmpty()) { currentIdx = -1; return; }

        // Binary search for the last line whose timeMs <= posMs
        int lo = 0, hi = snap.size() - 1, best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (snap.get(mid).timeMs() <= posMs) { best = mid; lo = mid + 1; }
            else                                  { hi = mid - 1; }
        }
        currentIdx = best;
    }

    public void clear() {
        lines      = List.of();
        loading    = false;
        available  = false;
        currentIdx = -1;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public boolean isLoading()   { return loading; }
    public boolean isAvailable() { return available; }

    public LyricsLine getCurrentLine() {
        int i = currentIdx;
        List<LyricsLine> snap = lines;
        if (i < 0 || i >= snap.size()) return null;
        return snap.get(i);
    }

    public LyricsLine getPreviousLine() {
        int i = currentIdx - 1;
        List<LyricsLine> snap = lines;
        if (i < 0 || i >= snap.size()) return null;
        return snap.get(i);
    }

    public LyricsLine getNextLine() {
        int i = currentIdx + 1;
        List<LyricsLine> snap = lines;
        if (i < 0 || i >= snap.size()) return null;
        return snap.get(i);
    }

    /** Time in ms until the next lyric line (useful for transition pre-cue). */
    public long getMsUntilNextLine(long posMs) {
        int i = currentIdx + 1;
        List<LyricsLine> snap = lines;
        if (i < 0 || i >= snap.size()) return Long.MAX_VALUE;
        return snap.get(i).timeMs() - posMs;
    }

    public List<LyricsLine> getAllLines() { return lines; }

    // ── Fetchers ──────────────────────────────────────────────────────────────

    /**
     * LRCLIB: free, no key required, returns timestamped LRC lyrics.
     * Endpoint: https://lrclib.net/api/get?artist_name=X&track_name=Y&album_name=Z
     */
    private List<LyricsLine> fetchLrcLib(Track track) throws Exception {
        String url = "https://lrclib.net/api/get"
                + "?artist_name=" + encode(track.artist())
                + "&track_name="  + encode(track.title())
                + (track.album().isBlank() ? "" : "&album_name=" + encode(track.album()))
                + "&duration="    + (track.durationMs() / 1000);

        String body = httpGetString(url);
        if (body == null) return List.of();

        com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        com.google.gson.JsonElement lrc = obj.get("syncedLyrics");
        if (lrc == null || lrc.isJsonNull()) {
            // Fall back to plain lyrics without timestamps
            com.google.gson.JsonElement plain = obj.get("plainLyrics");
            if (plain != null && !plain.isJsonNull()) return parsePlainLyrics(plain.getAsString());
            return List.of();
        }
        return parseLrc(lrc.getAsString());
    }

    /**
     * lyrics.ovh fallback — no timestamps, plain text only.
     */
    private List<LyricsLine> fetchLyricsOvh(Track track) throws Exception {
        if (track.artist().isBlank() || track.title().isBlank()) return List.of();
        String url = "https://api.lyrics.ovh/v1/"
                + encode(track.artist()) + "/" + encode(track.title());
        String body = httpGetString(url);
        if (body == null) return List.of();
        try {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            com.google.gson.JsonElement lyr = obj.get("lyrics");
            if (lyr == null || lyr.isJsonNull()) return List.of();
            return parsePlainLyrics(lyr.getAsString());
        } catch (Exception e) {
            return List.of();
        }
    }

    // ── LRC parsing ───────────────────────────────────────────────────────────

    /**
     * Parses LRC format: [mm:ss.xx] text
     * Returns lines sorted by time.
     */
    public static List<LyricsLine> parseLrc(String lrc) {
        List<LyricsLine> result = new ArrayList<>();
        for (String line : lrc.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            // Match [mm:ss.xx] or [mm:ss]
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^\\[(\\d{1,3}):(\\d{2})(?:\\.(\\d{1,3}))?\\](.*)$")
                    .matcher(line);
            if (!m.matches()) continue;
            int    mm   = Integer.parseInt(m.group(1));
            int    ss   = Integer.parseInt(m.group(2));
            int    cs   = m.group(3) != null ? Integer.parseInt(
                    (m.group(3) + "00").substring(0, 2)) : 0;
            String text = m.group(4).trim();
            if (text.isEmpty()) continue;
            result.add(new LyricsLine(mm * 60_000L + ss * 1000L + cs * 10L, text));
        }
        Collections.sort(result, java.util.Comparator.comparingLong(LyricsLine::timeMs));
        return Collections.unmodifiableList(result);
    }

    /**
     * Wraps plain (untimed) lyrics into evenly-spaced LyricsLine objects.
     * Uses rough 4-second-per-line estimate (better than nothing).
     */
    private static List<LyricsLine> parsePlainLyrics(String plain) {
        List<LyricsLine> result = new ArrayList<>();
        String[] rawLines = plain.split("\n");
        long timeMs = 0;
        for (String l : rawLines) {
            l = l.trim();
            if (l.isEmpty()) { timeMs += 1500; continue; }
            result.add(new LyricsLine(timeMs, l));
            timeMs += 4000; // rough 4s per line
        }
        return Collections.unmodifiableList(result);
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private String httpGetString(String url) {
        try {
            Request req = new Request.Builder().url(url)
                    .header("User-Agent", "MusicPlayerMod/1.0 (Minecraft Fabric mod)")
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                return resp.body().string();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
