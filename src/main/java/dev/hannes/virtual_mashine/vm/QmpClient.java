package dev.hannes.virtual_mashine.vm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

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
 * Minimal client for the QEMU Machine Protocol (newline separated JSON over TCP).
 * Not thread safe; {@link VirtualMachine} only uses it from its worker thread.
 */
final class QmpClient implements Closeable {
    private static final int DEFAULT_TIMEOUT_MS = 15_000;

    private final Socket socket;
    private final BufferedReader in;
    private final Writer out;

    private QmpClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
    }

    /** Connects while QEMU is still starting up; gives up if the process dies or the timeout passes. */
    static QmpClient connect(int port, Process process, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        IOException last = null;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("QEMU exited during startup");
            }
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
                socket.setSoTimeout(DEFAULT_TIMEOUT_MS);
                QmpClient client = new QmpClient(socket);
                client.readMessage(); // greeting
                client.execute("qmp_capabilities", null);
                return client;
            } catch (IOException e) {
                last = e;
                socket.close();
                try {
                    Thread.sleep(150);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", interrupted);
                }
            }
        }
        throw new IOException("Could not connect to QEMU monitor", last);
    }

    JsonElement execute(String command, @Nullable JsonObject arguments) throws IOException {
        return execute(command, arguments, DEFAULT_TIMEOUT_MS);
    }

    JsonElement execute(String command, @Nullable JsonObject arguments, int timeoutMs) throws IOException {
        JsonObject message = new JsonObject();
        message.addProperty("execute", command);
        if (arguments != null) {
            message.add("arguments", arguments);
        }
        out.write(message + "\n");
        out.flush();
        socket.setSoTimeout(timeoutMs);
        try {
            while (true) {
                JsonObject reply = readMessage();
                if (reply.has("return")) {
                    return reply.get("return");
                }
                if (reply.has("error")) {
                    JsonObject error = reply.getAsJsonObject("error");
                    String desc = error.has("desc") ? error.get("desc").getAsString() : error.toString();
                    throw new QmpException(command + ": " + desc);
                }
                // Everything else is an asynchronous event, which we do not need.
            }
        } finally {
            socket.setSoTimeout(DEFAULT_TIMEOUT_MS);
        }
    }

    /** Runs a classic monitor command (e.g. savevm) and returns its text output. */
    String hmp(String commandLine, int timeoutMs) throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("command-line", commandLine);
        JsonElement result = execute("human-monitor-command", args, timeoutMs);
        return result.isJsonPrimitive() ? result.getAsString() : "";
    }

    private JsonObject readMessage() throws IOException {
        String line = in.readLine();
        if (line == null) {
            throw new IOException("QEMU monitor connection closed");
        }
        JsonElement element = JsonParser.parseString(line);
        if (!element.isJsonObject()) {
            throw new IOException("Unexpected QMP message: " + line);
        }
        return element.getAsJsonObject();
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing left to do.
        }
    }

    static final class QmpException extends IOException {
        QmpException(String message) {
            super(message);
        }
    }
}
