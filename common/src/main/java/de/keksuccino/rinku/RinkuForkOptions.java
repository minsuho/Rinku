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

    /**
     * GTWebUI fork (M15-14): browsers created from now on paint through a shared D3D11 texture (Windows) instead of
     * pixels. Needs Chromium's GPU compositing, which the embedding mod's app handler decides before CEF starts.
     */
    public static void setSharedTexture(boolean on) {
        RinkuSharedTexture.setEnabled(on);
    }

    public static boolean sharedTexture() {
        return RinkuSharedTexture.enabled();
    }

    /**
     * GTWebUI fork: the game's GPU as its graphics device reports it, and whether that GL has the extensions the
     * shared texture imports with — true when the device's extension list names them, null when it does not (the list
     * holds only what the game uses: on an RTX 4060 it did not name them, though the import works). Null before the
     * device exists.
     */
    public static @Nullable GpuInfo gpu() {
        com.mojang.blaze3d.systems.GpuDevice device = com.mojang.blaze3d.systems.RenderSystem.tryGetDevice();
        if (device == null) return null;
        com.mojang.blaze3d.systems.DeviceInfo info = device.getDeviceInfo();
        java.util.Set<String> ext = info.underlyingExtensions();
        Boolean imports = ext.contains("GL_EXT_memory_object") && ext.contains("GL_EXT_memory_object_win32") ? Boolean.TRUE : null;
        return new GpuInfo(info.name(), info.vendorName(), info.backendName() + " " + info.driverInfo(), imports);
    }

    /** @param importExtensions GL_EXT_memory_object and GL_EXT_memory_object_win32: true, or null when unknown */
    public record GpuInfo(String name, String vendor, String driver, @Nullable Boolean importExtensions) {}

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
