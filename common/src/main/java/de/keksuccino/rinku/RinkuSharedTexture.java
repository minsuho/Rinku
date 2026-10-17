package de.keksuccino.rinku;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.cef.CefBrowserSettings;
import org.cef.handler.CefAcceleratedPaintInfo;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.lwjgl.opengl.EXTMemoryObject.*;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.GL_HANDLE_TYPE_D3D11_IMAGE_EXT;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL32C.*;
import static org.lwjgl.opengl.GL42C.glTexStorage2D;
import static org.lwjgl.opengl.GL43C.glCopyImageSubData;
import static org.lwjgl.opengl.GL12C.GL_BGRA;

/**
 * GTWebUI fork spike (M15-14 S0a, docs/gpu-shared-texture.md §4.1): view paints arrive as a shared D3D11 texture
 * ({@link RinkuForkOptions#setSharedTexture}, or {@code -Drinku.sharedTexture=true}; Windows). On the CEF thread, in a hidden GL context shared with the game's ({@link RinkuSharedContext}),
 * each callback imports the texture, copies it into a free slot, waits for that copy and releases the import (CEF
 * returns the texture to its pool when the callback ends, so nothing is cached). The render thread copies the newest
 * finished slot into the browser texture and hands the slot back once its own copy fence has passed.
 */
final class RinkuSharedTexture {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile boolean enabled = Boolean.getBoolean("rinku.sharedTexture");
    private static final long FENCE_TIMEOUT_NANOS = 1_000_000_000L;
    private static final long REPORT_NANOS = 5_000_000_000L;

    // one hidden context for every browser, current on the CEF thread only
    private static boolean contextReady;
    private static Thread contextThread;
    private static boolean contextFailed;

    // alpha mask pass (CEF thread, shared context): MASK_SCALE x MASK_SCALE blocks keep their largest alpha, so a block
    // with any visible web pixel counts as web (docs/gpu-shared-texture.md section 4.3, chosen 2026-10-16)
    static final int MASK_SCALE = 2;
    private static final boolean MASK_ENABLED = !"false".equals(System.getProperty("rinku.sharedMask"));
    private static int maskProgram, maskSizeUniform, maskVao, maskFbo, maskTexture, maskTexW, maskTexH, readFbo;
    private static boolean maskFailed;
    private static ByteBuffer maskRead;

    private enum State { EMPTY, WRITING, DONE, READING }

    private static final class Slot {
        State state = State.EMPTY;
        int texture, width, height;
        long frame, seq, readFence;
        int pixel00 = -1, pixel10 = -1;   // pixels 0,0 (open marker) and 1,0 (stamp probe) as 0xAARRGGBB, -1 unknown
    }

    /** CPU copy of a frame's alpha at 1/MASK_SCALE size (the largest alpha of each block). */
    private record AlphaMask(byte[] data, int width, int height, int viewWidth, int viewHeight) { }

    // two buffers: the CEF thread writes the one that is not published (a hit test reads one byte, a stale read is harmless)
    private final AlphaMask[] maskBuffers = new AlphaMask[2];
    private int maskNext;
    private AlphaMask pendingMask;   // CEF thread: read in copyInto, published with its slot
    private volatile AlphaMask alphaFront;
    private volatile int uploadedPixel00 = -1, uploadedPixel10 = -1;
    private volatile int uploadedWidth, uploadedHeight;
    private long maskNanos, maskMaxNanos;

    // popup (<select> list, CEF thread): its last picture, and while it shows a copy of the view without it, so a
    // paint of either one alone can make the next frame
    private int popupTexture, popupWidth, popupHeight;
    private int baseTexture, baseWidth, baseHeight;
    private boolean baseValid;
    private boolean popupPainted;   // popupTexture holds this showing's picture (not the last popup's)

    private final Slot[] slots = { new Slot(), new Slot() };
    private final Object lock = new Object();
    private long publishSeq;
    private boolean closed;
    private boolean busy;   // a callback runs (guarded by lock): close leaves the popup textures to it

