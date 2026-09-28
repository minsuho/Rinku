package de.keksuccino.rinku;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * GTWebUI fork: per-instance CEF paths, set by the embedding mod before Rinku initializes.
 * <p>Upstream Rinku shares one {@code %LOCALAPPDATA%\Rinku\cef-cache} across every Rinku instance on the PC and sets
 * no {@code root_cache_path}, so a second game instance fails to initialize (the profile is locked). GTWebUI picks a
 * free profile slot per instance (ARCHITECTURE ProfileSlots) and passes it here.
 */
public final class RinkuForkOptions {

    private static volatile Path rootCachePath;
    private static volatile Path cachePath;
    private static volatile Path logFile;

    private RinkuForkOptions() {}

    /**
     * @param root   CEF {@code root_cache_path} (all profiles of this installation live under it)
     * @param cache  CEF {@code cache_path}; must be {@code root} or a folder inside it (CEF rule)
     * @throws IllegalArgumentException if {@code cache} is not inside {@code root}
     * @throws IllegalStateException    after CEF has initialized
     */
    public static void setCachePaths(Path root, Path cache) {
        requireNotInitialized();
        Path r = root.toAbsolutePath().normalize(), c = cache.toAbsolutePath().normalize();
        if (!c.startsWith(r)) {
            throw new IllegalArgumentException("cache_path " + c + " must be inside root_cache_path " + r);
        }
        rootCachePath = r;
        cachePath = c;
    }

    /** CEF {@code log_file} (native Chromium log). Null keeps the CEF default. */
    public static void setLogFile(@Nullable Path file) {
        requireNotInitialized();
        logFile = file == null ? null : file.toAbsolutePath().normalize();
    }

    public static @Nullable Path rootCachePath() {
        return rootCachePath;
    }

    public static @Nullable Path cachePath() {
        return cachePath;
    }

    public static @Nullable Path logFile() {
        return logFile;
    }

    private static void requireNotInitialized() {
        if (Rinku.isInitialized()) {
            throw new IllegalStateException("RinkuForkOptions must be set before Rinku initializes");
        }
    }
}
