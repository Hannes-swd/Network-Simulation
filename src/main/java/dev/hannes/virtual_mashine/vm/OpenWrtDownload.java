package dev.hannes.virtual_mashine.vm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Downloads the current stable OpenWrt x86-64 image from downloads.openwrt.org and checks its SHA-256. */
final class OpenWrtDownload {
    private static final String BASE = "https://downloads.openwrt.org/";

    interface Progress {
        void update(long done, long total);
    }

    private OpenWrtDownload() {
    }

    /** @return file name of the image inside {@code folder} */
    static String fetch(Path folder, Progress progress) throws IOException {
        JsonObject versions = JsonParser.parseString(readText(BASE + ".versions.json")).getAsJsonObject();
        String version = versions.get("stable_version").getAsString();
        if (!version.matches("[0-9][0-9.]*[0-9]")) {
            throw new IOException("Unexpected OpenWrt version " + version);
        }
        String file = "openwrt-" + version + "-x86-64-generic-ext4-combined.img.gz";
        Path target = folder.resolve(file);
        if (Files.isRegularFile(target)) {
            return file;
        }
        String directory = BASE + "releases/" + version + "/targets/x86/64/";
        String expected = null;
        for (String line : readText(directory + "sha256sums").split("\n")) {
            if (line.trim().endsWith("*" + file) || line.trim().endsWith(" " + file)) {
                expected = line.trim().split("\\s+")[0].toLowerCase();
            }
        }
        if (expected == null) {
            throw new IOException("No checksum published for " + file);
        }
        Files.createDirectories(folder);
        Path part = folder.resolve(file + ".part");
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        HttpURLConnection connection = open(directory + file);
        long total = connection.getContentLengthLong();
        try (InputStream in = connection.getInputStream(); OutputStream out = Files.newOutputStream(part)) {
            byte[] buffer = new byte[1 << 16];
            long done = 0;
            long lastReport = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                sha.update(buffer, 0, read);
                done += read;
                if (done - lastReport >= 1024 * 1024) {
                    lastReport = done;
                    progress.update(done, total);
                }
            }
        } finally {
            connection.disconnect();
        }
        String actual = HexFormat.of().formatHex(sha.digest());
        if (!actual.equals(expected)) {
            Files.deleteIfExists(part);
            throw new IOException("Checksum mismatch for " + file);
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        return file;
    }

    private static String readText(String url) throws IOException {
        HttpURLConnection connection = open(url);
        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("User-Agent", "virtual_mashine Minecraft mod");
        int status = connection.getResponseCode();
        if (status != 200) {
            connection.disconnect();
            throw new IOException("HTTP " + status + " for " + url);
        }
        return connection;
    }
}
