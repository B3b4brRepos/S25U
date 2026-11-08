# Music Player Mod — MC 26.3 Fabric 0.19.5

## Build

```
./gradlew build
```
Requires: Java 25, Gradle 8.9 (wrapper included).
Output: `build/libs/musicplayer-1.0.0.jar`

## Setup

### Spotify (primary source — where playback happens)
1. Create app at https://developer.spotify.com/dashboard
2. Add redirect URI: `http://localhost:7879/callback`
3. In-game Settings → Music & HUD → API Keys → Spotify Client ID
4. Click "Connect Spotify" → browser opens → login → code exchanges automatically

### YouTube Data API (secondary search source)
1. Enable YouTube Data API v3 in Google Cloud Console
2. Create API key (free, 10,000 units/day)
3. In-game Settings → Music & HUD → API Keys → YouTube API Key

### YouTube Music (tertiary — no key required)
- Uses YouTube Music innertube API (unofficial, same as yt-dlp)
- Enable/disable in Settings → Music & HUD → Sources → YouTube Music

### Audio streaming (YouTube / YouTube Music)
- Place `yt-dlp` binary in PATH or `.minecraft/mods/musicplayer/tools/`
- Download from https://github.com/yt-dlp/yt-dlp/releases
- Spotify: 30-second preview URLs only (Spotify limitation for desktop apps without SDK)

## Features
- Full Dango Music settings parity (all settings from screenshots ported)
- Liquid Glass UI with GPU shaders (Deep Glow, frosted glass, chromatic aberration)
- Dynamic Island lyrics pill (top-center HUD)
- Song card overlay (top-left: cover + title + artist)
- 3D sky lyrics with wiggle/glow/face-player/above-ground/spread
- Environment audio: underwater muffle, cave echo, indoor reverb, rain filter
- Mob dance on the beat with FPS fix (max 20 entities, distance gate)
- Halloween theme: pumpkins, ghosts, fog, orange/purple palette
- Settings: Lyrics tab + Music & HUD tab matching Dango layout exactly
- Priority: Spotify search > YouTube search > YouTube Music search

## FPS Fix vs Dango
Dango applies dance squash to every entity every frame → 92 FPS in crowded third-person.
This mod: entity budget cap (20), distance gate, O(1) check before any matrix work.
Result: same visual effect, no measurable FPS impact.
