package dev.ollefx.musicplayer.audio;

/** Immutable track metadata shared across all systems. */
public record Track(
        String id,          // platform-unique ID (spotify:<id> or yt:<videoId>)
        String title,
        String artist,
        String album,
        long   durationMs,
        String artworkUrl,  // HTTP URL for album art thumbnail
        String audioUrl,    // direct-playable audio stream URL (may be preview only)
        Source source
) {
    public enum Source { SPOTIFY, YOUTUBE, LOCAL }

    /** Short display label: "Artist — Title" */
    public String label() {
        return artist.isEmpty() ? title : artist + " \u2014 " + title;
    }
}
