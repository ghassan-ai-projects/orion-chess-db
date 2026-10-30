package com.oriondb.engine;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How to launch and configure a UCI engine.
 *
 * @param executable path to the engine binary
 * @param threads    value for the {@code Threads} option
 * @param hashMb     value for the {@code Hash} option (MB)
 * @param options    further UCI options, sent as {@code setoption name K value V}
 */
public record EngineConfig(Path executable, int threads, int hashMb, Map<String, String> options) {
    /** Environment variable naming the engine binary; checked first by {@link #detect()}. */
    public static final String ENV = "ORIONDB_ENGINE";
    public static final int DEFAULT_HASH_MB = 128;

    private static final List<String> WELL_KNOWN = List.of(
        "/opt/homebrew/bin/stockfish", "/usr/local/bin/stockfish", "/usr/games/stockfish", "/usr/bin/stockfish");

    public EngineConfig {
        if (executable == null) throw new IllegalArgumentException("engine executable is required");
        if (threads < 1) throw new IllegalArgumentException("threads must be >= 1");
        if (hashMb < 1) throw new IllegalArgumentException("hash must be >= 1 MB");
        options = Collections.unmodifiableMap(new LinkedHashMap<>(options == null ? Map.of() : options));
    }

    /** Config for {@code executable} with default threads ({@code min(4, cores)}) and hash. */
    public static EngineConfig of(Path executable) {
        return new EngineConfig(executable, defaultThreads(), DEFAULT_HASH_MB, Map.of());
    }

    public static int defaultThreads() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
    }

    /**
     * Finds an engine: {@code $ORIONDB_ENGINE}, then {@code stockfish} on the
     * PATH, then common install locations. Candidates that are not
     * executable files are skipped.
     */
    public static Optional<EngineConfig> detect() {
        return detect(System.getenv());
    }

    static Optional<EngineConfig> detect(Map<String, String> env) {
        String explicit = env.get(ENV);
        if (explicit != null && !explicit.isBlank()) {
            Path p = toPath(explicit.strip());
            if (p != null && isExecutable(p)) return Optional.of(of(p));
        }
        String path = env.get("PATH");
        if (path != null) {
            boolean windows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
            for (String dir : path.split(File.pathSeparator)) {
                if (dir.isBlank()) continue;
                for (String name : windows ? List.of("stockfish.exe", "stockfish") : List.of("stockfish")) {
                    Path p = toPath(dir + File.separator + name);
                    if (p != null && isExecutable(p)) return Optional.of(of(p));
                }
            }
        }
        for (String loc : WELL_KNOWN) {
            Path p = Path.of(loc);
            if (isExecutable(p)) return Optional.of(of(p));
        }
        return Optional.empty();
    }

    private static Path toPath(String s) {
        try {
            return Path.of(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isExecutable(Path p) {
        try {
            return Files.isRegularFile(p) && Files.isExecutable(p);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public EngineConfig withThreads(int threads) {
        return new EngineConfig(executable, threads, hashMb, options);
    }

    public EngineConfig withHash(int hashMb) {
        return new EngineConfig(executable, threads, hashMb, options);
    }

    /** Adds (or with a null value removes) an extra UCI option. */
    public EngineConfig withOption(String name, String value) {
        Map<String, String> m = new LinkedHashMap<>(options);
        if (value == null) m.remove(name);
        else m.put(name, value);
        return new EngineConfig(executable, threads, hashMb, m);
    }
}
