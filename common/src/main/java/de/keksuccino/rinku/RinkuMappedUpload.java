package de.keksuccino.rinku;

import com.mojang.blaze3d.opengl.GlStateManager;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL44;

import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL32.*;

/**
 * GTWebUI fork experiment (M15-13 step 2 B), render thread, one per browser view: the two frame buffers of
 * {@link RetainedPaintSurface} become persistently mapped pixel unpack buffers, so the CEF thread's dirty-rect copy
 * already lands in GPU-visible memory and the upload is a {@code glTexSubImage2D} from the buffer — no render-thread
 * copy at all.
 *
 * <pre>
 *   before drain: last upload's fence signalled? (no wait: when not, skip the drain this frame — the buffer it read
 *                 is about to go back to the CEF thread)
 *   drain        → the front is a mapped buffer → glTexSubImage2D per rect from it → fence
 *   resize/close → the surface drops the buffers; {@link #collect} deletes what it no longer lists
 * </pre>
 * The CEF thread never calls GL. Needs {@code ARB_buffer_storage} (GL 4.4).
 */
final class RinkuMappedUpload {

    private static final int FLAGS = GL_MAP_READ_BIT | GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;
    private static volatile Boolean supported;

    private final List<Integer> owned = new ArrayList<>();
    private long fence;

    static boolean isSupported() {
        Boolean s = supported;
        if (s == null) {
            try {
                s = GL.getCapabilities().GL_ARB_buffer_storage && GL.getCapabilities().OpenGL32;
            } catch (Throwable t) {
                s = false;
            }
            supported = s;
        }
        return s;
    }

    /** Before {@link RetainedPaintSurface#drain}: false = the last upload may still read the buffer, drain next frame. */
    boolean readyToDrain() {
        if (fence == 0) return true;
        int r = glClientWaitSync(fence, 0, 0);
        if (r == GL_TIMEOUT_EXPIRED) {
            RinkuPboUpload.mappedSkip();
            return false;
        }
        glDeleteSync(fence);
        fence = 0;
        return true;
    }

    /** Mapped mode on and the surface still on ordinary memory: give it two mapped buffers. */
    void adoptIfWanted(RetainedPaintSurface surface) {
        boolean want = RinkuPboUpload.isMapped();
        int[] current = surface.glBuffers();
        boolean mapped = current[0] != 0 || current[1] != 0;
        if (want && !mapped) {
            int bytes = surface.retainedBytes();
            if (bytes <= 0) return;
            int a = create(bytes), b = create(bytes);
            if (!surface.replaceBuffers(map(a, bytes), a, map(b, bytes), b)) {
                delete(a);
                delete(b);
                return;
            }
            owned.add(a);
            owned.add(b);
            RinkuPboUpload.mappedAdopt();
        } else if (!want && mapped) {
            // back to ordinary memory (switching the experiment off): the surface frees these when it resizes
            int bytes = surface.retainedBytes();
            if (bytes <= 0) return;
            ByteBuffer a = org.lwjgl.system.MemoryUtil.memAlloc(bytes), b = org.lwjgl.system.MemoryUtil.memAlloc(bytes);
            if (!surface.replaceBuffers(a, 0, b, 0)) {
                org.lwjgl.system.MemoryUtil.memFree(a);
                org.lwjgl.system.MemoryUtil.memFree(b);
            }
        }
    }

    /** The texture bound and at the frame's size. @param regions clipped rects, null = whole frame */
    void upload(int glBuffer, int width, int height, Rectangle[] regions) {
        long t0 = System.nanoTime();
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, glBuffer);
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
        if (fence != 0) glDeleteSync(fence);
        fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        RinkuPboUpload.mappedUpload(System.nanoTime() - t0);
    }

    /** Delete the mapped buffers the surface dropped (resize, close, switched off). */
    void collect(RetainedPaintSurface surface) {
        if (owned.isEmpty()) return;
        int[] current = surface.glBuffers();
        owned.removeIf(name -> {
            if (name == current[0] || name == current[1]) return false;
            delete(name);   // GL defers the deletion while an upload still reads it
            return true;
        });
    }

    /** Render thread, browser cleanup. */
    void close() {
        for (int name : owned) delete(name);
        owned.clear();
        if (fence != 0) glDeleteSync(fence);
        fence = 0;
    }

    private static int create(int bytes) {
        int name = glGenBuffers();
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, name);
        GL44.glBufferStorage(GL_PIXEL_UNPACK_BUFFER, bytes, FLAGS);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        return name;
    }

    private static ByteBuffer map(int name, int bytes) {
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, name);
        ByteBuffer b = glMapBufferRange(GL_PIXEL_UNPACK_BUFFER, 0, bytes, FLAGS);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        if (b == null) throw new IllegalStateException("persistent map failed");
        return b;
    }

    private static void delete(int name) {
        glDeleteBuffers(name);   // deleting a mapped buffer unmaps it
    }
}
