package dev.ollefx.musicplayer.audio;

import dev.ollefx.musicplayer.MusicPlayerMod;

import javax.sound.sampled.*;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;

/**
 * Streams and resamples audio from a URL to a fixed sample rate / channel count.
 * Supports any format java.sound.sampled can decode (WAV, AIFF, AU).
 * For MP3/OGG URLs returned by Spotify/YouTube, the caller should convert
 * the preview stream URL — Spotify provides 30-second MP3 previews via their API.
 *
 * Uses javax.sound for portability; no native codec dependencies beyond LWJGL.
 */
public class AudioDecoder implements AutoCloseable {

    private final String url;
    private AudioInputStream decodedStream;
    private final AudioFormat targetFormat;
    private long    positionMs;
    private long    durationMs;
    private boolean closed;

    public AudioDecoder(String url, long startMs, int sampleRate, int channels) throws Exception {
        this.url = url;

        URL audioUrl = URI.create(url).toURL();
        AudioInputStream rawStream = AudioSystem.getAudioInputStream(
                new BufferedInputStream(audioUrl.openStream()));

        AudioFormat srcFmt = rawStream.getFormat();

        // Duration estimation from frame count
        long frames = rawStream.getFrameLength();
        if (frames > 0 && srcFmt.getFrameRate() > 0) {
            durationMs = (long)(frames / srcFmt.getFrameRate() * 1000);
        } else {
            durationMs = 0;
        }

        // Convert to target PCM format
        targetFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                16,
                channels,
                channels * 2,
                sampleRate,
                false  // little-endian
        );

        AudioInputStream pcmStream = AudioSystem.getAudioInputStream(targetFormat, rawStream);

        // Seek to startMs by skipping frames
        if (startMs > 0) {
            long framesToSkip = (long)(startMs * sampleRate / 1000L) * channels;
            long bytesToSkip  = framesToSkip * 2; // 16-bit = 2 bytes/sample
            long skipped = 0;
            byte[] skipBuf = new byte[8192];
            while (skipped < bytesToSkip) {
                int n = pcmStream.read(skipBuf, 0, (int)Math.min(skipBuf.length, bytesToSkip - skipped));
                if (n < 0) break;
                skipped += n;
            }
        }
        positionMs = startMs;
        decodedStream = pcmStream;
    }

    /**
     * Read {@code sampleCount} interleaved PCM samples (16-bit signed LE).
     * Returns null on end-of-stream.
     */
    public short[] readSamples(int sampleCount) {
        if (closed || decodedStream == null) return null;
        byte[] buf = new byte[sampleCount * 2];
        int read;
        try {
            read = decodedStream.read(buf, 0, buf.length);
        } catch (IOException e) {
            MusicPlayerMod.LOGGER.warn("AudioDecoder read error: {}", e.getMessage());
            return null;
        }
        if (read <= 0) return null;

        // Advance position estimate
        int samplesRead = read / 2;
        int sr = (int) targetFormat.getSampleRate();
        int ch = targetFormat.getChannels();
        positionMs += (long) samplesRead * 1000L / (sr * ch);

        short[] out = new short[read / 2];
        for (int i = 0; i < out.length; i++) {
            int b0 = buf[i * 2] & 0xFF;
            int b1 = buf[i * 2 + 1] & 0xFF;
            out[i] = (short)(b0 | (b1 << 8));
        }
        return out;
    }

    public String getUrl()       { return url; }
    public long getPositionMs()  { return positionMs; }
    public long getDurationMs()  { return durationMs; }

    @Override
    public void close() {
        closed = true;
        if (decodedStream != null) {
            try { decodedStream.close(); } catch (IOException ignored) {}
            decodedStream = null;
        }
    }
}
