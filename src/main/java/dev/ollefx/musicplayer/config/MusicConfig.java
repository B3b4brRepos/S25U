package dev.ollefx.musicplayer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.ollefx.musicplayer.MusicPlayerMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted as config/musicplayer.json.
 * All fields are public — accessed directly, no boilerplate getters.
 */
public class MusicConfig {

    // ── API credentials (filled by user via Settings screen) ─────────────────
    public String spotifyClientId     = "";
    public String spotifyClientSecret = "";
    public String youtubeApiKey       = "";

    // Stored OAuth tokens (written back after login)
    public String spotifyAccessToken  = "";
    public String spotifyRefreshToken = "";
    public long   spotifyTokenExpiry  = 0L; // epoch millis

    // ── UI ───────────────────────────────────────────────────────────────────
    public float uiScale             = 1.0f;      // 0.5 – 2.0
    public float animationIntensity  = 1.0f;      // 0.0 = no animations, 1.0 = full
    public boolean reducedMotion     = false;
    public boolean uiSounds          = true;
    public float   uiSoundVolume     = 0.6f;

    // ── Halloween theme ───────────────────────────────────────────────────────
    public boolean halloweenTheme      = true;
    public boolean halloweenParticles  = true;
    public boolean halloweenGhosts     = true;
    public boolean halloweenFog        = true;
    public boolean halloweenBackground = true;

    // ── Lyrics ───────────────────────────────────────────────────────────────
    public LyricsMode lyricsMode        = LyricsMode.HUD;
    public LyricTransition lyricTransition = LyricTransition.SLIDE_UP;
    public boolean pillClickThrough     = false;

    // Sky lyrics config
    public boolean skyLyricsGlow        = true;
    public float   skyLyricsOpacity     = 0.9f;
    public float   skyLyricsScale       = 1.0f;
    public float   skyLyricsHeight      = 12.0f;  // blocks above player eye
    public float   skyLyricsDistance    = 8.0f;   // blocks in front
    public float   skyLyricsGlowIntensity = 0.8f;
    public float   skyLyricsAnimSpeed   = 1.0f;

    // 2D HUD lyrics config
    public float   hudLyricsOpacity     = 0.9f;
    public int     hudLyricsFontSize    = 1;      // 1 or 2
    public float   hudLyricsX           = 0.5f;  // 0–1 relative screen X
    public float   hudLyricsY           = 0.75f; // 0–1 relative screen Y
    public int     hudLyricsColor       = 0xFFFFFFFF;
    public int     hudCurrentColor      = 0xFFFF8C00; // pumpkin orange

    // ── Playback ─────────────────────────────────────────────────────────────
    public float   musicVolume          = 0.8f;
    public boolean shuffle              = false;
    public RepeatMode repeatMode        = RepeatMode.OFF;

    // ── Graphics quality ──────────────────────────────────────────────────────
    public boolean blurBackground       = true;
    public boolean glassReflections     = true;
    public int     particleCount        = 100;    // max Halloween particles

    // ═══════════════════════════════════════════════════════════════════════
    // Dango-equivalent settings — mirrored 1:1 from screenshots
    // ═══════════════════════════════════════════════════════════════════════

    // ── 3D Lyrics placement ───────────────────────────────────────────────────
    public boolean lyrics3dEnabled      = true;
    public boolean lyricsFacePlayer     = true;   // lyrics rotate to face player
    public boolean lyricsKeepAway       = false;  // back off when you walk into them
    public boolean lyricsStayOnScreen   = false;  // glide back into view when you turn
    public boolean lyricsAboveGround    = true;   // never sink below eye level
    public float   lyricsSpread         = 60.0f;  // degrees L/R spread
    public float   lyricsScale          = 0.7f;   // lyric size (Dango default 0.7)
    public boolean lyricsSmoothFont     = false;  // smooth vs pixel font
    public boolean lyricsSplitLines     = true;   // split two lines written as one
    public int     lyricsColor          = 0xFFF7E1D7; // #F7E1D7 Dango default
    public boolean lyricsTranslation    = false;  // show translation under each line
    public int     lyricsTransColor     = 0xFF803D66; // #803D66 Dango default
    public boolean lyricsTransAnimation = true;
    public boolean lyricsTransWiggle    = true;

