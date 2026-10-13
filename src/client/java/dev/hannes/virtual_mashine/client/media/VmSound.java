package dev.hannes.virtual_mashine.client.media;

import dev.hannes.virtual_mashine.Virtual_mashine;
import dev.hannes.virtual_mashine.network.MediaPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Plays the sound of the machines the server says this player hears: from the device's place in
 * the world (mono, quieter with distance, under "Jukebox/Note Blocks"), or directly while its
 * screen is open (stereo). One stream per machine; a stream nothing arrives for ends.
 */
public final class VmSound {
    private static final Identifier SOUND = Virtual_mashine.id("vm_audio");
    private static final long SILENCE_MS = 1500;

    private static final class Source {
        final int mode;
        final PcmStream stream;
        final float x;
        final float y;
        final float z;
        @Nullable Instance instance;
        long lastData;

        Source(int mode, float x, float y, float z) {
            this.mode = mode;
            this.stream = mode == MediaPayloads.VmAudio.DIRECT_STEREO ? new PcmStream(44_100, 2) : new PcmStream(22_050, 1);
            this.x = x;
            this.y = y;
            this.z = z;
        }

        boolean sameAs(MediaPayloads.VmAudio payload) {
            return mode == payload.mode() && Math.abs(x - payload.x()) < 0.01 && Math.abs(y - payload.y()) < 0.01
                    && Math.abs(z - payload.z()) < 0.01;
        }
    }

    /** A streaming sound instance fed by a {@link PcmStream}. */
    private static final class Instance extends AbstractSoundInstance implements TickableSoundInstance {
        private final PcmStream stream;
        private boolean stopped;

        Instance(Source source) {
            super(SOUND, SoundSource.RECORDS, SoundInstance.createUnseededRandom());
            this.stream = source.stream;
            boolean positional = source.mode == MediaPayloads.VmAudio.POSITIONAL;
            this.relative = !positional;
            this.attenuation = positional ? SoundInstance.Attenuation.LINEAR : SoundInstance.Attenuation.NONE;
            this.x = positional ? source.x : 0;
            this.y = positional ? source.y : 0;
            this.z = positional ? source.z : 0;
            this.volume = 1.0F;
        }

        @Override
        public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary loader, Identifier id, boolean repeatInstantly) {
            return CompletableFuture.completedFuture(stream);
        }

        @Override
        public boolean isStopped() {
            return stopped;
        }

        @Override
        public void tick() {
        }

        void end() {
            stopped = true;
        }
    }

    private static final Map<UUID, Source> SOURCES = new HashMap<>();

    private VmSound() {
    }

    /** Main thread. */
    public static void onAudio(MediaPayloads.VmAudio payload) {
        Source source = SOURCES.get(payload.vm());
        if (source == null || !source.sameAs(payload)) {
            if (source != null) {
                stop(source);
            }
            source = new Source(payload.mode(), payload.x(), payload.y(), payload.z());
            SOURCES.put(payload.vm(), source);
        }
        source.stream.write(payload.pcm());
        source.lastData = System.currentTimeMillis();
        Minecraft minecraft = Minecraft.getInstance();
        if (source.instance == null || !minecraft.getSoundManager().isActive(source.instance)) {
            // First sound, or the engine dropped the stream after a long stall: start it (again).
            source.instance = new Instance(source);
            minecraft.getSoundManager().play(source.instance);
        }
    }

    public static void tick(Minecraft minecraft) {
        long now = System.currentTimeMillis();
        Iterator<Source> it = SOURCES.values().iterator();
        while (it.hasNext()) {
            Source source = it.next();
            if (now - source.lastData > SILENCE_MS) {
                stop(source);
                it.remove();
            }
        }
    }

    private static void stop(Source source) {
        if (source.instance != null) {
            source.instance.end();
            Minecraft.getInstance().getSoundManager().stop(source.instance);
            source.instance = null;
        }
    }

    public static void stopAll() {
        for (Source source : SOURCES.values()) {
            stop(source);
        }
        SOURCES.clear();
    }
}
