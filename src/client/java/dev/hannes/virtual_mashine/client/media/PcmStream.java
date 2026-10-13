package dev.hannes.virtual_mashine.client.media;

import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A live sound stream for Minecraft's sound engine: PCM arriving from the network goes into a
 * jitter buffer, and every read takes 40 ms out of it. The engine keeps four reads queued, so the
 * sound runs about a quarter second behind the machine, enough to ride out a slow tick.
 * <p>
 * Underruns are filled with silence (the source must never run dry, or the engine drops it), and
 * after an underrun the buffer first fills up again before playing on; a buffer that grew too long
 * (the game was paused) skips ahead instead of lagging behind for good.
 */
final class PcmStream implements AudioStream {
    private static final int CHUNK_MS = 40;
    private static final int START_MS = 60;
    private static final int MAX_MS = 400;

    private final AudioFormat format;
    private final int channels;
    private final int chunkSamples;
    private final int startSamples;
    private final int maxSamples;
    private final short[] ring;
    private int readPos;
    private int size;
    private boolean playing;

    PcmStream(int rate, int channels) {
        this.format = new AudioFormat(rate, 16, channels, true, false);
        this.channels = channels;
        this.chunkSamples = rate * CHUNK_MS / 1000 * channels;
        this.startSamples = rate * START_MS / 1000 * channels;
        this.maxSamples = rate * MAX_MS / 1000 * channels;
        this.ring = new short[rate * channels];
    }

    int channels() {
        return channels;
    }

    /** 16-bit little-endian PCM in this stream's format; from the game's main thread. */
    synchronized void write(byte[] pcm) {
        ByteBuffer in = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        while (in.remaining() >= 2) {
            if (size == ring.length) {
                readPos = (readPos + 1) % ring.length;
                size--;
            }
            ring[(readPos + size) % ring.length] = in.getShort();
            size++;
        }
        if (size > maxSamples) {
            skip(size - startSamples);
        }
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    /** On the sound thread: always one chunk, silence where nothing arrived. */
    @Override
    public synchronized ByteBuffer read(int expectedSize) {
        ByteBuffer out = BufferUtils.createByteBuffer(chunkSamples * 2);
        if (!playing && size >= startSamples) {
            playing = true;
        }
        int copied = 0;
        if (playing) {
            copied = Math.min(size, chunkSamples);
            copied -= copied % channels;
            for (int i = 0; i < copied; i++) {
                out.putShort(ring[(readPos + i) % ring.length]);
            }
            skip(copied);
            if (copied < chunkSamples) {
                playing = false;
            }
        }
        for (int i = copied; i < chunkSamples; i++) {
            out.putShort((short) 0);
        }
        out.flip();
        return out;
    }

    private void skip(int samples) {
        int n = Math.min(samples - samples % channels, size);
        readPos = (readPos + n) % ring.length;
        size -= n;
    }

    @Override
    public void close() {
        // The stream outlives its sound instance: a new instance takes over when the engine dropped one.
    }
}
