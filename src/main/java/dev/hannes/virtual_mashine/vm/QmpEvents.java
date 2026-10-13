package dev.hannes.virtual_mashine.vm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A second QMP connection that only listens for events. QEMU runs with {@code -action reboot=shutdown}
 * (a warm reset hangs some guests under WHPX in the boot loader), so a guest that reboots makes QEMU
 * exit; this notices that it was a reboot (or the reset button), and the machine is started again from cold.
 */
final class QmpEvents implements Closeable {
    private final int port;
    private final Thread thread;
    private volatile boolean closed;
    private volatile boolean rebootRequested;
    private volatile Socket socket;

    QmpEvents(int port, String label) {
        this.port = port;
        this.thread = new Thread(this::run, "virtual_mashine-events-" + label);
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /**
     * The guest asked for a restart (QEMU then exits instead of resetting). Called after QEMU exited:
     * waits a moment for the last events to be read.
     */
    boolean rebootRequested() {
        try {
            thread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return rebootRequested;
    }

    private void run() {
        for (int attempt = 0; attempt < 200 && !closed; attempt++) {
            try (Socket s = new Socket()) {
                socket = s;
                s.connect(new InetSocketAddress("127.0.0.1", port), 1000);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                Writer out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
                in.readLine(); // greeting
                out.write("{\"execute\": \"qmp_capabilities\"}\n");
                out.flush();
                String line;
                while ((line = in.readLine()) != null) {
                    JsonElement element = JsonParser.parseString(line);
                    if (element.isJsonObject() && isGuestReset(element.getAsJsonObject())) {
                        rebootRequested = true;
                    }
                }
                return; // QEMU is gone.
            } catch (IOException | RuntimeException e) {
                if (closed) {
                    return;
                }
                try {
                    Thread.sleep(150);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }
    }

    private static boolean isGuestReset(JsonObject message) {
        if (!message.has("event") || !message.has("data")) {
            return false;
        }
        String event = message.get("event").getAsString();
        JsonObject data = message.getAsJsonObject("data");
        String reason = data.has("reason") ? data.get("reason").getAsString() : "";
        return ("SHUTDOWN".equals(event) || "RESET".equals(event))
                && ("guest-reset".equals(reason) || "host-qmp-system-reset".equals(reason));
    }

    @Override
    public void close() {
        closed = true;
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // Closing anyway.
            }
        }
        thread.interrupt();
    }
}
