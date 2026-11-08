package dev.ollefx.musicplayer.audio;

import dev.ollefx.musicplayer.MusicPlayerMod;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns a dedicated OpenAL context (separate from Minecraft's).
 * Streams audio PCM into an AL streaming source via double-buffering.
 *
 * Threading:
 *   - Main thread  : init / shutdown / volume / seek / state queries
 *   - Decode thread: fills buffers from AudioDecoder
 *
 * Safety:
 *   - All AL calls happen on the decode thread after context switch,
 *     except init/shutdown which run on the main thread before/after any decoding.
 *   - Bounded ring of BUFFER_COUNT AL buffers; stale data is never queued.
 */
public class AudioBackend {

    private static final int BUFFER_COUNT    = 4;
    private static final int BUFFER_SAMPLES  = 8192;      // per buffer, per channel
    private static final int SAMPLE_RATE     = 44100;
    private static final int CHANNELS        = 2;         // stereo
    private static final int AL_FORMAT       = 0x1103;    // AL_FORMAT_STEREO16

    private long    alcDevice;
    private long    alcContext;
    private int     alSource;
    private int[]   alBuffers;

    private AudioDecoder    activeDecoder;
    private Thread          decodeThread;
    private final AtomicBoolean running      = new AtomicBoolean(false);
    private final AtomicBoolean paused       = new AtomicBoolean(false);
    private final AtomicInteger volumePct    = new AtomicInteger(80); // 0–100
    private final AtomicLong    positionMs   = new AtomicLong(0);
    private final AtomicLong    durationMs   = new AtomicLong(0);

    // ── Visualizer data (last FFT magnitude per 32-band bin) ─────────────────
    private final float[] visualizerBands = new float[32];

    // ── Environment audio DSP ─────────────────────────────────────────────────
    private volatile EnvironmentAudio environmentAudio = null;

    public void setEnvironmentAudio(EnvironmentAudio ea) { this.environmentAudio = ea; }

    public AudioBackend() {
        try {
            alcDevice  = ALC10.alcOpenDevice((ByteBuffer) null);
            if (alcDevice == 0) {
                MusicPlayerMod.LOGGER.warn("AudioBackend: no default OpenAL device");
                return;
            }
            int[] attrs = { 0 };
            alcContext = ALC10.alcCreateContext(alcDevice, attrs);
            ALC10.alcMakeContextCurrent(alcContext);
            AL.createCapabilities(ALC.createCapabilities(alcDevice));

            alBuffers = new int[BUFFER_COUNT];
            AL10.alGenBuffers(alBuffers);
            alSource = AL10.alGenSources();
            AL10.alSourcef(alSource, AL10.AL_GAIN, volumePct.get() / 100.0f);

            MusicPlayerMod.LOGGER.info("AudioBackend: OpenAL context ready");
        } catch (Exception e) {
            MusicPlayerMod.LOGGER.error("AudioBackend: init failed", e);
        }
    }

    // ── Playback control ──────────────────────────────────────────────────────

    /**
     * Start streaming audio from the given URL/path via AudioDecoder.
     * Stops any previous playback first.
     */
    public synchronized void play(String url, long startMs) {
        stopInternal();
        try {
            activeDecoder = new AudioDecoder(url, startMs, SAMPLE_RATE, CHANNELS);
            durationMs.set(activeDecoder.getDurationMs());
            positionMs.set(startMs);
            paused.set(false);
            running.set(true);
            decodeThread = new Thread(this::decodeLoop, "musicplayer-decode");
            decodeThread.setDaemon(true);
            decodeThread.start();
        } catch (Exception e) {
            MusicPlayerMod.LOGGER.error("AudioBackend: play failed for {}", url, e);
        }
    }

    public synchronized void pause() {
        if (!paused.get()) {
            paused.set(true);
            AL10.alSourcePause(alSource);
        }
    }

    public synchronized void resume() {
        if (paused.get()) {
            paused.set(false);
            AL10.alSourcePlay(alSource);
        }
    }

    public synchronized void stop() {
        stopInternal();
    }

    public synchronized void seek(long ms) {
        if (activeDecoder != null) {
            stopInternal();
            String url = activeDecoder.getUrl();
            play(url, ms);
        }
    }

    public void setVolume(float v) {
        v = Math.max(0f, Math.min(1f, v));
        volumePct.set((int)(v * 100));
        AL10.alSourcef(alSource, AL10.AL_GAIN, v);
    }

    // ── State queries ─────────────────────────────────────────────────────────

    public boolean isPlaying()  { return running.get() && !paused.get(); }
    public boolean isPaused()   { return running.get() && paused.get(); }
    public boolean isStopped()  { return !running.get(); }
    public long getPositionMs() { return positionMs.get(); }
    public long getDurationMs() { return durationMs.get(); }
    public float[] getVisualizerBands() { return visualizerBands; }

    // ── Decode loop ───────────────────────────────────────────────────────────

    private void decodeLoop() {
        // Acquire AL context on this thread (Minecraft holds it on main; we made a separate one)
        ALC10.alcMakeContextCurrent(alcContext);

        try {
            // Prime buffers
            for (int i = 0; i < BUFFER_COUNT; i++) {
                short[] pcm = activeDecoder.readSamples(BUFFER_SAMPLES * CHANNELS);
                if (pcm == null || pcm.length == 0) break;
                feedBuffer(alBuffers[i], pcm);
                AL10.alSourceQueueBuffers(alSource, alBuffers[i]);
            }
            AL10.alSourcePlay(alSource);

            while (running.get()) {
                if (paused.get()) {
                    Thread.sleep(20);
                    continue;
                }
                // Update position from decoder
                positionMs.set(activeDecoder.getPositionMs());

                // Fill any processed buffers
                int processed = AL10.alGetSourcei(alSource, AL10.AL_BUFFERS_PROCESSED);
                while (processed-- > 0) {
                    int buf = AL10.alSourceUnqueueBuffers(alSource);
                    short[] pcm = activeDecoder.readSamples(BUFFER_SAMPLES * CHANNELS);
                    if (pcm == null || pcm.length == 0) {
                        running.set(false);
                        break;
                    }
                    updateVisualizer(pcm);
                    // Environment audio DSP — apply reverb/filter in-place
                    EnvironmentAudio ea = environmentAudio;
                    if (ea != null && ea.enabled) {
                        float[] floatPcm = shortToFloat(pcm);
                        ea.process(floatPcm, floatPcm.length);
                        floatToShort(floatPcm, pcm);
                    }
                    feedBuffer(buf, pcm);
                    AL10.alSourceQueueBuffers(alSource, buf);
                }

                // Restart if starvation stopped the source
                if (AL10.alGetSourcei(alSource, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING
                        && running.get() && !paused.get()) {
                    AL10.alSourcePlay(alSource);
                }

                Thread.sleep(5);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            MusicPlayerMod.LOGGER.error("AudioBackend: decode error", e);
        } finally {
            AL10.alSourceStop(alSource);
            // Drain queued buffers
            int q = AL10.alGetSourcei(alSource, AL10.AL_BUFFERS_QUEUED);
            while (q-- > 0) AL10.alSourceUnqueueBuffers(alSource);
        }
    }

    private void feedBuffer(int bufferId, short[] samples) {
        // Convert short[] to ByteBuffer
        ByteBuffer bb = ByteBuffer.allocateDirect(samples.length * 2);
        for (short s : samples) {
            bb.put((byte)(s & 0xFF));
            bb.put((byte)((s >> 8) & 0xFF));
        }
        bb.flip();
        AL10.alBufferData(bufferId, AL_FORMAT, bb, SAMPLE_RATE);
    }

    /** Cheap 32-band magnitude from raw PCM without FFT library dependency. */
    private void updateVisualizer(short[] pcm) {
        int samplesPerBand = Math.max(1, pcm.length / 32);
        for (int b = 0; b < 32; b++) {
            long sum = 0;
            int start = b * samplesPerBand;
            int end = Math.min(start + samplesPerBand, pcm.length);
            for (int i = start; i < end; i++) sum += Math.abs(pcm[i]);
            float rms = (float) sum / (samplesPerBand * 32768.0f);
            // Smooth toward new value: 70% old + 30% new
            visualizerBands[b] = visualizerBands[b] * 0.7f + rms * 0.3f;
        }
    }

    // ── PCM conversion helpers (for environment audio DSP) ───────────────────

    private static float[] shortToFloat(short[] pcm) {
        float[] out = new float[pcm.length];
        for (int i = 0; i < pcm.length; i++) out[i] = pcm[i] / 32768.0f;
        return out;
    }

    private static void floatToShort(float[] floatPcm, short[] out) {
        for (int i = 0; i < out.length && i < floatPcm.length; i++) {
            float v = floatPcm[i];
            if (v >  1.0f) v =  1.0f;
            if (v < -1.0f) v = -1.0f;
            out[i] = (short)(v * 32767f);
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void stopInternal() {
        running.set(false);
        if (decodeThread != null) {
            decodeThread.interrupt();
            try { decodeThread.join(500); } catch (InterruptedException ignored) {}
            decodeThread = null;
        }
        if (activeDecoder != null) {
            activeDecoder.close();
            activeDecoder = null;
        }
        // Clear AL state
        if (alSource != 0) {
            AL10.alSourceStop(alSource);
            int q = AL10.alGetSourcei(alSource, AL10.AL_BUFFERS_QUEUED);
            while (q-- > 0) AL10.alSourceUnqueueBuffers(alSource);
        }
        positionMs.set(0);
    }

    public synchronized void shutdown() {
        stopInternal();
        if (alSource != 0) { AL10.alDeleteSources(alSource); alSource = 0; }
        if (alBuffers != null) { AL10.alDeleteBuffers(alBuffers); alBuffers = null; }
        if (alcContext != 0) { ALC10.alcDestroyContext(alcContext); alcContext = 0; }
        if (alcDevice  != 0) { ALC10.alcCloseDevice(alcDevice);    alcDevice  = 0; }
        MusicPlayerMod.LOGGER.info("AudioBackend: shutdown complete");
    }
}