    // CEF thread counters (read for the report on the same thread)
    private long callbacks, dropped, failures, popups;
    private volatile long failuresBefore;   // failures of earlier report periods
    private long cefNanos, cefMaxNanos, waitMaxNanos;
    private long reportStart, lastCallback, totalCallbacks;
    private final int id = System.identityHashCode(this);
    private int lastFormat = -1;
    private int lastWidth, lastHeight;
    // render thread copies since the last report (reset by the CEF thread; approximate)
    private volatile long copiesSeen;

    /** Settings for a new browser: shared texture on, or null for the usual pixel paints. Render thread. */
    static CefBrowserSettings browserSettings() {
        if (!enabled || !prepareContext()) return null;
        CefBrowserSettings settings = new CefBrowserSettings();
        settings.shared_texture_enabled = true;
        return settings;
    }

    static void setEnabled(boolean on) {
        enabled = on;
    }

    static boolean enabled() {
        return enabled;
    }

    /** Callbacks that could not import or copy the texture so far (approximate, any thread). */
    long failures() {
        return failuresBefore + failures;
    }

    private static boolean prepareContext() {
        if (contextReady) return true;
        if (contextFailed) return false;
        contextReady = RinkuSharedContext.create();
        if (!contextReady) {
            contextFailed = true;
            LOGGER.error("[shared] could not create the shared GL context; browsers use pixel paints");
            return false;
        }
        LOGGER.info("[shared] shared GL context created ({})", RinkuSharedContext.describe());
        return true;
    }

    private static boolean makeCurrentHere() {
        Thread current = Thread.currentThread();
        if (contextThread == current) return true;
        if (contextThread != null || contextFailed) {
            return false;
        }
        RinkuSharedContext.makeCurrent();
        GLCapabilities caps = GL.createCapabilities();
        contextThread = current;
        boolean ok = caps.GL_EXT_memory_object && caps.GL_EXT_memory_object_win32 && (caps.OpenGL43 || caps.GL_ARB_copy_image);
        LOGGER.info("[shared] context current on thread '{}': memory_object {}, memory_object_win32 {}, copy_image {}, renderer {}",
                current.getName(), caps.GL_EXT_memory_object, caps.GL_EXT_memory_object_win32, caps.OpenGL43 || caps.GL_ARB_copy_image,
                glGetString(GL_RENDERER));
        if (!ok) contextFailed = true;
        return ok;
    }

    /**
     * CEF thread. Returns the frame number it published, or -1. {@code popupRect}: where the popup shows on the view
     * (physical px), null while hidden.
     */
    long accept(boolean popup, CefAcceleratedPaintInfo info, long frame, java.awt.Rectangle popupRect) {
        long start = System.nanoTime();
        if (reportStart == 0) reportStart = start;
        if (lastCallback != 0 && start - lastCallback > 1_000_000_000L) {
            LOGGER.info("[shared] #{} paint {} after a {} ms gap", Integer.toHexString(id), totalCallbacks + 1, (start - lastCallback) / 1_000_000);
        }
        lastCallback = start;
        totalCallbacks++;
        callbacks++;
        synchronized (lock) {
            if (closed) return -1;
            busy = true;
        }
        try {
            if (info == null || info.shared_texture_handle == 0 || info.width <= 0 || info.height <= 0) {
                failures++;
                return -1;
            }
            if (!makeCurrentHere()) {
                if (failures++ == 0) LOGGER.error("[shared] paint on thread '{}' but the context belongs to '{}'",
                        Thread.currentThread().getName(), contextThread == null ? "-" : contextThread.getName());
                return -1;
            }
            if (info.format != lastFormat || info.width != lastWidth || info.height != lastHeight) {
                lastFormat = info.format;
                lastWidth = info.width;
                lastHeight = info.height;
                LOGGER.info("[shared] #{} format {} size {}x{}", Integer.toHexString(id), info.format, info.width, info.height);
            }
            if (popup) {
                popups++;
                if (popupRect == null) return -1;
                if (!withImported(info, source -> copyPopup(source, info.width, info.height))) {
                    failures++;
                    return -1;
                }
                if (!baseValid) return -1;   // the view paint that showing the popup asks for composes it
            }
            Slot slot = takeWritable();
            if (slot == null) {
                dropped++;
                return -1;
            }
            boolean done = false;
            int w = popup ? baseWidth : info.width, h = popup ? baseHeight : info.height;
            try {
                if (popup) {
                    sizeSlot(slot, w, h);
                    glCopyImageSubData(baseTexture, GL_TEXTURE_2D, 0, 0, 0, 0, slot.texture, GL_TEXTURE_2D, 0, 0, 0, 0, w, h, 1);
                    overlayPopup(slot, popupRect);
                    readAndWait(slot, lastFormat);
                    done = true;
                } else {
                    done = withImported(info, source -> copyView(slot, source, info, popupRect));
                }
            } finally {
                publish(slot, done, frame, w, h);
            }
            if (!done) failures++;
            return done ? frame : -1;
        } finally {
            synchronized (lock) {
                busy = false;
                if (closed) deletePopupTextures();
            }
            long took = System.nanoTime() - start;
            cefNanos += took;
            cefMaxNanos = Math.max(cefMaxNanos, took);
            reportIfDue(System.nanoTime());
        }
    }

