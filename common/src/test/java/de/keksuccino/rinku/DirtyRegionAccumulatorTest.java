package de.keksuccino.rinku;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirtyRegionAccumulatorTest {

    private static DirtyRegionAccumulator fresh(int maxRegions) {
        DirtyRegionAccumulator a = new DirtyRegionAccumulator(maxRegions, 0.5);
        a.reset(1000, 1000);
        a.take(); // consume the initial full upload
        return a;
    }

    @Test
    void firstDrainAfterResetIsFull() {
        DirtyRegionAccumulator a = new DirtyRegionAccumulator(32, 0.5);
        a.reset(640, 360);
        assertTrue(a.isFull());
        assertArrayEquals(new Rectangle[]{new Rectangle(0, 0, 640, 360)}, a.take());
        assertTrue(a.isEmpty());
    }

    @Test
    void separateRegionsStaySeparate() {
        DirtyRegionAccumulator a = fresh(32);
        a.add(new Rectangle(0, 0, 10, 10));
        a.add(new Rectangle(500, 500, 10, 10));
        assertEquals(2, a.regionCount());
        Rectangle[] out = a.take();
        assertEquals(2, out.length);
        assertTrue(a.isEmpty());
    }

    @Test
    void overlappingAndTouchingRegionsMerge() {
        DirtyRegionAccumulator a = fresh(32);
        a.add(new Rectangle(0, 0, 10, 10));
        a.add(new Rectangle(5, 5, 10, 10));   // overlaps
        a.add(new Rectangle(15, 0, 5, 5));    // touches the merged edge
        assertArrayEquals(new Rectangle[]{new Rectangle(0, 0, 20, 15)}, a.take());
    }

    @Test
    void mergeChainsThroughEarlierRegions() {
        DirtyRegionAccumulator a = fresh(32);
        a.add(new Rectangle(0, 0, 10, 10));
        a.add(new Rectangle(40, 0, 10, 10));
        a.add(new Rectangle(8, 0, 34, 10));   // bridges both
        assertArrayEquals(new Rectangle[]{new Rectangle(0, 0, 50, 10)}, a.take());
    }

    @Test
    void tooManyRegionsCollapseToBounds() {
        DirtyRegionAccumulator a = fresh(4);
        for (int i = 0; i < 5; i++) a.add(new Rectangle(i * 100, i * 100, 5, 5));
        assertEquals(1, a.mergesToBounds());
        assertArrayEquals(new Rectangle[]{new Rectangle(0, 0, 405, 405)}, a.take());
    }

    @Test
    void largePendingAreaSwitchesToFull() {
        DirtyRegionAccumulator a = fresh(32);
        a.add(new Rectangle(0, 0, 1000, 600)); // 60% of the surface
        assertTrue(a.isFull());
        assertEquals(1, a.switchesToFull());
        assertArrayEquals(new Rectangle[]{new Rectangle(0, 0, 1000, 1000)}, a.take());
        assertFalse(a.isFull());
    }

    @Test
    void addWhileFullIsIgnored() {
        DirtyRegionAccumulator a = fresh(32);
        a.markFull();
        a.add(new Rectangle(1, 1, 1, 1));
        assertEquals(1, a.take().length);
    }
}
