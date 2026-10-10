package de.keksuccino.rinku;

import com.mojang.blaze3d.opengl.GlStateManager;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.util.Locale;

import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL32.*;

/**
 * GTWebUI fork experiment (M15-13 step 2): view uploads through a ring of pixel unpack buffers, render thread only.
 *
 * <pre>
 *   pick the next buffer → wait for its last upload's fence (normally signalled long ago) → map unsynchronized →
 *   copy the dirty rows (same layout as the frame) → unmap → glTexSubImage2D per rect from the buffer → fence
 * </pre>
 * The texture upload then reads the buffer asynchronously instead of the driver copying client memory inside the call.
 * Off by default; {@link #setEnabled} switches at run time so the two paths can be compared in one session. The copy,
 * the GL calls and the fence wait are timed separately ({@link #describe}).
 */
public final class RinkuPboUpload {

    static final int RING = 3;
    /** direct = upload from memory (default), pbo = this ring, mapped = {@link RinkuMappedUpload} (step B) */
    private static volatile String mode = "direct";
    private static volatile Boolean supported;
    // render thread
    private static long copyNanos, callNanos, waitNanos, uploads, bytes, waits;
    private static long mappedNanos, mappedUploads, mappedSkips, mappedAdopts;

    private final int[] buffers = new int[RING];
    private final long[] fences = new long[RING];
    private int size;
    private int next;

    public static boolean isEnabled() {
        return mode.equals("pbo");
    }

    static boolean isMapped() {
        return mode.equals("mapped");
    }

    public static String mode() {
        return mode;
    }

    /** @return false when this GL lacks what the mode needs (pbo: GL 3.2, mapped: ARB_buffer_storage) */
    public static boolean setMode(String m) {
        switch (m) {
            case "direct" -> mode = m;
            case "pbo" -> {
                if (!isSupported()) return false;
                mode = m;
            }
            case "mapped" -> {
                if (!RinkuMappedUpload.isSupported()) return false;
                mode = m;
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** @return false when this GL has no mappable buffers (GL 3.0 map range) */
    public static boolean setEnabled(boolean on) {
        return setMode(on ? "pbo" : "direct");
    }

    static void mappedUpload(long nanos) {
        mappedNanos += nanos;
        mappedUploads++;
    }

    static void mappedSkip() {
        mappedSkips++;
    }

    static void mappedAdopt() {
        mappedAdopts++;
    }

    static boolean isSupported() {
        Boolean s = supported;
        if (s == null) {
            try {
                s = GL.getCapabilities().OpenGL30 && GL.getCapabilities().OpenGL32;
            } catch (Throwable t) {
                s = false;
            }
            supported = s;
        }
        return s;
    }

    /** Cumulative timings since the last reset; resets them. */
    public static String describeAndReset() {
        String s = uploads == 0 ? "no PBO uploads" : String.format(Locale.ROOT,
                "PBO uploads %d (%.1f MB): copy avg %.3f ms, GL calls avg %.3f ms, fence wait avg %.3f ms (%d waited)",
                uploads, bytes / 1e6, copyNanos / 1e6 / uploads, callNanos / 1e6 / uploads, waitNanos / 1e6 / uploads, waits);
        if (mappedUploads > 0 || mappedSkips > 0 || mappedAdopts > 0) s += String.format(Locale.ROOT,
                "; mapped uploads %d: GL calls avg %.3f ms, drains skipped for the fence %d, buffers adopted %d",
                mappedUploads, mappedUploads == 0 ? 0 : mappedNanos / 1e6 / mappedUploads, mappedSkips, mappedAdopts);
        copyNanos = callNanos = waitNanos = uploads = bytes = waits = 0;
        mappedNanos = mappedUploads = mappedSkips = mappedAdopts = 0;
        return s;
    }

    /**
     * Render thread, the target texture bound and its storage at {@code width}×{@code height}.
     *
     * @param src     the whole frame, BGRA, tightly packed
     * @param regions rects to upload, already clipped; null = the whole frame
     */
    void upload(ByteBuffer src, int width, int height, Rectangle[] regions) {
        int need = width * height * 4;
        if (need != size) resize(need);
        int i = next;
        next = (next + 1) % RING;

        long t0 = System.nanoTime();
        if (fences[i] != 0) {
            int r = glClientWaitSync(fences[i], 0, 0);
            if (r == GL_TIMEOUT_EXPIRED) {
                waits++;
                glClientWaitSync(fences[i], GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
            }
            glDeleteSync(fences[i]);
            fences[i] = 0;
        }
        long t1 = System.nanoTime();

        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, buffers[i]);
        ByteBuffer dst = glMapBufferRange(GL_PIXEL_UNPACK_BUFFER, 0, size, GL_MAP_WRITE_BIT | GL_MAP_UNSYNCHRONIZED_BIT);
        if (dst == null) {
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
            throw new IllegalStateException("glMapBufferRange failed");
        }
        long s = MemoryUtil.memAddress(src), d = MemoryUtil.memAddress(dst);
        long copied = 0;
        if (regions == null) {
            MemoryUtil.memCopy(s, d, size);
            copied = size;
        } else {
            for (Rectangle r : regions) {
                long row = (long) r.width << 2;
                for (int y = 0; y < r.height; y++) {
                    long off = ((long) (r.y + y) * width + r.x) << 2;
                    MemoryUtil.memCopy(s + off, d + off, row);
                }
                copied += row * r.height;
            }
        }
        glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);
        long t2 = System.nanoTime();

        GlStateManager._pixelStore(GL_UNPACK_ROW_LENGTH, width);
        if (regions == null) {
            GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, 0);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0L);
        } else {
            for (Rectangle r : regions) {
                GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, r.x);
                GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, r.y);
                glTexSubImage2D(GL_TEXTURE_2D, 0, r.x, r.y, r.width, r.height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0L);
            }
            GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, 0);
        }
        fences[i] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        long t3 = System.nanoTime();

        waitNanos += t1 - t0;
        copyNanos += t2 - t1;
        callNanos += t3 - t2;
        bytes += copied;
        uploads++;
    }

    private void resize(int need) {
        close();
        for (int k = 0; k < RING; k++) {
            buffers[k] = glGenBuffers();
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, buffers[k]);
            glBufferData(GL_PIXEL_UNPACK_BUFFER, need, GL_STREAM_DRAW);
        }
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        size = need;
        next = 0;
    }

    /** Render thread. */
    void close() {
        for (int k = 0; k < RING; k++) {
            if (fences[k] != 0) glDeleteSync(fences[k]);
            fences[k] = 0;
            if (buffers[k] != 0) glDeleteBuffers(buffers[k]);
            buffers[k] = 0;
        }
        size = 0;
    }
}