    private Slot takeWritable() {
        synchronized (lock) {
            if (closed) return null;
            Slot pick = null;
            for (Slot s : slots) if (s.state == State.EMPTY) { pick = s; break; }
            // an unread finished frame can be replaced by a newer one; a slot the render thread reads cannot
            if (pick == null) for (Slot s : slots) if (s.state == State.DONE && (pick == null || s.seq < pick.seq)) pick = s;
            if (pick != null) pick.state = State.WRITING;
            return pick;
        }
    }

    private void publish(Slot slot, boolean done, long frame, int width, int height) {
        synchronized (lock) {
            if (closed) {
                if (slot.texture != 0) glDeleteTextures(slot.texture);
                slot.texture = 0;
                slot.state = State.EMPTY;
                return;
            }
            if (done) {
                if (pendingMask != null) alphaFront = pendingMask;
                slot.state = State.DONE;
                slot.frame = frame;
                slot.seq = ++publishSeq;
            } else {
                slot.state = State.EMPTY;
            }
            pendingMask = null;
        }
    }

    private static void sizeSlot(Slot slot, int w, int h) {
        if (slot.texture != 0 && slot.width == w && slot.height == h) return;
        if (slot.texture != 0) glDeleteTextures(slot.texture);
        slot.texture = newTexture(w, h);
        slot.width = w;
        slot.height = h;
    }

