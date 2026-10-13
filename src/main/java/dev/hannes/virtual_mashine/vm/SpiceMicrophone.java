package dev.hannes.virtual_mashine.vm;

import dev.hannes.virtual_mashine.Virtual_mashine;

import javax.crypto.Cipher;
import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The microphone input of a machine's sound card. QEMU reads its line-in from its built-in SPICE
 * server; this is a small SPICE client (main and record channel only) that streams the voices of
 * the players at the machine's microphones into it, in real time and mixed per player.
 * <p>
 * Players send 16-bit mono PCM at {@link #INPUT_RATE}; every source has its own jitter buffer, so
 * packets arriving in bursts with the game's ticks still give a steady stream. When nobody feeds,
 * nothing is sent and QEMU hands the guest silence by itself.
 */
public final class SpiceMicrophone implements Closeable {
    /** Sample rate of the voice players send, mono. */
    public static final int INPUT_RATE = 24_000;

    private static final int MAGIC = 0x51444552; // "REDQ"
    private static final int LINK_MESS_SIZE = 18;
    private static final int PUBLIC_KEY_SIZE = 162;
    private static final int CHANNEL_MAIN = 1;
    private static final int CHANNEL_RECORD = 6;

    private static final int MSG_SET_ACK = 3;
    private static final int MSG_PING = 4;
    private static final int MSGC_ACK_SYNC = 1;
    private static final int MSGC_ACK = 2;
    private static final int MSGC_PONG = 3;
    private static final int MSG_MAIN_INIT = 103;
    private static final int MSG_MAIN_CHANNELS_LIST = 104;
    private static final int MSGC_MAIN_ATTACH_CHANNELS = 104;
    private static final int MSG_RECORD_START = 101;
    private static final int MSG_RECORD_STOP = 102;
    private static final int MSGC_RECORD_DATA = 101;
    private static final int MSGC_RECORD_MODE = 102;
    private static final int MSGC_RECORD_START_MARK = 103;
    private static final int AUDIO_MODE_RAW = 1;

    private static final int PACKET_MS = 20;
    /** A source starts playing once this much is buffered, so the next burst arrives in time. */
    private static final int START_SAMPLES = INPUT_RATE * 80 / 1000;
    /** More than this buffered (the sender ran ahead, or the guest paused): skip to {@link #START_SAMPLES}. */
    private static final int MAX_SAMPLES = INPUT_RATE * 400 / 1000;
    private static final int CAPACITY = INPUT_RATE;
    private static final long SOURCE_TIMEOUT_NANOS = 600_000_000L;

    private static final ScheduledExecutorService PUMP = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "virtual_mashine-microphones");
        thread.setDaemon(true);
        return thread;
    });

    private final int port;
    private final String password;
    private final String label;
    private final Thread thread;
    private final ScheduledFuture<?> pump;
    private final Map<UUID, Source> sources = new HashMap<>();

    private volatile boolean closed;
    private volatile Channel main;
    private volatile Channel record;
    private volatile boolean recording;
    private volatile int frequency = 48_000;
    private volatile int channels = 2;
    private long startNanos;
    private long sentFrames;

    /**
     * @param port     QEMU's SPICE port on 127.0.0.1
     * @param password the ticket QEMU was started with
     */
    public SpiceMicrophone(int port, String password, String label) {
        this.port = port;
        this.password = password;
        this.label = label;
        this.thread = new Thread(this::run, "virtual_mashine-spice-" + label);
        this.thread.setDaemon(true);
        this.thread.start();
        this.pump = PUMP.scheduleAtFixedRate(this::pumpSafely, PACKET_MS, PACKET_MS, TimeUnit.MILLISECONDS);
    }

    /** The guest has its microphone open right now. */
    public boolean recording() {
        return recording;
    }

    /**
     * Voice of one player: 16-bit mono samples at {@link #INPUT_RATE}, already scaled by how close
     * they stand. Called on the server thread.
     */
    public void feed(UUID source, short[] samples, int count, float gain) {
        if (closed || !recording) {
            return;
        }
        synchronized (sources) {
            sources.computeIfAbsent(source, key -> new Source()).write(samples, count, gain);
        }
    }

    @Override
    public void close() {
        closed = true;
        pump.cancel(false);
        Channel m = main;
        Channel r = record;
        if (m != null) {
            m.close();
        }
        if (r != null) {
            r.close();
        }
        thread.interrupt();
    }

    // ---------------------------------------------------------------- connection

    private void run() {
        while (!closed) {
            try {
                session();
            } catch (IOException e) {
                if (!closed) {
                    Virtual_mashine.LOGGER.debug("Microphone link of {} ended: {}", label, e.toString());
                }
            } finally {
                recording = false;
                Channel r = record;
                if (r != null) {
                    r.close();
                }
                record = null;
                Channel m = main;
                if (m != null) {
                    m.close();
                }
                main = null;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** The main channel lives as long as the session; the record channel joins it on its own thread. */
    private void session() throws IOException {
        Channel m = Channel.link(port, 0, CHANNEL_MAIN, password);
        main = m;
        int session = 0;
        boolean recordStarted = false;
        while (!closed) {
            Message message = m.read();
            if (m.handleBase(message)) {
                continue;
            }
            if (message.type == MSG_MAIN_INIT && message.body.length >= 4) {
                session = message.le().getInt();
                m.send(MSGC_MAIN_ATTACH_CHANNELS, new byte[0]);
            } else if (message.type == MSG_MAIN_CHANNELS_LIST && !recordStarted) {
                ByteBuffer list = message.le();
                int count = list.getInt();
                boolean hasRecord = false;
                for (int i = 0; i < count && list.remaining() >= 2; i++) {
                    hasRecord |= list.get() == CHANNEL_RECORD & list.get() == 0;
                }
                if (hasRecord) {
                    recordStarted = true;
                    int id = session;
                    Thread recorder = new Thread(() -> runRecord(id, m), "virtual_mashine-spice-rec-" + label);
                    recorder.setDaemon(true);
                    recorder.start();
                }
            }
        }
    }

    private void runRecord(int session, Channel owner) {
        try (Channel r = Channel.link(port, session, CHANNEL_RECORD, password)) {
            record = r;
            while (!closed) {
                Message message = r.read();
                if (r.handleBase(message)) {
                    continue;
                }
                if (message.type == MSG_RECORD_START && message.body.length >= 10) {
                    ByteBuffer start = message.le();
                    int ch = start.getInt();
                    start.getShort(); // format: always signed 16 bit
                    int freq = start.getInt();
                    channels = ch == 1 ? 1 : 2;
                    frequency = freq > 0 ? freq : 48_000;
                    int now = (int) System.currentTimeMillis();
                    r.send(MSGC_RECORD_MODE, ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putInt(now).putShort((short) AUDIO_MODE_RAW).array());
                    r.send(MSGC_RECORD_START_MARK, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(now).array());
                    synchronized (sources) {
                        sources.clear();
                        startNanos = 0;
                    }
                    recording = true;
                } else if (message.type == MSG_RECORD_STOP) {
                    recording = false;
                }
            }
        } catch (IOException e) {
            if (!closed) {
                Virtual_mashine.LOGGER.debug("Microphone channel of {} ended: {}", label, e.toString());
            }
        } finally {
            recording = false;
            record = null;
            owner.close(); // Start the whole session again.
        }
    }

    // ---------------------------------------------------------------- streaming

    private void pumpSafely() {
        try {
            pump();
        } catch (IOException e) {
            Channel r = record;
            if (r != null) {
                r.close();
            }
        } catch (RuntimeException e) {
            Virtual_mashine.LOGGER.warn("Microphone of {} failed", label, e);
        }
    }

    /** Sends the frames that are due since the last call, as a real sound card would deliver them. */
    private void pump() throws IOException {
        Channel r = record;
        int rate = frequency;
        int outChannels = channels;
        short[] mixed;
        int due;
        synchronized (sources) {
            long now = System.nanoTime();
            if (r == null || !recording || sources.isEmpty()) {
                startNanos = 0;
                sources.clear();
                return;
            }
            if (startNanos == 0) {
                startNanos = now;
                sentFrames = 0;
                return;
            }
            long dueFrames = (now - startNanos) * rate / 1_000_000_000L - sentFrames;
            if (dueFrames > rate / 5) {
                // The pump was stalled (e.g. by the garbage collector): do not try to catch up.
                startNanos = now - (long) PACKET_MS * 1_000_000L;
                sentFrames = 0;
                dueFrames = (long) rate * PACKET_MS / 1000;
            }
            if (dueFrames <= 0) {
                return;
            }
            due = (int) dueFrames;
            mixed = new short[due];
            int[] sum = new int[due];
            double step = (double) INPUT_RATE / rate;
            boolean any = false;
            Iterator<Source> it = sources.values().iterator();
            while (it.hasNext()) {
                Source source = it.next();
                if (source.stale(now)) {
                    it.remove();
                    continue;
                }
                any |= source.mixInto(sum, due, step);
            }
            sentFrames += due;
            if (!any) {
                return;
            }
            for (int i = 0; i < due; i++) {
                mixed[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sum[i]));
            }
        }
        ByteBuffer body = ByteBuffer.allocate(4 + due * outChannels * 2).order(ByteOrder.LITTLE_ENDIAN);
        body.putInt((int) System.currentTimeMillis());
        for (int i = 0; i < due; i++) {
            for (int c = 0; c < outChannels; c++) {
                body.putShort(mixed[i]);
            }
        }
        r.send(MSGC_RECORD_DATA, body.array());
    }

    /** One player's voice: a ring of mono samples with its own resampling position. */
    private static final class Source {
        private final short[] ring = new short[CAPACITY];
        private int readPos;
        private int size;
        private double fraction;
        private boolean playing;
        private long lastWrite = System.nanoTime();

        void write(short[] samples, int count, float gain) {
            lastWrite = System.nanoTime();
            for (int i = 0; i < count; i++) {
                if (size == CAPACITY) {
                    readPos = (readPos + 1) % CAPACITY;
                    size--;
                }
                int value = Math.round(samples[i] * gain);
                ring[(readPos + size) % CAPACITY] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
                size++;
            }
            if (size > MAX_SAMPLES) {
                skip(size - START_SAMPLES);
            }
        }

        boolean stale(long now) {
            return size == 0 && now - lastWrite > SOURCE_TIMEOUT_NANOS;
        }

        /** Adds {@code frames} output frames to {@code sum}; false if this source is silent right now. */
        boolean mixInto(int[] sum, int frames, double step) {
            if (!playing) {
                if (size < START_SAMPLES) {
                    return false;
                }
                playing = true;
            }
            double position = fraction;
            for (int i = 0; i < frames; i++) {
                int index = (int) position;
                if (index + 1 >= size) {
                    playing = false; // Ran dry: wait for a new cushion before playing on.
                    skip(Math.min(size, index));
                    fraction = 0;
                    return true;
                }
                double t = position - index;
                short a = ring[(readPos + index) % CAPACITY];
                short b = ring[(readPos + index + 1) % CAPACITY];
                sum[i] += (int) Math.round(a + (b - a) * t);
                position += step;
            }
            int consumed = (int) position;
            fraction = position - consumed;
            skip(consumed);
            return true;
        }

        private void skip(int samples) {
            int n = Math.min(samples, size);
            readPos = (readPos + n) % CAPACITY;
            size -= n;
        }
    }

    // ---------------------------------------------------------------- SPICE wire format

    private record Message(int type, byte[] body) {
        ByteBuffer le() {
            return ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        }
    }

    /** One SPICE channel connection, using the full 18-byte message header. */
    private static final class Channel implements Closeable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final Object writeLock = new Object();
        private long serial;
        private int ackWindow;
        private int ackCount;

        private Channel(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedInputStream(socket.getInputStream(), 1 << 16);
            this.out = socket.getOutputStream();
        }

        static Channel link(int port, int connectionId, int type, String password) throws IOException {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
                socket.setTcpNoDelay(true);
                Channel channel = new Channel(socket);
                ByteBuffer link = ByteBuffer.allocate(16 + LINK_MESS_SIZE).order(ByteOrder.LITTLE_ENDIAN);
                link.putInt(MAGIC).putInt(2).putInt(2).putInt(LINK_MESS_SIZE);
                link.putInt(connectionId).put((byte) type).put((byte) 0).putInt(0).putInt(0).putInt(LINK_MESS_SIZE);
                channel.out.write(link.array());
                channel.out.flush();
                ByteBuffer header = channel.readLe(16);
                if (header.getInt() != MAGIC) {
                    throw new IOException("Not a SPICE server");
                }
                int major = header.getInt();
                header.getInt();
                int size = header.getInt();
                if (size < 4 || size > 4096) {
                    throw new IOException("Bad SPICE link reply");
                }
                ByteBuffer reply = channel.readLe(size);
                int error = reply.getInt();
                if (error != 0 || major != 2) {
                    throw new IOException("SPICE link refused (" + error + ")");
                }
                byte[] key = new byte[PUBLIC_KEY_SIZE];
                reply.get(key);
                channel.out.write(encryptTicket(key, password));
                channel.out.flush();
                int result = channel.readLe(4).getInt();
                if (result != 0) {
                    throw new IOException("SPICE authentication failed (" + result + ")");
                }
                return channel;
            } catch (IOException e) {
                socket.close();
                throw e;
            }
        }

        private static byte[] encryptTicket(byte[] publicKey, String password) throws IOException {
            try {
                PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(publicKey));
                Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
                cipher.init(Cipher.ENCRYPT_MODE, key);
                return cipher.doFinal((password + "\0").getBytes(StandardCharsets.UTF_8));
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                throw new IOException("Cannot encrypt the SPICE ticket", e);
            }
        }

        Message read() throws IOException {
            ByteBuffer header = readLe(18);
            header.getLong(); // serial
            int type = header.getShort() & 0xFFFF;
            int size = header.getInt();
            if (size < 0 || size > 16 << 20) {
                throw new IOException("Bad SPICE message size " + size);
            }
            byte[] body = readFully(size);
            if (ackWindow > 0 && ++ackCount >= ackWindow) {
                ackCount = 0;
                send(MSGC_ACK, new byte[0]);
            }
            return new Message(type, body);
        }

        /** Acks and pings, which every channel has to answer. */
        boolean handleBase(Message message) throws IOException {
            switch (message.type) {
                case MSG_SET_ACK -> {
                    ByteBuffer body = message.le();
                    int generation = body.getInt();
                    ackWindow = body.getInt();
                    ackCount = 0;
                    send(MSGC_ACK_SYNC, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(generation).array());
                    return true;
                }
                case MSG_PING -> {
                    ByteBuffer body = message.le();
                    ByteBuffer pong = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
                    pong.putInt(body.getInt()).putLong(body.getLong());
                    send(MSGC_PONG, pong.array());
                    return true;
                }
                default -> {
                    return message.type < 100; // Other common messages (notify, migration, ...) are ignored.
                }
            }
        }

        void send(int type, byte[] body) throws IOException {
            synchronized (writeLock) {
                ByteBuffer header = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN);
                header.putLong(++serial).putShort((short) type).putInt(body.length).putInt(0);
                out.write(header.array());
                out.write(body);
                out.flush();
            }
        }

        private ByteBuffer readLe(int length) throws IOException {
            return ByteBuffer.wrap(readFully(length)).order(ByteOrder.LITTLE_ENDIAN);
        }

        private byte[] readFully(int length) throws IOException {
            byte[] data = in.readNBytes(length);
            if (data.length < length) {
                throw new EOFException("SPICE connection closed");
            }
            return data;
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing anyway.
            }
        }
    }
}
