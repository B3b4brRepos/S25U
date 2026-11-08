package dev.ollefx.musicplayer.audio;

import dev.ollefx.musicplayer.MusicPlayerMod;
import dev.ollefx.musicplayer.config.MusicConfig;
import dev.ollefx.musicplayer.integration.SpotifyIntegration;
import dev.ollefx.musicplayer.integration.YouTubeIntegration;
import dev.ollefx.musicplayer.lyrics.LyricsManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Central playback controller.
 *
 * Network/decode work runs on the executor; playback state changes on the calling thread.
 * UI reads state via getters — thread-safe where it matters via volatile/synchronized.
 */
public class MusicManager {

    public enum State { STOPPED, LOADING, PLAYING, PAUSED, ERROR }

    private final AudioBackend      audio;
    private final SpotifyIntegration spotify;
    private final YouTubeIntegration youtube;
    private       LyricsManager     lyrics;

    private final PlaybackQueue  queue     = new PlaybackQueue();
    private final List<Track>    favorites = new ArrayList<>();
    private final Set<String>    favIds    = new LinkedHashSet<>();
    private final List<Track>    recent    = new ArrayList<>(30);

    private volatile State   state       = State.STOPPED;
    private volatile Track   nowPlaying  = null;
    private volatile String  errorMsg    = null;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "musicplayer-manager");
        t.setDaemon(true);
        return t;
    });

    private Future<?> pendingLoad;
    private final List<Consumer<State>> stateListeners = new ArrayList<>();

    public MusicManager(AudioBackend audio,
                        SpotifyIntegration spotify,
                        YouTubeIntegration youtube,
                        LyricsManager lyrics) {
        this.audio   = audio;
        this.spotify = spotify;
        this.youtube = youtube;
        this.lyrics  = lyrics;
    }

    public void setLyricsManager(LyricsManager l) { this.lyrics = l; }

    // ── Playback ──────────────────────────────────────────────────────────────

    public synchronized void play(Track track) {
        if (track == null) return;

        cancelPendingLoad();
        nowPlaying = track;
        setState(State.LOADING);

        // Add to recent
        recent.remove(track);
        recent.add(0, track);
        if (recent.size() > 30) recent.remove(30);

        pendingLoad = executor.submit(() -> {
            try {
                String audioUrl = resolveAudioUrl(track);
                if (audioUrl == null || audioUrl.isBlank()) {
                    setError("No playable URL for: " + track.title());
                    return;
                }
                audio.play(audioUrl, 0);
                setState(State.PLAYING);

                // Load lyrics asynchronously
                if (lyrics != null) lyrics.loadForTrack(track);
            } catch (Exception e) {
                setError("Playback error: " + e.getMessage());
                MusicPlayerMod.LOGGER.error("MusicManager: play failed", e);
            }
        });
    }

    public synchronized void pause() {
        if (state == State.PLAYING) {
            audio.pause();
            setState(State.PAUSED);
        }
    }

    public synchronized void resume() {
        if (state == State.PAUSED) {
            audio.resume();
            setState(State.PLAYING);
        }
    }

    public synchronized void togglePause() {
        if (state == State.PLAYING) pause();
        else if (state == State.PAUSED) resume();
    }

    public synchronized void next() {
        Track t = queue.next();
        if (t != null) play(t);
        else { audio.stop(); setState(State.STOPPED); }
    }

    public synchronized void previous() {
        // If > 3s into track, restart; otherwise go to previous
        if (audio.getPositionMs() > 3000) {
            seek(0);
        } else {
            Track t = queue.previous();
            if (t != null) play(t);
        }
    }

    public synchronized void seek(long ms) {
        if (nowPlaying != null) audio.seek(ms);
    }

    public synchronized void setVolume(float v) {
        audio.setVolume(v);
    }

    public synchronized void stop() {
        cancelPendingLoad();
        audio.stop();
        nowPlaying = null;
        if (lyrics != null) lyrics.clear();
        setState(State.STOPPED);
    }

    // ── Queue ─────────────────────────────────────────────────────────────────

    public PlaybackQueue getQueue()       { return queue; }
    public void addToQueue(Track t)       { queue.add(t); }
    public void clearQueue()              { queue.clear(); }

    public void setShuffle(boolean s)     { queue.setShuffle(s); }
    public void setRepeat(MusicConfig.RepeatMode r) { queue.setRepeatMode(r); }

    // ── Favorites ─────────────────────────────────────────────────────────────

    public void addFavorite(Track t) {
        if (favIds.add(t.id())) favorites.add(0, t);
    }

    public void removeFavorite(Track t) {
        if (favIds.remove(t.id())) favorites.removeIf(f -> f.id().equals(t.id()));
    }

    public boolean isFavorite(Track t)  { return t != null && favIds.contains(t.id()); }
    public List<Track> getFavorites()   { return List.copyOf(favorites); }
    public List<Track> getRecent()      { return List.copyOf(recent); }

    // ── State ─────────────────────────────────────────────────────────────────

    public State   getState()           { return state; }
    public Track   getNowPlaying()      { return nowPlaying; }
    public String  getErrorMessage()    { return errorMsg; }
    public long    getPlaybackPositionMs() { return audio.getPositionMs(); }
    public long    getDurationMs()      { return audio.getDurationMs(); }
    public float[] getVisualizerBands() { return audio.getVisualizerBands(); }

    public void addStateListener(Consumer<State> l) { stateListeners.add(l); }

    // ── URL resolution ────────────────────────────────────────────────────────

    /**
     * Returns a directly playable audio URL for the track.
     * Spotify: 30-second preview URL from track object.
     * YouTube: direct audio stream resolved via yt-dlp equivalent (InputStream).
     * Falls back to track.audioUrl() if already a playable URL.
     */
    private String resolveAudioUrl(Track track) {
        if (track.audioUrl() != null && !track.audioUrl().isBlank()) {
            return track.audioUrl();
        }
        return switch (track.source()) {
            case SPOTIFY -> spotify.getPreviewUrl(track.id());
            case YOUTUBE -> youtube.getAudioStreamUrl(track.id());
            case LOCAL   -> track.audioUrl();
        };
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void setState(State s) {
        state = s;
        errorMsg = null;
        for (Consumer<State> l : stateListeners) {
            try { l.accept(s); } catch (Exception ignored) {}
        }
    }

    private void setError(String msg) {
        state    = State.ERROR;
        errorMsg = msg;
        MusicPlayerMod.LOGGER.warn("MusicManager error: {}", msg);
        for (Consumer<State> l : stateListeners) {
            try { l.accept(State.ERROR); } catch (Exception ignored) {}
        }
    }

    private void cancelPendingLoad() {
        if (pendingLoad != null && !pendingLoad.isDone()) {
            pendingLoad.cancel(true);
        }
    }

    public void shutdown() {
        cancelPendingLoad();
        executor.shutdownNow();
        audio.stop();
        MusicPlayerMod.LOGGER.info("MusicManager: shutdown");
    }
}
