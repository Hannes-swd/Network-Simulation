package dev.hannes.virtual_mashine.vm;

import dev.hannes.virtual_mashine.Virtual_mashine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Consumer;

/**
 * Android for phones: Android-x86 (open source), downloaded once into the ISO folder and checked
 * against its published SHA-256. On the first start the phone boots the installer straight from
 * the image with "install automatically to the empty disk"; its menus run on COM1, where this
 * class watches for the end. From then on the phone boots Android from its own disk.
 */
final class AndroidSetup implements Consumer<byte[]> {
    static final String PROVISION = "android";
    static final String ISO = "android-x86_64-9.0-r2.iso";
    static final String KERNEL = "android-kernel";
    static final String INITRD = "android-initrd.img";
    private static final String URL = "https://sourceforge.net/projects/android-x86/files/Release%209.0/" + ISO + "/download";
    /** Published by SourceForge for this file; android-x86.org lists the matching SHA-1 1cc85b5e…. */
    private static final String SHA256 = "f7eb8fc56f29ad5432335dc054183acf086c539f3990f0b6e9ff58bd6df4604e";
    private static final int ATTEMPTS = 40;

    /**
     * Kernel options of the installer boot: install without any question to the first disk, and put
     * the installer's menus on COM1. The installer keeps the options it does not know for the
     * installed system, so Android's messages stay on COM1 and the screen stays upright like a phone's.
     */
    static final String INSTALL_CMDLINE = "root=/dev/ram0 AUTO_INSTALL=force DEBUG= video=720x1280 console=tty0 console=ttyS0,115200";

    interface Progress {
        void update(long done, long total);
    }

    private final SerialLine serial;
    private final Runnable onDone;
    private final StringBuilder recent = new StringBuilder();
    private boolean done;

    private AndroidSetup(SerialLine serial, Runnable onDone) {
        this.serial = serial;
        this.onDone = onDone;
    }

    static void attach(SerialLine serial, Runnable onDone) {
        serial.addTap(new AndroidSetup(serial, onDone));
    }

    /** The installer draws its dialogs with escape sequences; only the words matter. */
    @Override
    public synchronized void accept(byte[] chunk) {
        if (done) {
            return;
        }
        recent.append(new String(chunk, StandardCharsets.ISO_8859_1));
        if (recent.length() > 16384) {
            recent.delete(0, recent.length() - 8192);
        }
        String words = recent.toString().replaceAll("\u001B\\[[0-9;?]*[A-Za-z]", " ").replaceAll("\\s+", " ");
        if (words.contains("installed successfully")) {
            done = true;
            serial.removeTap(this);
            Virtual_mashine.LOGGER.info("Android installation finished");
            onDone.run();
        }
    }

    /** Extracts the installer's kernel and initrd next to the phone's disk. */
    static void prepareBoot(Path iso, Path dir) throws IOException {
        IsoFiles.extract(iso, "kernel", dir.resolve(KERNEL));
        IsoFiles.extract(iso, "initrd.img", dir.resolve(INITRD));
    }

    static void cleanUpBoot(Path dir) {
        try {
            Files.deleteIfExists(dir.resolve(KERNEL));
            Files.deleteIfExists(dir.resolve(INITRD));
        } catch (IOException ignored) {
            // Only temporary copies.
        }
    }

    // ---------------------------------------------------------------- download

    /** @return file name of the image inside {@code folder} */
    static String fetch(Path folder, Progress progress) throws IOException {
        Path target = folder.resolve(ISO);
        if (Files.isRegularFile(target)) {
            return ISO; // Only ever moved there after the checksum matched.
        }
        Files.createDirectories(folder);
        Path part = folder.resolve(ISO + ".part");
        IOException last = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                download(part, progress);
                last = null;
                break;
            } catch (IOException e) {
                // Mirrors drop long downloads now and then: continue where it stopped.
                last = e;
                Virtual_mashine.LOGGER.info("Android download interrupted ({}), resuming", e.getMessage());
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", interrupted);
                }
            }
        }
        if (last != null) {
            throw last;
        }
        if (!sha256(part).equals(SHA256)) {
            Files.deleteIfExists(part);
            throw new IOException("Checksum mismatch for " + ISO);
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        return ISO;
    }

    private static void download(Path part, Progress progress) throws IOException {
        long have = Files.isRegularFile(part) ? Files.size(part) : 0;
        HttpURLConnection connection = (HttpURLConnection) URI.create(URL).toURL().openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        // SourceForge answers download tools with a redirect to a mirror (browsers get a web page).
        connection.setRequestProperty("User-Agent", "Wget/1.21 (virtual_mashine Minecraft mod)");
        if (have > 0) {
            connection.setRequestProperty("Range", "bytes=" + have + "-");
        }
        try {
            int status = connection.getResponseCode();
            if (status == 416) {
                return; // Nothing left to fetch.
            }
            if (status != 200 && status != 206) {
                throw new IOException("HTTP " + status);
            }
            String type = connection.getContentType();
            if (type != null && type.startsWith("text/html")) {
                throw new IOException("Got a web page instead of the image");
            }
            boolean append = status == 206;
            long done = append ? have : 0;
            long length = connection.getContentLengthLong();
            long total = length < 0 ? -1 : done + length;
            try (InputStream in = connection.getInputStream();
                 OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                         append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[1 << 16];
                long lastReport = 0;
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                    done += read;
                    if (done - lastReport >= 4 * 1024 * 1024) {
                        lastReport = done;
                        progress.update(done, total);
                    }
                }
            }
            if (total > 0 && done < total) {
                throw new IOException("Connection closed early");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
