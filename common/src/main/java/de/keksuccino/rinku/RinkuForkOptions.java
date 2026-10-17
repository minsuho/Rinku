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
    /** What the game's GL said on the render thread before CEF started; null until then. */
    private static volatile GlGpu glGpu;

    private record GlGpu(boolean importExtensions, @Nullable String adapterLuid) {}

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
     * GTWebUI fork: the game's GPU as its graphics device reports it, whether that GL has the extensions the shared
     * texture imports with, and its adapter LUID. The last two come from the game's GL itself, read on the render
     * thread just before CEF starts ({@link #readGameGl}); before that the extensions are true when the device's list
     * names them and null otherwise (the list holds only what the game uses: on an RTX 4060 it did not name them,
     * though the import works). Null before the device exists.
     */
    public static @Nullable GpuInfo gpu() {
        com.mojang.blaze3d.systems.GpuDevice device = com.mojang.blaze3d.systems.RenderSystem.tryGetDevice();
        if (device == null) return null;
        com.mojang.blaze3d.systems.DeviceInfo info = device.getDeviceInfo();
        GlGpu gl = glGpu;
        Boolean imports;
        if (gl != null) {
            imports = gl.importExtensions();
        } else {
            java.util.Set<String> ext = info.underlyingExtensions();
            imports = ext.contains("GL_EXT_memory_object") && ext.contains("GL_EXT_memory_object_win32") ? Boolean.TRUE : null;
        }
        return new GpuInfo(info.name(), info.vendorName(), info.backendName() + " " + info.driverInfo(), imports,
                gl == null ? null : gl.adapterLuid());
    }

    /**
     * @param importExtensions GL_EXT_memory_object and GL_EXT_memory_object_win32: true, false, or null when unknown
     * @param adapterLuid      the game GPU's adapter LUID as Chromium's {@code --use-adapter-luid} takes it ("high,low",
     *                         high part signed, low part unsigned); null when the GL cannot tell
     */
    public record GpuInfo(String name, String vendor, String driver, @Nullable Boolean importExtensions, @Nullable String adapterLuid) {}

    /**
     * Render thread, game context current (CefUtil.init, just before CEF starts): asks the game's GL for the import
     * extensions and, through {@code GL_DEVICE_LUID_EXT}, which adapter it runs on, so Chromium can be put on the same
     * GPU (a texture shared from another adapter cannot be imported).
     */
    public static void readGameGl() {
        if (glGpu != null) return;
        try {
            org.lwjgl.opengl.GLCapabilities caps = org.lwjgl.opengl.GL.getCapabilities();
            boolean imports = caps.GL_EXT_memory_object && caps.GL_EXT_memory_object_win32;
            String luid = null;
            if (imports) {
                try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    java.nio.ByteBuffer b = stack.calloc(org.lwjgl.opengl.EXTMemoryObjectWin32.GL_LUID_SIZE_EXT).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    while (org.lwjgl.opengl.GL11C.glGetError() != 0) {
                        // drop errors left by the game
                    }
                    org.lwjgl.opengl.EXTMemoryObject.glGetUnsignedBytevEXT(org.lwjgl.opengl.EXTMemoryObjectWin32.GL_DEVICE_LUID_EXT, b);
                    if (org.lwjgl.opengl.GL11C.glGetError() == 0) {
                        long low = b.getInt(0) & 0xFFFFFFFFL;   // LUID { DWORD LowPart; LONG HighPart; }
                        int high = b.getInt(4);
                        if (low != 0 || high != 0) luid = high + "," + low;
                    }
                }
            }
            glGpu = new GlGpu(imports, luid);
        } catch (RuntimeException | LinkageError e) {
            org.slf4j.LoggerFactory.getLogger(RinkuForkOptions.class).warn("[shared] could not read the game's GL: {}", e.toString());
        }
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
