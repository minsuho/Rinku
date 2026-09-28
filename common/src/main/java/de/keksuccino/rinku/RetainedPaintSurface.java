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
 *   CEF thread  accept(): lock → copy only the dirty rects into the retained full-size buffer (+ alpha copy)
 *                         → add them to the pending regions → unlock          (no per-paint allocation)
 *   render      drain():  lock → memcpy pending regions into the staging buffer → clear → unlock
 *                         → caller uploads from staging outside the lock       (no GL inside the lock)
 * </pre>
 * A paint arriving before the render thread drained is merged into the pending regions instead of replacing the
 * previous frame, so a game below 60 FPS no longer turns every upload into a full upload.
 */
final class RetainedPaintSurface implements AutoCloseable {

    /** One drained upload: pixels live in the staging buffer until the next drain. */
    record Drained(ByteBuffer buffer, int width, int height, Rectangle[] regions, boolean full, long frame) {}

    private final ReentrantLock lock = new ReentrantLock();
    private final DirtyRegionAccumulator pending;
    private final boolean keepAlpha;
    private ByteBuffer pixels, staging;
    private int width, height;
    private volatile byte[] alpha;
    private volatile int alphaWidth, alphaHeight;
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
                if (pixels != null) MemoryUtil.memFree(pixels);
                if (staging != null) MemoryUtil.memFree(staging);
                pixels = MemoryUtil.memAlloc((int) bytes);
                staging = MemoryUtil.memAlloc((int) bytes);
                width = w;
                height = h;
                MemoryUtil.memCopy(srcAddr, MemoryUtil.memAddress(pixels), bytes);
                copiedBytes += bytes;
                if (keepAlpha) {
                    byte[] a = new byte[w * h];
                    copyAlpha(srcAddr, w, a, new Rectangle(0, 0, w, h));
                    alphaWidth = w;
                    alphaHeight = h;
                    alpha = a;
                }
                pending.reset(w, h);
                return;
            }
            long dstAddr = MemoryUtil.memAddress(pixels);
            byte[] a = keepAlpha ? alpha : null;
            for (Rectangle r : dirty) {
                Rectangle c = clip(r, w, h);
                if (c == null) continue;
                copyRows(srcAddr, dstAddr, w, c);
                copiedBytes += (long) c.width * c.height * 4L;
                if (a != null) copyAlpha(srcAddr, w, a, c);
                pending.add(c);
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
        lock.lock();
        try {
            if (closed || pixels == null) return null;
            if (forceFull) pending.markFull();
            if (pending.isEmpty()) return null;
            boolean full = pending.isFull();
            Rectangle[] regions = pending.take();
            long src = MemoryUtil.memAddress(pixels), dst = MemoryUtil.memAddress(staging);
            if (full) {
                MemoryUtil.memCopy(src, dst, (long) width * height * 4L);
                uploadedBytes += (long) width * height * 4L;
                fullUploads++;
            } else {
                for (Rectangle r : regions) {
                    copyRows(src, dst, width, r);
                    uploadedBytes += (long) r.width * r.height * 4L;
                }
            }
            drains++;
            return new Drained(staging, width, height, regions, full, latestFrame);
        } finally {
            lock.unlock();
        }
    }

    /** Alpha (0–255) at a view pixel from the latest paint, or -1 without an alpha copy / outside the view. */
    int alphaAt(int x, int y) {
        byte[] a = alpha;
        int w = alphaWidth, h = alphaHeight;
        if (a == null || x < 0 || y < 0 || x >= w || y >= h) return -1;
        return a[y * w + x] & 0xFF;
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
            if (pixels != null) MemoryUtil.memFree(pixels);
            if (staging != null) MemoryUtil.memFree(staging);
            pixels = staging = null;
            alpha = null;
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
