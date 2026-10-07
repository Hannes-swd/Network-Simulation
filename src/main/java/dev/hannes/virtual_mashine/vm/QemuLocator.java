package dev.hannes.virtual_mashine.vm;

import org.jspecify.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Finds the QEMU installation: the configured path first, then the usual install locations and PATH. */
public final class QemuLocator {
    private QemuLocator() {
    }

    public static @Nullable Path findSystemEmulator() {
        return find("qemu-system-x86_64");
    }

    public static @Nullable Path findImageTool() {
        Path emulator = findSystemEmulator();
        if (emulator != null) {
            Path sibling = emulator.resolveSibling(exe("qemu-img"));
            if (Files.isRegularFile(sibling)) {
                return sibling;
            }
        }
        return find("qemu-img");
    }

    private static @Nullable Path find(String program) {
        String fileName = exe(program);
        for (Path dir : candidateDirs()) {
            Path candidate = dir.resolve(fileName);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static List<Path> candidateDirs() {
        List<Path> dirs = new ArrayList<>();
        String configured = ModConfig.get().qemuPath;
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured.trim());
            dirs.add(Files.isRegularFile(path) ? path.getParent() : path);
        }
        switch (HostInfo.OS) {
            case WINDOWS -> {
                addEnv(dirs, "ProgramFiles", "qemu");
                addEnv(dirs, "ProgramW6432", "qemu");
                addEnv(dirs, "ProgramFiles(x86)", "qemu");
                addEnv(dirs, "USERPROFILE", "scoop", "apps", "qemu", "current");
                addEnv(dirs, "ChocolateyInstall", "bin");
                dirs.add(Path.of("C:\\msys64\\ucrt64\\bin"));
                dirs.add(Path.of("C:\\msys64\\mingw64\\bin"));
            }
            case MAC -> {
                dirs.add(Path.of("/opt/homebrew/bin"));
                dirs.add(Path.of("/usr/local/bin"));
                dirs.add(Path.of("/opt/local/bin"));
            }
            case LINUX -> {
                dirs.add(Path.of("/usr/bin"));
                dirs.add(Path.of("/usr/local/bin"));
                dirs.add(Path.of("/usr/libexec"));
            }
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String entry : pathEnv.split(File.pathSeparator)) {
                if (!entry.isBlank()) {
                    try {
                        dirs.add(Path.of(entry.trim().replace("\"", "")));
                    } catch (RuntimeException ignored) {
                        // Malformed PATH entry.
                    }
                }
            }
        }
        return dirs;
    }

    private static void addEnv(List<Path> dirs, String variable, String... more) {
        String base = System.getenv(variable);
        if (base != null && !base.isBlank()) {
            dirs.add(Path.of(base, more));
        }
    }

    private static String exe(String program) {
        return HostInfo.OS == HostInfo.Os.WINDOWS ? program + ".exe" : program;
    }
}