    // ── Deep Glow ─────────────────────────────────────────────────────────────
    public boolean glowEnabled          = true;
    public boolean glowNightOnly        = false;  // off in daylight
    public int     glowColorMode        = 0;      // 0=text, 1=pink, 2=blue, 3=gold
    public float   glowStrength         = 0.30f;  // intensity multiplier
    public float   glowRadius           = 0.50f;  // halo spread (0–1)

    // ── Motion / wiggle ───────────────────────────────────────────────────────
    public boolean wiggle               = true;   // low-fps jitter per letter
    public int     wiggleFps            = 4;      // jitter frame rate
    public float   wiggleAmount         = 0.80f;  // jitter strength
    public boolean textFlash            = false;  // words slam in synced to beat
    public boolean wordPop              = true;   // words pop in one by one
    public float   flashSize            = 1.0f;

    // ── Dance ─────────────────────────────────────────────────────────────────
    public boolean danceEnabled         = false;
    public boolean danceAuto            = false;  // auto-detect BPM from drum beats
    public boolean danceTap             = false;  // tap BPM manually
    public boolean danceSpin            = false;  // creatures spin steadily
    public float   danceAmount          = 1.3f;   // squash/stretch amount
    public int     danceOffsetMs        = 0;      // move beat earlier/later

    // ── Playback / Music & HUD tab ────────────────────────────────────────────
    public boolean volumeNormalization  = false;  // keep songs at similar loudness

    // ── Environment audio ─────────────────────────────────────────────────────
    public boolean envAudioEnabled      = true;
    public boolean envUnderwater        = true;
    public boolean envCaveEcho          = true;
    public boolean envIndoors           = true;
    public boolean envRain              = true;
    public float   envStrength          = 1.0f;  // 0–1 effect multiplier

    // ── Song card overlay ─────────────────────────────────────────────────────
    public float   songCardScale        = 1.0f;
    public boolean songCardShowPaused   = false;
    public float   songCardPosX         = 0.01f; // 0–1 relative screen X
    public float   songCardPosY         = 0.05f;

    // ── Dynamic Island ────────────────────────────────────────────────────────
    public boolean dynamicIslandGlass   = true;   // liquid glass background
    public boolean dynamicIslandMusic   = true;   // show what's playing
    public boolean dynamicIslandNotify  = true;   // expand on track change
    public float   dynamicIslandScale   = 1.0f;
    public float   dynamicIslandNotifyTime = 3.5f; // seconds notification stays

    // ── Now playing overlay ───────────────────────────────────────────────────
    public boolean nowPlayingOverlay    = true;   // show current song top-left
    public boolean nowPlayingCover      = true;   // show album cover top-left

    // ── Updates ───────────────────────────────────────────────────────────────
    public boolean checkForUpdates      = true;

    // ── YouTube Music source ──────────────────────────────────────────────────
    public boolean ytMusicEnabled       = true;
    // Source priority: 1=Spotify, 2=YouTube, 3=YouTube Music (matches ticket spec)
    public int     source1Priority      = 1; // Spotify
    public int     source2Priority      = 2; // YouTube
    public int     source3Priority      = 3; // YouTube Music

    // ── Enums ─────────────────────────────────────────────────────────────────
    public enum LyricsMode { OFF, HUD, SKY, BOTH }
    public enum RepeatMode { OFF, ONE, ALL }
    public enum LyricTransition {
        SLIDE_UP, LIQUID_MORPH, LETTER_REVEAL, WORD_REVEAL,
        BLUR_FOCUS, FADE_SCALE, SCROLL
    }

    // ── Persistence ───────────────────────────────────────────────────────────
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("musicplayer.json");
    }

    public static MusicConfig load() {
        Path path = configPath();
        if (Files.exists(path)) {
            try (Reader r = Files.newBufferedReader(path)) {
                MusicConfig cfg = GSON.fromJson(r, MusicConfig.class);
                if (cfg != null) {
                    MusicPlayerMod.LOGGER.info("Config loaded from {}", path);
                    return cfg;
                }
            } catch (IOException e) {
                MusicPlayerMod.LOGGER.warn("Failed to read config, using defaults: {}", e.getMessage());
            }
        }
        MusicConfig cfg = new MusicConfig();
        cfg.save();
        return cfg;
    }

    public void save() {
        Path path = configPath();
        try (Writer w = Files.newBufferedWriter(path)) {
            GSON.toJson(this, w);
        } catch (IOException e) {
            MusicPlayerMod.LOGGER.warn("Failed to save config: {}", e.getMessage());
        }
    }
}
