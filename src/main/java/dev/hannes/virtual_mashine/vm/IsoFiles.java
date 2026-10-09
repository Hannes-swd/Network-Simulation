package dev.hannes.virtual_mashine.vm;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/** Copies single files out of an ISO 9660 image, e.g. the kernel and initrd of a live CD. */
final class IsoFiles {
    private static final int SECTOR = 2048;
    private static final int MAX_DIRECTORY = 16 * 1024 * 1024;

    private IsoFiles() {
    }

    /** @param path slash-separated path inside the image, compared without regard to case */
    static void extract(Path iso, String path, Path target) throws IOException {
        try (FileChannel in = FileChannel.open(iso, StandardOpenOption.READ)) {
            long[] entry = find(in, path);
            if (entry == null) {
                throw new IOException(path + " not found in " + iso.getFileName());
            }
            try (FileChannel out = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                long position = entry[0] * SECTOR;
                long remaining = entry[1];
                while (remaining > 0) {
                    long copied = in.transferTo(position, remaining, out);
                    if (copied <= 0) {
                        throw new IOException("Image ends inside " + path);
                    }
                    position += copied;
                    remaining -= copied;
                }
            }
        }
    }

    /** @return {first sector, size in bytes}, or null */
    private static long[] find(FileChannel in, String path) throws IOException {
        ByteBuffer descriptor = read(in, 16L * SECTOR, SECTOR);
        if (descriptor.get(0) != 1 || !"CD001".equals(ascii(descriptor, 1, 5))) {
            throw new IOException("Not an ISO 9660 image");
        }
        long sector = Integer.toUnsignedLong(descriptor.getInt(156 + 2));
        long size = Integer.toUnsignedLong(descriptor.getInt(156 + 10));
        for (String part : path.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            if (size > MAX_DIRECTORY) {
                throw new IOException("Directory too large");
            }
            ByteBuffer directory = read(in, sector * SECTOR, (int) size);
            long[] match = null;
            int i = 0;
            while (i < directory.limit()) {
                int length = directory.get(i) & 0xFF;
                if (length == 0) {
                    i = (i / SECTOR + 1) * SECTOR; // Records never cross a sector boundary.
                    continue;
                }
                if (i + 33 > directory.limit()) {
                    break;
                }
                int nameLength = directory.get(i + 32) & 0xFF;
                String name = ascii(directory, i + 33, Math.min(nameLength, directory.limit() - i - 33));
                int version = name.indexOf(';');
                if (version >= 0) {
                    name = name.substring(0, version);
                }
                if (name.endsWith(".")) {
                    name = name.substring(0, name.length() - 1);
                }
                if (name.toLowerCase(Locale.ROOT).equals(part.toLowerCase(Locale.ROOT))) {
                    match = new long[]{Integer.toUnsignedLong(directory.getInt(i + 2)), Integer.toUnsignedLong(directory.getInt(i + 10))};
                    break;
                }
                i += length;
            }
            if (match == null) {
                return null;
            }
            sector = match[0];
            size = match[1];
        }
        return new long[]{sector, size};
    }

    private static ByteBuffer read(FileChannel in, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            if (in.read(buffer, position + buffer.position()) < 0) {
                throw new IOException("Image is truncated");
            }
        }
        return buffer.flip();
    }

    private static String ascii(ByteBuffer buffer, int offset, int length) {
        byte[] bytes = new byte[length];
        buffer.get(offset, bytes);
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }
}
