package de.keksuccino.rinku;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.util.concurrent.locks.ReentrantLock;

/**
 * GTWebUI fork: dirty-rect paint path for one browser view (ARCHITECTURE §3.17).
 *
 * <pre>
 *   CEF thread  accept(): lock → bring the back buffer up to date (rects it missed at the last swap, copied from
 *                         the front) → copy the dirty rects into it (+ alpha copy) → add them to the pending
 *                         regions → unlock                                        (no per-paint allocation)
 *   render      drain():  tryLock → swap back and front (O(1), no copy) → clear pending → unlock
 *                         → caller uploads from the front outside the lock        (no GL inside the lock)
 * </pre>
 * The render thread never copies and never waits: when the CEF thread holds the lock (a paint in progress) the
 * drain returns null and the regions go up next frame. Both buffers are full frames; the back one lags the front by
 * the rects of the last swap ({@code backStale}), which the CEF thread copies before writing (2026-09-29: the old
 * render-thread staging copy cost 1.8 ms per 1080p full upload).
 * A paint arriving before the render thread drained is merged into the pending regions instead of replacing the
 * previous frame, so a game below 60 FPS no longer turns every upload into a full upload.
 */
final class RetainedPaintSurface implements AutoCloseable {

    /**
     * One drained upload: pixels live in the staging buffer until the next drain.
     *
     * @param glBuffer the pixel unpack buffer {@code buffer} is mapped from, 0 = ordinary memory
     */
    record Drained(ByteBuffer buffer, int width, int height, Rectangle[] regions, boolean full, long frame, int glBuffer) {}

    private final ReentrantLock lock = new ReentrantLock();
    private final DirtyRegionAccumulator pending;
    private final boolean keepAlpha;
    /** back = CEF writes, front = uploaded. Swapped by {@link #drain}. */
    private ByteBuffer pixels, staging;
    /**
     * GL buffers {@code pixels} / {@code staging} are mapped from (PBO step B, {@link #replaceBuffers}), 0 = memAlloc.
     * The render thread owns mapped buffers: this class never frees them, it only drops them (resize, close).
     */
    private int pixelsGl, stagingGl;
    /** Rects the back buffer ({@code pixels}) misses after the last swap; null = the whole frame. */
    private final java.util.List<Rectangle> backStale = new java.util.ArrayList<>();
    private boolean backStaleAll;
    private int width, height;
    /**
     * Alpha copy, double-buffered: the CEF thread writes only {@code alphaBack}, then publishes it as {@code alphaFront}
     * (one volatile write); the render thread reads only the published plane. The plane that was just retired misses
     * the rects of that paint ({@code alphaBackStale}) and is brought up to date before the next write.
     */
    record AlphaPlane(byte[] data, int width, int height) {}

    private volatile AlphaPlane alphaFront;
    private byte[] alphaBack;
    private final java.util.List<Rectangle> alphaBackStale = new java.util.ArrayList<>();
    private long latestFrame;
    private boolean closed;

    // counters (M1-06)
    private long paints, copiedBytes, drains, uploadedBytes, fullUploads;

    RetainedPaintSurface(boolean keepAlpha) {
        this(keepAlpha, DirtyRegionAccumulator.DEFAULT_MAX_REGIONS, DirtyRegionAccumulator.DEFAULT_FULL_UPLOAD_FRACTION);
    }

    RetainedPaintSurface(boolean keepAlpha, int maxRegions, double fullUploadFraction) {
        this.keepAlpha = keepAlpha;
        this.pending = new DirtyRegionAccumulator(maxRegions, fullUploadFraction);
    }