    private static int newTexture(int w, int h) {
        int t = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, t);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, w, h);
        glBindTexture(GL_TEXTURE_2D, 0);
        return t;
    }

    /** The view's picture into the slot; while a popup shows, also kept without it and the popup drawn over it. */
    private void copyView(Slot slot, int source, CefAcceleratedPaintInfo info, java.awt.Rectangle popupRect) {
        int w = info.width, h = info.height;
        sizeSlot(slot, w, h);
        glCopyImageSubData(source, GL_TEXTURE_2D, 0, 0, 0, 0, slot.texture, GL_TEXTURE_2D, 0, 0, 0, 0, w, h, 1);
        if (popupRect != null) {
            if (baseTexture == 0 || baseWidth != w || baseHeight != h) {
                if (baseTexture != 0) glDeleteTextures(baseTexture);
                baseTexture = newTexture(w, h);
                baseWidth = w;
                baseHeight = h;
            }
            glCopyImageSubData(source, GL_TEXTURE_2D, 0, 0, 0, 0, baseTexture, GL_TEXTURE_2D, 0, 0, 0, 0, w, h, 1);
            baseValid = true;
            overlayPopup(slot, popupRect);
        } else {
            baseValid = false;
            popupPainted = false;
        }
        readAndWait(slot, info.format);
    }

    private void copyPopup(int source, int w, int h) {
        if (popupTexture == 0 || popupWidth != w || popupHeight != h) {
            if (popupTexture != 0) glDeleteTextures(popupTexture);
            popupTexture = newTexture(w, h);
            popupWidth = w;
            popupHeight = h;
        }
        glCopyImageSubData(source, GL_TEXTURE_2D, 0, 0, 0, 0, popupTexture, GL_TEXTURE_2D, 0, 0, 0, 0, w, h, 1);
        popupPainted = true;
        waitForCopies();   // the source returns to CEF's pool when the callback ends
    }

    /** The popup's last picture over the slot at {@code rect}, cut to the view (rows run top-down in both). */
    private void overlayPopup(Slot slot, java.awt.Rectangle rect) {
        if (rect == null || popupTexture == 0 || !popupPainted) return;
        int x0 = Math.max(0, rect.x), y0 = Math.max(0, rect.y);
        int x1 = Math.min(slot.width, rect.x + Math.min(rect.width, popupWidth));
        int y1 = Math.min(slot.height, rect.y + Math.min(rect.height, popupHeight));
        if (x1 <= x0 || y1 <= y0) return;
        glCopyImageSubData(popupTexture, GL_TEXTURE_2D, 0, x0 - rect.x, y0 - rect.y, 0,
                slot.texture, GL_TEXTURE_2D, 0, x0, y0, 0, x1 - x0, y1 - y0, 1);
    }

    /** Marker, stamp and alpha mask of the finished slot, then wait until the GPU has done every copy. */
    private void readAndWait(Slot slot, int format) {
        long maskStart = System.nanoTime();
        readAlphaAndMarker(slot, format);
        long maskTook = System.nanoTime() - maskStart;
        maskNanos += maskTook;
        maskMaxNanos = Math.max(maskMaxNanos, maskTook);
        waitForCopies();
    }

    private void waitForCopies() {
        long fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        long waitStart = System.nanoTime();
        int result = glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, FENCE_TIMEOUT_NANOS);
        glDeleteSync(fence);
        if (result != GL_ALREADY_SIGNALED && result != GL_CONDITION_SATISFIED) {
            // the copy may still read the pooled texture: do not let the callback return before it ends
            LOGGER.warn("[shared] copy fence result 0x{}, waiting with glFinish", Integer.toHexString(result));
            glFinish();
        }
        waitMaxNanos = Math.max(waitMaxNanos, System.nanoTime() - waitStart);
    }

    /** Imports the callback's texture for {@code use} (which waits for its copies) and releases it; false when the import failed. */
    private boolean withImported(CefAcceleratedPaintInfo info, java.util.function.IntConsumer use) {
        int w = info.width, h = info.height;
        int memory = glCreateMemoryObjectsEXT();
        int source = 0;
        try {
            glMemoryObjectParameteriEXT(memory, GL_DEDICATED_MEMORY_OBJECT_EXT, GL_TRUE);
            // a D3D11 image is a dedicated allocation: size 0 (w*h*4 gave GL_INVALID_VALUE on NVIDIA)
            glImportMemoryWin32HandleEXT(memory, 0L, GL_HANDLE_TYPE_D3D11_IMAGE_EXT, info.shared_texture_handle);
            source = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, source);
            glTexStorageMem2DEXT(GL_TEXTURE_2D, 1, GL_RGBA8, w, h, memory, 0L);
            int error = glGetError();
            if (error != GL_NO_ERROR) {
                if (failures < 5) LOGGER.error("[shared] import failed: GL error 0x{}", Integer.toHexString(error));
                return false;
            }
            glBindTexture(GL_TEXTURE_2D, 0);
            use.accept(source);
            return true;
        } finally {
            glBindTexture(GL_TEXTURE_2D, 0);
            // texture before memory object (AMD drivers hang the other way round, CCBlueX note)
            if (source != 0) glDeleteTextures(source);
            glDeleteMemoryObjectsEXT(memory);
        }
    }

    private void reportIfDue(long now) {
        if (now - reportStart < REPORT_NANOS) return;
        double secs = (now - reportStart) / 1e9;
        LOGGER.info("[shared] #" + Integer.toHexString(id) + " {} paints/s (dropped {}, failed {}), callback avg {} ms max {} ms, fence wait max {} ms, alpha mask avg {} ms max {} ms, render copies {}/s",
                String.format("%.1f", callbacks / secs), dropped, failures,
                String.format("%.3f", callbacks == 0 ? 0 : cefNanos / 1e6 / callbacks), String.format("%.3f", cefMaxNanos / 1e6),
                String.format("%.3f", waitMaxNanos / 1e6), String.format("%.3f", callbacks == 0 ? 0 : maskNanos / 1e6 / callbacks),
                String.format("%.3f", maskMaxNanos / 1e6), String.format("%.1f", (copiesSeen) / secs));
        copiesSeen = 0;
        failuresBefore += failures;
        callbacks = dropped = failures = 0;
        cefNanos = cefMaxNanos = waitMaxNanos = maskNanos = maskMaxNanos = 0;
        reportStart = now;
    }

    /** Alpha (0-255) of the latest paint at a view pixel, -1 without a mask yet / outside the view. Any thread. */
    int alphaAt(int x, int y) {
        AlphaMask m = alphaFront;
        if (m == null || x < 0 || y < 0 || x >= m.viewWidth() || y >= m.viewHeight()) return -1;
        return m.data()[(y / MASK_SCALE) * m.width() + x / MASK_SCALE] & 0xFF;
    }

    /** Render thread: pixel 0,0 or 1,0 (0xAARRGGBB) of the frame last copied to the browser texture; other pixels -1. */
    int uploadedPixelAt(int x, int y) {
        if (y != 0 || x < 0 || x > 1 || x >= uploadedWidth || uploadedHeight <= 0) return -1;
        return x == 0 ? uploadedPixel00 : uploadedPixel10;
    }

    /** CEF thread, after the slot copy: alpha mask into {@link #pendingMask}, top-left pixel into the slot. */
    private void readAlphaAndMarker(Slot slot, int format) {
        pendingMask = null;
        slot.pixel00 = slot.pixel10 = -1;
        int w = slot.width, h = slot.height;
        try {
            // marker and stamp: two pixels of the slot. Format 1 is BGRA bytes, so reading them as RGBA keeps the BGRA order
            if (readFbo == 0) readFbo = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, readFbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, slot.texture, 0);
            glPixelStorei(GL_PACK_ALIGNMENT, 1);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                int n = Math.min(2, w);
                ByteBuffer px = stack.malloc(8).order(ByteOrder.LITTLE_ENDIAN);
                glReadPixels(0, 0, n, 1, format == 1 ? GL_RGBA : GL_BGRA, GL_UNSIGNED_BYTE, px);
                slot.pixel00 = px.getInt(0);
                if (n > 1) slot.pixel10 = px.getInt(4);
            }
            if (!MASK_ENABLED || maskFailed || !prepareMask()) return;
            int mw = (w + MASK_SCALE - 1) / MASK_SCALE, mh = (h + MASK_SCALE - 1) / MASK_SCALE;
            if (maskTexW != mw || maskTexH != mh) {
                if (maskTexture != 0) glDeleteTextures(maskTexture);
                maskTexture = glGenTextures();
                glBindTexture(GL_TEXTURE_2D, maskTexture);
                glTexStorage2D(GL_TEXTURE_2D, 1, GL_R8, mw, mh);
                glBindFramebuffer(GL_FRAMEBUFFER, maskFbo);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, maskTexture, 0);
                maskTexW = mw;
                maskTexH = mh;
                if (maskRead == null || maskRead.capacity() < mw * mh) {
                    if (maskRead != null) MemoryUtil.memFree(maskRead);
                    maskRead = MemoryUtil.memAlloc(mw * mh);
                }
            }
            glBindFramebuffer(GL_FRAMEBUFFER, maskFbo);
            glViewport(0, 0, mw, mh);
            glUseProgram(maskProgram);
            glUniform2i(maskSizeUniform, w, h);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, slot.texture);
            glBindVertexArray(maskVao);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glBindVertexArray(0);
            glUseProgram(0);
            maskRead.clear();
            glReadPixels(0, 0, mw, mh, GL_RED, GL_UNSIGNED_BYTE, maskRead);
            int error = glGetError();
            if (error != GL_NO_ERROR) {
                LOGGER.error("[shared] alpha mask failed: GL error 0x{}; hit tests see no mask", Integer.toHexString(error));
                maskFailed = true;
                return;
            }
            AlphaMask m = maskBuffers[maskNext];
            if (m == null || m.width() != mw || m.height() != mh) m = maskBuffers[maskNext] = new AlphaMask(new byte[mw * mh], mw, mh, w, h);
            maskRead.get(0, m.data(), 0, mw * mh);
            maskNext ^= 1;
            pendingMask = m;
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindTexture(GL_TEXTURE_2D, 0);
        }
    }

    private static boolean prepareMask() {
        if (maskProgram != 0) return true;
        String vs = "#version 330 core\nvoid main() { vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2); gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0); }\n";
        String fs = "#version 330 core\nuniform sampler2D view; uniform ivec2 size; out vec4 color;\n"
                + "void main() { ivec2 base = ivec2(gl_FragCoord.xy) * " + MASK_SCALE + "; float a = 0.0;\n"
                + "  for (int j = 0; j < " + MASK_SCALE + "; j++) for (int i = 0; i < " + MASK_SCALE + "; i++)\n"
                + "    a = max(a, texelFetch(view, min(base + ivec2(i, j), size - 1), 0).a);\n"
                + "  color = vec4(a); }\n";
        int v = compile(GL_VERTEX_SHADER, vs), f = compile(GL_FRAGMENT_SHADER, fs);
        if (v == 0 || f == 0) {
            maskFailed = true;
            return false;
        }
        int program = glCreateProgram();
        glAttachShader(program, v);
        glAttachShader(program, f);
        glLinkProgram(program);
        glDeleteShader(v);
        glDeleteShader(f);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            LOGGER.error("[shared] alpha mask program: {}", glGetProgramInfoLog(program));
            glDeleteProgram(program);
            maskFailed = true;
            return false;
        }
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "view"), 0);
        glUseProgram(0);
        maskSizeUniform = glGetUniformLocation(program, "size");
        maskVao = glGenVertexArrays();
        maskFbo = glGenFramebuffers();
        maskProgram = program;
        return true;
    }

    private static int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            LOGGER.error("[shared] alpha mask shader: {}", glGetShaderInfoLog(shader));
            glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    /**
     * Render thread: return slots whose read has finished, then copy the newest finished slot into the browser
     * texture. Returns the frame number copied, or -1.
     */
    long drain(RinkuRenderer renderer) {
        Slot pick = null;
        synchronized (lock) {
            if (closed) return -1;
            for (Slot s : slots) {
                if (s.state == State.READING && glClientWaitSync(s.readFence, 0, 0L) != GL_TIMEOUT_EXPIRED) {
                    glDeleteSync(s.readFence);
                    s.readFence = 0;
                    s.state = State.EMPTY;
                }
            }
            for (Slot s : slots) if (s.state == State.DONE && (pick == null || s.seq > pick.seq)) pick = s;
            if (pick == null) return -1;
            pick.state = State.READING;
        }
        int target = renderer.sharedTarget(pick.width, pick.height, lastFormat == 1);
        glCopyImageSubData(pick.texture, GL_TEXTURE_2D, 0, 0, 0, 0, target, GL_TEXTURE_2D, 0, 0, 0, 0, pick.width, pick.height, 1);
        long fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        synchronized (lock) {
            pick.readFence = fence;
        }
        uploadedPixel00 = pick.pixel00;
        uploadedPixel10 = pick.pixel10;
        uploadedWidth = pick.width;
        uploadedHeight = pick.height;
        copiesSeen++;
        return pick.frame;
    }

    /** Render thread: free the slots (a slot the CEF thread is writing is freed by it when it publishes). */
    void close() {
        synchronized (lock) {
            closed = true;
            for (Slot s : slots) {
                if (s.readFence != 0) {
                    glDeleteSync(s.readFence);
                    s.readFence = 0;
                }
                if (s.state != State.WRITING && s.texture != 0) {
                    glDeleteTextures(s.texture);
                    s.texture = 0;
                }
            }
            if (!busy) deletePopupTextures();
        }
    }

    /** Under lock, no callback running. */
    private void deletePopupTextures() {
        if (popupTexture != 0) glDeleteTextures(popupTexture);
        if (baseTexture != 0) glDeleteTextures(baseTexture);
        popupTexture = baseTexture = 0;
        baseValid = popupPainted = false;
    }
}
