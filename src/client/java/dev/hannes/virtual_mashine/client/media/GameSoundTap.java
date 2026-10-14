package dev.hannes.virtual_mashine.client.media;

import dev.hannes.virtual_mashine.Virtual_mashine;
import dev.hannes.virtual_mashine.network.MediaPayloads;
import dev.hannes.virtual_mashine.vm.SpiceMicrophone;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The game's sounds as a microphone block hears them, for microphones set to pick up game sounds:
 * every sound this game plays in the world is mixed again in software, from the microphone's position
 * (its volume falling off with distance like in the game), and sent to the server as that microphone's
 * signal. The machines' own sound is left out, so a computer does not hear itself.
 */
public final class GameSoundTap {
    private static final int RATE = SpiceMicrophone.INPUT_RATE;
    /** Packets of at most this many samples. */
    private static final int PACKET_SAMPLES = MediaPayloads.MAX_MIC_BYTES / 2;
    /** Loudness of the whole mix, leaving headroom before several sounds clip. */
    private static final float MASTER_GAIN = 0.7F;
    private static final int MAX_VOICES = 48;
    /** Decoded sounds kept for reuse, counted in samples (2 bytes each). */
    private static final long CACHE_SAMPLES = 16L * 1024 * 1024;
    /** Longer sounds (whole music discs) are cut here. */
    private static final int MAX_CLIP_SAMPLES = RATE * 60 * 6;

    private record Job(UUID mic, Vec3 pos) {
    }

    private static final class Voice {
        final SoundInstance instance;
        final CompletableFuture<short @Nullable []> clip;
        final float volume;
        final float pitch;
        final float range;
        final boolean looping;
        double position;
        boolean done;

        Voice(SoundInstance instance, CompletableFuture<short @Nullable []> clip, float volume, float pitch, float range, boolean looping) {
            this.instance = instance;
            this.clip = clip;
            this.volume = volume;
            this.pitch = pitch;
            this.range = range;
            this.looping = looping;
        }
    }