    /** CEF thread. {@code src} is the complete frame; only {@code dirty} changed. */
    void accept(Rectangle[] dirty, ByteBuffer src, int w, int h, long frame) {
        if (w <= 0 || h <= 0) return;
        long bytes = (long) w * h * 4L;
        if (bytes > Integer.MAX_VALUE || src.capacity() < bytes) return;
        lock.lock();
        try {
            if (closed) return;
            paints++;
            latestFrame = frame;
            long srcAddr = MemoryUtil.memAddress(src, 0);
            if (pixels == null || w != width || h != height) {
                lastDrainedFront = null;   // about to be freed
                if (pixels != null && pixelsGl == 0) MemoryUtil.memFree(pixels);
                if (staging != null && stagingGl == 0) MemoryUtil.memFree(staging);
                pixelsGl = stagingGl = 0;   // mapped buffers are dropped; the render thread deletes them
                pixels = MemoryUtil.memAlloc((int) bytes);
                staging = MemoryUtil.memAlloc((int) bytes);
                width = w;
                height = h;
                MemoryUtil.memCopy(srcAddr, MemoryUtil.memAddress(pixels), bytes);
                copiedBytes += bytes;
                backStale.clear();
                backStaleAll = false;
                stagingInvalid = true;   // the other buffer has no frame yet
                publish();
                if (keepAlpha) {
                    byte[] a = new byte[w * h];
                    copyAlpha(srcAddr, w, a, new Rectangle(0, 0, w, h));
                    alphaBack = a.clone();
                    alphaBackStale.clear();
                    alphaFront = new AlphaPlane(a, w, h);
                }
                pending.reset(w, h);
                return;
            }
            long dstAddr = MemoryUtil.memAddress(pixels);
            // catch the back buffer up with the front: the rects of the last swap
            long frontAddr = MemoryUtil.memAddress(staging);
            if (backStaleAll) {
                MemoryUtil.memCopy(frontAddr, dstAddr, bytes);
            } else {
                for (Rectangle st : backStale) copyRows(frontAddr, dstAddr, w, st);
            }
            backStale.clear();
            backStaleAll = false;
            AlphaPlane front = keepAlpha ? alphaFront : null;
            byte[] back = front != null ? alphaBack : null;
            if (back != null) {
                // catch the back plane up with the rects it missed when it was retired
                for (Rectangle st : alphaBackStale) copyAlphaPlane(front.data(), back, w, st);
                alphaBackStale.clear();
            }
            for (Rectangle r : dirty) {
                Rectangle c = clip(r, w, h);
                if (c == null) continue;
                copyRows(srcAddr, dstAddr, w, c);
                copiedBytes += (long) c.width * c.height * 4L;
                if (back != null) {
                    copyAlpha(srcAddr, w, back, c);
                    alphaBackStale.add(c);
                }
                pending.add(c);
            }
            if (back != null) {
                alphaBack = front.data();                      // retired plane; stale by alphaBackStale
                alphaFront = new AlphaPlane(back, w, h);       // publish
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Render thread.
     * @param forceFull upload the whole retained frame (resync after a popup change or a failed upload)
     * @return null when there is nothing to upload
     */
    @Nullable Drained drain(boolean forceFull) {
        if (!lock.tryLock()) {
            if (forceFull) pendingForceFull = true;   // a paint is being written: upload next frame
            return null;
        }
        try {
            if (closed || pixels == null) return null;
            if (forceFull || pendingForceFull) pending.markFull();
            pendingForceFull = false;
            if (pending.isEmpty()) return null;
            boolean full = pending.isFull();
            Rectangle[] regions = pending.take();
            // swap: the back buffer holds the current frame; the old front becomes the back and lags by these rects
            ByteBuffer front = pixels;
            int frontGl = pixelsGl;
            pixels = staging;
            pixelsGl = stagingGl;
            staging = front;
            stagingGl = frontGl;
            backStale.clear();
            if (full || stagingInvalid) backStaleAll = true;
            stagingInvalid = false;
            if (full) {
                backStaleAll = true;
                uploadedBytes += (long) width * height * 4L;
                fullUploads++;
            } else {
                for (Rectangle r : regions) {
                    backStale.add(r);
                    uploadedBytes += (long) r.width * r.height * 4L;
                }
            }
            drains++;
            lastDrainedWidth = width;
            lastDrainedHeight = height;
            lastDrainedFront = front;
            return new Drained(front, width, height, regions, full, latestFrame, frontGl);
        } finally {
            lock.unlock();
        }
    }

    private boolean pendingForceFull;
    private boolean stagingInvalid;

    /**
     * Render thread (PBO step B): put both frame buffers into {@code back} / {@code front} (mapped GL buffers, or
     * memAlloc'd ones with names 0 to go back), copying their contents. Old memAlloc buffers are freed here; old GL
     * buffers are only dropped (see {@link #glBuffers}).
     *
     * @return false when a paint holds the lock, nothing is retained yet, or the size does not match — try next frame
     */
    boolean replaceBuffers(ByteBuffer back, int backGl, ByteBuffer front, int frontGl) {
        if (!lock.tryLock()) return false;
        try {
            long bytes = (long) width * height * 4L;
            if (closed || pixels == null || back.capacity() < bytes || front.capacity() < bytes) return false;
            MemoryUtil.memCopy(MemoryUtil.memAddress(pixels), MemoryUtil.memAddress(back), bytes);
            MemoryUtil.memCopy(MemoryUtil.memAddress(staging), MemoryUtil.memAddress(front), bytes);
            if (pixelsGl == 0) MemoryUtil.memFree(pixels);
            if (stagingGl == 0) MemoryUtil.memFree(staging);
            if (lastDrainedFront == staging) lastDrainedFront = front;
            pixels = back;
            pixelsGl = backGl;
            staging = front;
            stagingGl = frontGl;
            publish();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * GL buffers in use (0 = none); a mapped buffer not listed here any more may be deleted. Never blocks (the render
     * thread calls it every frame): a published snapshot, updated under the lock. The pair only changes as a set
     * (swaps keep both names), so a stale read never lists a buffer that was already dropped and adopted again.
     */
    int[] glBuffers() {
        return new int[] {publishedGlA, publishedGlB};
    }

    /** Frame size of the retained buffers, 0 before the first paint. Never blocks. */
    int retainedBytes() {
        return publishedBytes;
    }

    private volatile int publishedGlA, publishedGlB, publishedBytes;

    /** Under the lock, after the buffers or the size changed. */
    private void publish() {
        publishedGlA = pixelsGl;
        publishedGlB = stagingGl;
        publishedBytes = pixels == null ? 0 : width * height * 4;
    }

    /** Render thread: pixel of the last drained (uploaded) frame, 0xAARRGGBB; -1 before the first drain / outside. */
    int uploadedPixelAt(int x, int y) {
        if (!lock.tryLock()) return -2;   // a paint (or resize) is in progress: unknown this frame
        try {
            ByteBuffer front = lastDrainedFront;
            int w = lastDrainedWidth, h = lastDrainedHeight;
            if (front == null || x < 0 || y < 0 || x >= w || y >= h) return -1;
            return front.getInt((y * w + x) * 4);
        } finally {
            lock.unlock();
        }
    }

    private volatile ByteBuffer lastDrainedFront;
    private volatile int lastDrainedWidth, lastDrainedHeight;

    /** Alpha (0–255) at a view pixel from the latest paint, or -1 without an alpha copy / outside the view. */
    int alphaAt(int x, int y) {
        AlphaPlane p = alphaFront;
        if (p == null || x < 0 || y < 0 || x >= p.width() || y >= p.height()) return -1;
        return p.data()[y * p.width() + x] & 0xFF;
    }

    RinkuPaintStats stats() {
        lock.lock();
        try {
            return new RinkuPaintStats(paints, copiedBytes, drains, uploadedBytes, fullUploads,
                    pending.mergesToBounds(), pending.switchesToFull(), pending.regionCount());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) return;
            closed = true;
            if (pixels != null && pixelsGl == 0) MemoryUtil.memFree(pixels);
            if (staging != null && stagingGl == 0) MemoryUtil.memFree(staging);
            pixels = staging = null;
            pixelsGl = stagingGl = 0;
            publish();
            lastDrainedFront = null;
            alphaFront = null;
            alphaBack = null;
        } finally {
            lock.unlock();
        }
    }

    private static void copyRows(long src, long dst, int stride, Rectangle r) {
        long rowBytes = (long) r.width << 2;
        for (int row = 0; row < r.height; row++) {
            long off = ((long) (r.y + row) * stride + r.x) << 2;
            MemoryUtil.memCopy(src + off, dst + off, rowBytes);
        }
    }

    private static void copyAlphaPlane(byte[] from, byte[] to, int stride, Rectangle r) {
        for (int row = 0; row < r.height; row++) {
            int base = (r.y + row) * stride + r.x;
            System.arraycopy(from, base, to, base, r.width);
        }
    }

    /** BGRA → alpha byte per pixel. */
    private static void copyAlpha(long src, int stride, byte[] a, Rectangle r) {
        for (int row = 0; row < r.height; row++) {
            int base = (r.y + row) * stride + r.x;
            long p = src + ((long) base << 2) + 3;
            for (int col = 0; col < r.width; col++) {
                a[base + col] = MemoryUtil.memGetByte(p + ((long) col << 2));
            }
        }
    }

    private static @Nullable Rectangle clip(@Nullable Rectangle r, int w, int h) {
        if (r == null) return null;
        int x = Math.max(0, r.x), y = Math.max(0, r.y);
        int x2 = Math.min(w, r.x + r.width), y2 = Math.min(h, r.y + r.height);
        return x2 > x && y2 > y ? new Rectangle(x, y, x2 - x, y2 - y) : null;
    }
}
