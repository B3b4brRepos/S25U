package dev.ollefx.musicplayer.audio;

import dev.ollefx.musicplayer.config.MusicConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages ordered + shuffled playback.
 * All mutation is synchronized; read-only snapshots are CopyOnWrite.
 */
public class PlaybackQueue {

    private final List<Track> originalOrder = new ArrayList<>();
    private final List<Track> playOrder     = new ArrayList<>();
    private final List<Track> history       = new ArrayList<>(50);

    private int     currentIndex   = -1;
    private boolean shuffle        = false;
    private MusicConfig.RepeatMode repeatMode = MusicConfig.RepeatMode.OFF;

    // ── Mutation ──────────────────────────────────────────────────────────────

    public synchronized void add(Track t) {
        originalOrder.add(t);
        playOrder.add(t);
        if (shuffle) Collections.shuffle(playOrder);
        if (currentIndex < 0) currentIndex = 0;
    }

    public synchronized void addAll(List<Track> tracks) {
        originalOrder.addAll(tracks);
        playOrder.addAll(tracks);
        if (shuffle) Collections.shuffle(playOrder);
        if (currentIndex < 0 && !playOrder.isEmpty()) currentIndex = 0;
    }

    public synchronized void remove(int index) {
        if (index < 0 || index >= playOrder.size()) return;
        playOrder.remove(index);
        originalOrder.remove(index);
        if (currentIndex >= playOrder.size()) currentIndex = playOrder.size() - 1;
    }

    public synchronized void clear() {
        originalOrder.clear();
        playOrder.clear();
        currentIndex = -1;
    }

    public synchronized void jumpTo(int index) {
        if (index >= 0 && index < playOrder.size()) {
            if (currentIndex >= 0 && currentIndex < playOrder.size()) {
                history.add(0, playOrder.get(currentIndex));
                if (history.size() > 50) history.remove(50);
            }
            currentIndex = index;
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    public synchronized Track current() {
        if (currentIndex < 0 || currentIndex >= playOrder.size()) return null;
        return playOrder.get(currentIndex);
    }

    /** Advance to next track; returns null if queue ended and repeat is OFF. */
    public synchronized Track next() {
        if (playOrder.isEmpty()) return null;
        if (repeatMode == MusicConfig.RepeatMode.ONE) return current();

        Track cur = current();
        if (cur != null) { history.add(0, cur); if (history.size() > 50) history.remove(50); }

        currentIndex++;
        if (currentIndex >= playOrder.size()) {
            if (repeatMode == MusicConfig.RepeatMode.ALL) {
                currentIndex = 0;
                if (shuffle) Collections.shuffle(playOrder);
            } else {
                currentIndex = playOrder.size(); // past end
                return null;
            }
        }
        return current();
    }

    public synchronized Track previous() {
        if (playOrder.isEmpty()) return null;
        if (!history.isEmpty()) {
            Track prev = history.remove(0);
            // find it in playOrder and jump
            int idx = playOrder.indexOf(prev);
            if (idx >= 0) { currentIndex = idx; return current(); }
        }
        currentIndex = Math.max(0, currentIndex - 1);
        return current();
    }

    // ── Shuffle / Repeat ──────────────────────────────────────────────────────

    public synchronized void setShuffle(boolean s) {
        this.shuffle = s;
        Track cur = current();
        if (s) {
            Collections.shuffle(playOrder);
            // Keep current at front
            if (cur != null) {
                playOrder.remove(cur);
                playOrder.add(0, cur);
                currentIndex = 0;
            }
        } else {
            // Restore original order, preserve position
            playOrder.clear();
            playOrder.addAll(originalOrder);
            currentIndex = cur != null ? playOrder.indexOf(cur) : 0;
            if (currentIndex < 0) currentIndex = 0;
        }
    }

    public synchronized void setRepeatMode(MusicConfig.RepeatMode mode) {
        this.repeatMode = mode;
    }

    // ── Snapshots ─────────────────────────────────────────────────────────────

    public List<Track> getPlayOrder() { return List.copyOf(playOrder); }
    public List<Track> getHistory()   { return List.copyOf(history); }
    public int getCurrentIndex()      { return currentIndex; }
    public boolean isEmpty()          { return playOrder.isEmpty(); }
    public int size()                 { return playOrder.size(); }
    public boolean isShuffle()        { return shuffle; }
    public MusicConfig.RepeatMode getRepeatMode() { return repeatMode; }
}