    private static final Map<UUID, Job> JOBS = new HashMap<>();
    private static final List<Voice> VOICES = new ArrayList<>();
    private static final Map<Identifier, CompletableFuture<short @Nullable []>> CLIPS = new LinkedHashMap<>(64, 0.75F, true);
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "virtual_mashine-game-sound-decoder");
        thread.setDaemon(true);
        return thread;
    });
    /** Start of the shared timeline and how many samples of it went out. */
    private static long clockStart;
    private static long produced;

    private GameSoundTap() {
    }

    public static synchronized void onJob(MediaPayloads.MicGameJob job) {
        if (job.active()) {
            JOBS.put(job.mic(), new Job(job.mic(), new Vec3(job.x(), job.y(), job.z())));
        } else {
            JOBS.remove(job.mic());
        }
        if (JOBS.isEmpty()) {
            VOICES.clear();
        }
    }

    /** Called by the sound engine for every sound it started. */
    public static synchronized void onPlay(SoundInstance instance) {
        if (JOBS.isEmpty() || VOICES.size() >= MAX_VOICES || instance.isRelative()
                || instance.getAttenuation() != SoundInstance.Attenuation.LINEAR) {
            return; // Menu clicks, music and the like are not sounds in the world.
        }
        Sound sound = instance.getSound();
        if (sound == null || Virtual_mashine.MOD_ID.equals(sound.getLocation().getNamespace())
                || Virtual_mashine.MOD_ID.equals(instance.getIdentifier().getNamespace())) {
            return; // The machines' own sound: a computer must not hear itself.
        }
        float volume = instance.getVolume();
        float range = Math.max(volume, 1.0F) * sound.getAttenuationDistance();
        Vec3 at = new Vec3(instance.getX(), instance.getY(), instance.getZ());
        boolean audible = instance instanceof TickableSoundInstance;
        for (Job job : JOBS.values()) {
            audible |= job.pos.distanceTo(at) < range;
        }
        if (!audible) {
            return;
        }
        float pitch = Math.max(0.5F, Math.min(2.0F, instance.getPitch()));
        VOICES.add(new Voice(instance, clip(sound.getPath()), Math.min(volume, 1.0F), pitch, range, instance.isLooping()));
    }

    /** Every client tick: mixes what came due since the last tick and sends it to each microphone. */
    public static synchronized void tick(Minecraft minecraft) {
        if (JOBS.isEmpty() || minecraft.level == null || !ClientPlayNetworking.canSend(MediaPayloads.MicGameAudio.TYPE)) {
            clockStart = 0;
            if (minecraft.level == null) {
                JOBS.clear();
                VOICES.clear();
            }
            return;
        }
        long now = System.nanoTime();
        if (clockStart == 0) {
            clockStart = now;
            produced = 0;
        }
        long due = (now - clockStart) * RATE / 1_000_000_000L - produced;
        if (due <= 0) {
            return;
        }
        if (due > RATE / 4) {
            produced += due - RATE / 20; // The game stalled: skip ahead instead of sending a backlog.
            due = RATE / 20;
        }
        int count = (int) due;
        produced += count;

        Map<UUID, float[]> mixes = new HashMap<>();
        float[] voiceSamples = new float[count];
        Iterator<Voice> it = VOICES.iterator();
        while (it.hasNext()) {
            Voice voice = it.next();
            if (voice.clip.isDone() && voice.clip.getNow(null) == null
                    || voice.looping && !minecraft.getSoundManager().isActive(voice.instance)) {
                it.remove(); // Could not be decoded, or a looping sound the game stopped.
                continue;
            }
            short[] clip = voice.clip.getNow(null);
            if (clip == null || clip.length == 0) {
                continue; // Still decoding: it starts a moment late.
            }
            voice.done = render(voice, clip, voiceSamples, count);
            Vec3 at = new Vec3(voice.instance.getX(), voice.instance.getY(), voice.instance.getZ());
            for (Job job : JOBS.values()) {
                float gain = (float) (voice.volume * Math.max(0, 1 - job.pos.distanceTo(at) / voice.range)) * MASTER_GAIN;
                if (gain < 0.002F) {
                    continue;
                }
                float[] mix = mixes.computeIfAbsent(job.mic, key -> new float[count]);
                for (int i = 0; i < count; i++) {
                    mix[i] += voiceSamples[i] * gain;
                }
            }
            if (voice.done) {
                it.remove();
            }
        }
        // Silence is not sent: the computer's line-in is quiet by itself then.
        mixes.forEach(GameSoundTap::send);
    }

    /** Writes the voice's next {@code count} samples (pitch applied) into {@code out}; true when it ended. */
    private static boolean render(Voice voice, short[] clip, float[] out, int count) {
        double position = voice.position;
        for (int i = 0; i < count; i++) {
            if (position >= clip.length) {
                if (!voice.looping) {
                    java.util.Arrays.fill(out, i, count, 0);
                    voice.position = position;
                    return true;
                }
                position -= clip.length;
            }
            int index = (int) position;
            double frac = position - index;
            int next = index + 1 < clip.length ? clip[index + 1] : voice.looping ? clip[0] : 0;
            out[i] = (float) ((clip[index] * (1 - frac) + next * frac) / 32768.0);
            position += voice.pitch;
        }
        voice.position = position;
        return false;
    }

    private static void send(UUID mic, float[] mix) {
        for (int start = 0; start < mix.length; start += PACKET_SAMPLES) {
            int length = Math.min(PACKET_SAMPLES, mix.length - start);
            ByteBuffer bytes = ByteBuffer.allocate(length * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < length; i++) {
                float sample = Math.max(-1F, Math.min(1F, mix[start + i]));
                bytes.putShort((short) Math.round(sample * 32767));
            }
            ClientPlayNetworking.send(new MediaPayloads.MicGameAudio(mic, bytes.array()));
        }
    }

    // ---------------------------------------------------------------- decoding

    /** The sound file as mono 16-bit samples at {@link #RATE}, decoded once in the background. */
    private static CompletableFuture<short @Nullable []> clip(Identifier path) {
        CompletableFuture<short @Nullable []> cached = CLIPS.get(path);
        if (cached != null) {
            return cached;
        }
        CompletableFuture<short @Nullable []> future = CompletableFuture.supplyAsync(() -> decode(path), DECODER);
        CLIPS.put(path, future);
        long total = 0;
        for (CompletableFuture<short @Nullable []> clip : CLIPS.values()) {
            short[] data = clip.getNow(null);
            total += data == null ? 0 : data.length;
        }
        Iterator<CompletableFuture<short @Nullable []>> oldest = CLIPS.values().iterator();
        while (total > CACHE_SAMPLES && oldest.hasNext()) {
            short[] data = oldest.next().getNow(null);
            if (data != null) {
                total -= data.length;
                oldest.remove();
            }
        }
        return future;
    }

    private static short @Nullable [] decode(Identifier path) {
        try (InputStream in = Minecraft.getInstance().getResourceManager().open(path);
             JOrbisAudioStream stream = new JOrbisAudioStream(in)) {
            int channels = Math.max(1, stream.getFormat().getChannels());
            float sourceRate = stream.getFormat().getSampleRate();
            double step = sourceRate / RATE;
            // Down-mix to mono at the source rate first, then resample to RATE.
            float[][] mono = {new float[1 << 16]};
            int[] frames = {0};
            int[] channel = {0};
            float[] sum = {0};
            int limit = (int) Math.min(Integer.MAX_VALUE - 16, (long) (MAX_CLIP_SAMPLES * step) + 2);
            while (frames[0] < limit && stream.readChunk(sample -> {
                sum[0] += sample;
                if (++channel[0] == channels) {
                    if (frames[0] == mono[0].length) {
                        mono[0] = java.util.Arrays.copyOf(mono[0], mono[0].length * 2);
                    }
                    mono[0][frames[0]++] = sum[0] / channels;
                    channel[0] = 0;
                    sum[0] = 0;
                }
            })) {
                // Keep reading.
            }
            int length = (int) Math.min(MAX_CLIP_SAMPLES, (long) (frames[0] / step));
            short[] out = new short[length];
            for (int i = 0; i < length; i++) {
                double src = i * step;
                int index = (int) src;
                double frac = src - index;
                float a = mono[0][Math.min(index, frames[0] - 1)];
                float b = mono[0][Math.min(index + 1, frames[0] - 1)];
                out[i] = (short) Math.round(Math.max(-1, Math.min(1, a + (b - a) * frac)) * 32767);
            }
            return out;
        } catch (IOException | RuntimeException e) {
            Virtual_mashine.LOGGER.debug("Could not decode {} for a microphone", path, e);
            return null;
        }
    }

    public static synchronized void reset() {
        JOBS.clear();
        VOICES.clear();
        clockStart = 0;
    }
}
