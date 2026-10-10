package de.keksuccino.rinku;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** GTWebUI fork: the swapped front buffer must always hold the latest complete frame. */
class RetainedPaintSurfaceTest {

    static final int W = 64, H = 48;

    /** A "CEF" frame: full BGRA buffer. */
    static ByteBuffer frame(int[] px) {
        ByteBuffer b = MemoryUtil.memAlloc(W * H * 4);
        for (int i = 0; i < W * H; i++) b.putInt(i * 4, px[i]);
        return b;
    }

    static void paint(int[] model, Rectangle r, int value) {
        for (int y = r.y; y < r.y + r.height; y++) for (int x = r.x; x < r.x + r.width; x++) model[y * W + x] = value;
    }

    static void assertFront(int[] model, RetainedPaintSurface.Drained d) {
        for (int i = 0; i < W * H; i++) assertEquals(model[i], d.buffer().getInt(i * 4), "pixel " + i);
    }

    /** PBO step B: the frame buffers move into (mapped) buffers of the render thread and keep matching the frames. */
    @Test
    void replacedBuffersKeepTheFramesAndAreDroppedNotFreed() {
        RetainedPaintSurface s = new RetainedPaintSurface(false);
        int[] model = new int[W * H];
        Random rnd = new Random(3);
        paint(model, new Rectangle(0, 0, W, H), 0x22222222);
        ByteBuffer f = frame(model);
        s.accept(new Rectangle[]{new Rectangle(0, 0, W, H)}, f, W, H, 1);
        MemoryUtil.memFree(f);
        assertFront(model, s.drain(false));
        ByteBuffer a = MemoryUtil.memAlloc(W * H * 4), b = MemoryUtil.memAlloc(W * H * 4);
        org.junit.jupiter.api.Assertions.assertTrue(s.replaceBuffers(a, 7, b, 8));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[]{7, 8}, s.glBuffers());
        for (int step = 0; step < 50; step++) {
            int x = rnd.nextInt(W - 1), y = rnd.nextInt(H - 1);
            Rectangle r = new Rectangle(x, y, 1 + rnd.nextInt(W - x), 1 + rnd.nextInt(H - y));
            paint(model, r, rnd.nextInt());
            ByteBuffer fb = frame(model);
            s.accept(new Rectangle[]{r}, fb, W, H, step + 2);
            MemoryUtil.memFree(fb);
            RetainedPaintSurface.Drained d = s.drain(false);
            assertFront(model, d);
            org.junit.jupiter.api.Assertions.assertTrue(d.glBuffer() == 7 || d.glBuffer() == 8, "uploads from a mapped buffer");
            org.junit.jupiter.api.Assertions.assertTrue(d.buffer() == a || d.buffer() == b);
        }
        // a resize drops the mapped buffers (the render thread deletes them); they must still be valid memory here
        ByteBuffer big = MemoryUtil.memAlloc(W * 2 * H * 4);
        s.accept(new Rectangle[]{new Rectangle(0, 0, W * 2, H)}, big, W * 2, H, 99);
        MemoryUtil.memFree(big);
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[]{0, 0}, s.glBuffers());
        a.putInt(0, 1);
        b.putInt(0, 1);
        MemoryUtil.memFree(a);
        MemoryUtil.memFree(b);
        s.close();
    }

    @Test
    void frontAlwaysMatchesTheLatestFrame() {
        RetainedPaintSurface s = new RetainedPaintSurface(false);
        int[] model = new int[W * H];
        Random rnd = new Random(7);
        paint(model, new Rectangle(0, 0, W, H), 0x11111111);
        ByteBuffer f = frame(model);
        s.accept(new Rectangle[]{new Rectangle(0, 0, W, H)}, f, W, H, 1);
        MemoryUtil.memFree(f);
        RetainedPaintSurface.Drained first = s.drain(false);
        assertNotNull(first);
        assertFront(model, first);
        for (int step = 0; step < 200; step++) {
            int paints = 1 + rnd.nextInt(3);   // several paints between two drains
            for (int k = 0; k < paints; k++) {
                int x = rnd.nextInt(W - 1), y = rnd.nextInt(H - 1);
                Rectangle r = new Rectangle(x, y, 1 + rnd.nextInt(W - x), 1 + rnd.nextInt(H - y));
                paint(model, r, rnd.nextInt());
                ByteBuffer fb = frame(model);
                s.accept(new Rectangle[]{r}, fb, W, H, step + 2);
                MemoryUtil.memFree(fb);
            }
            RetainedPaintSurface.Drained d = s.drain(rnd.nextInt(20) == 0);
            assertNotNull(d);
            assertFront(model, d);
        }
        assertNull(s.drain(false), "nothing new");
        s.close();
    }
}
