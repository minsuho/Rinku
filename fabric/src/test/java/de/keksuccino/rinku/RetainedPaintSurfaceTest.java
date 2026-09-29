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
