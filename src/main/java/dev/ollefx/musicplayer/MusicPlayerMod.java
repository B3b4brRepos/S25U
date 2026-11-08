package dev.ollefx.musicplayer;

import dev.ollefx.musicplayer.audio.AudioBackend;
import dev.ollefx.musicplayer.audio.DanceSystem;
import dev.ollefx.musicplayer.audio.EnvironmentAudio;
import dev.ollefx.musicplayer.audio.MusicManager;
import dev.ollefx.musicplayer.config.MusicConfig;
import dev.ollefx.musicplayer.integration.MusicSearchManager;
import dev.ollefx.musicplayer.integration.SpotifyIntegration;
import dev.ollefx.musicplayer.integration.YouTubeIntegration;
import dev.ollefx.musicplayer.integration.YouTubeMusicSource;
import dev.ollefx.musicplayer.lyrics.LyricsManager;
import dev.ollefx.musicplayer.render.*;
import dev.ollefx.musicplayer.screen.MusicKeybindings;
import dev.ollefx.musicplayer.screen.MusicScreen;
import dev.ollefx.musicplayer.util.ArtworkManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MusicPlayerMod implements ClientModInitializer {

    public static final String MOD_ID = "musicplayer";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // ── Singletons ────────────────────────────────────────────────────────────
    public static MusicConfig         config;
    public static MusicManager        musicManager;
    public static AudioBackend        audioBackend;
    public static SpotifyIntegration  spotify;
    public static YouTubeIntegration  youtube;
    public static YouTubeMusicSource  ytMusic;
    public static LyricsManager       lyricsManager;
    public static AnimationController animationController;
    public static DynamicLyricsPill   lyricsPill;
    public static HudLyricsRenderer   hudLyricsRenderer;
    public static SkyLyricsRenderer   skyLyricsRenderer;
    public static HalloweenEffects    halloweenEffects;
    public static GlowPass            glowPass;
    public static DanceSystem         danceSystem;
    public static EnvironmentAudio    environmentAudio;
    public static SongCardRenderer    songCardRenderer;
    public static ArtworkManager      artworkManager;

    @Override
    public void onInitializeClient() {
        LOGGER.info("Music Player initializing — bench lit, iron hot.");

        config             = MusicConfig.load();
        animationController = new AnimationController();
        audioBackend        = new AudioBackend();
        spotify             = new SpotifyIntegration(config);
        youtube             = new YouTubeIntegration(config);
        ytMusic             = new YouTubeMusicSource();
        lyricsManager       = new LyricsManager(config);
        danceSystem         = new DanceSystem();
        environmentAudio    = new EnvironmentAudio();
        artworkManager      = new ArtworkManager();

        musicManager = new MusicManager(audioBackend, spotify, youtube, lyricsManager);
        musicManager.setLyricsManager(lyricsManager);

        // Apply dance config
        syncDanceConfig();

        // Apply env audio config
        syncEnvAudioConfig();

        // Render systems
        lyricsPill       = new DynamicLyricsPill(animationController, lyricsManager, musicManager);
        hudLyricsRenderer = new HudLyricsRenderer(animationController, lyricsManager, musicManager, config);
        skyLyricsRenderer = new SkyLyricsRenderer(lyricsManager, musicManager, config);
        halloweenEffects  = new HalloweenEffects(animationController, config);
        songCardRenderer  = new SongCardRenderer(animationController);
        glowPass          = new GlowPass();

        // Start yt-dlp probe async
        Thread ytProbe = new Thread(() -> ytMusic.ensureTools(), "musicplayer-ytprobe");
        ytProbe.setDaemon(true);
        ytProbe.start();

        // Keybinding
        KeyBindingHelper.registerKeyBinding(MusicKeybindings.KEY_OPEN_MUSIC);

        // Tick
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Keybinding: open/close
            while (MusicKeybindings.KEY_OPEN_MUSIC.consumeClick()) {
                if (client.screen instanceof MusicScreen) client.setScreen(null);
                else client.setScreen(new MusicScreen());
            }

            animationController.tick(client);
            lyricsManager.tick(musicManager.getPlaybackPositionMs());
            lyricsPill.tick();
            songCardRenderer.tick();
            halloweenEffects.tick();
            environmentAudio.tick();

            // Dance: begin-frame resets entity budget
            danceSystem.beginFrame();
            // Feed audio bands to dance beat detector
            danceSystem.tickAudio(musicManager.getVisualizerBands());
            // Feed audio to environment DSP
            // (AudioBackend calls env.process() inside its decode loop — wired below)
        });

        // HUD render (note: Dynamic Island + lyrics pill are ALSO injected via
        // MixinHud.extractCameraOverlays for correct Z-order; HudRenderCallback
        // used here for 2D lyrics which don't need the camera overlay hook)
        HudRenderCallback.EVENT.register((guiGraphics, tickCounter) -> {
            Minecraft mc = Minecraft.getInstance();
            float pt = tickCounter.getGameTimeDeltaPartialTick(true);
            if (config.lyricsMode == MusicConfig.LyricsMode.HUD
                    || config.lyricsMode == MusicConfig.LyricsMode.BOTH) {
                hudLyricsRenderer.render(guiGraphics, mc, pt);
            }
            halloweenEffects.renderHud(guiGraphics, mc, pt);
        });

        // 3D sky lyrics
        skyLyricsRenderer.register();

        // Wire environment audio into AudioBackend
        audioBackend.setEnvironmentAudio(environmentAudio);

        LOGGER.info("Music Player ready. absolute olliE.");
    }

    /** Sync dance config fields → DanceSystem. */
    public static void syncDanceConfig() {
        if (danceSystem == null || config == null) return;
        danceSystem.enabled   = config.danceEnabled;
        danceSystem.auto      = config.danceAuto;
        danceSystem.spin      = config.danceSpin;
        danceSystem.amount    = config.danceAmount;
        danceSystem.offsetMs  = config.danceOffsetMs;
    }

    /** Sync env audio config fields → EnvironmentAudio. */
    public static void syncEnvAudioConfig() {
        if (environmentAudio == null || config == null) return;
        environmentAudio.enabled = config.envAudioEnabled;
        environmentAudio.water   = config.envUnderwater;
        environmentAudio.cave    = config.envCaveEcho;
        environmentAudio.indoor  = config.envIndoors;
        environmentAudio.rain    = config.envRain;
        environmentAudio.strength = config.envStrength;
    }

    public static void shutdown() {
        if (musicManager   != null) musicManager.shutdown();
        if (audioBackend   != null) audioBackend.shutdown();
        if (skyLyricsRenderer != null) skyLyricsRenderer.unregister();
        if (artworkManager != null) artworkManager.shutdown();
        if (config         != null) config.save();
        LOGGER.info("Music Player shut down.");
    }
}
