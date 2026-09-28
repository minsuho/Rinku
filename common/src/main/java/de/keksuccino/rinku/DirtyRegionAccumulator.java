package de.keksuccino.rinku;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * GTWebUI fork: upload regions that piled up between two render-thread drains.
 *
 * <p>Overlapping or touching regions are merged. Too many regions collapse into their bounding box, and a pending area
 * above the full-upload threshold turns into one full upload. Not thread-safe; {@link RetainedPaintSurface} guards it.
 */
final class DirtyRegionAccumulator {

    static final int DEFAULT_MAX_REGIONS = 32;
    static final double DEFAULT_FULL_UPLOAD_FRACTION = 0.5;

    private final int maxRegions;
    private final double fullUploadFraction;
    private final List<Rectangle> regions = new ArrayList<>();
    private int width, height;
    private boolean full;
    private long mergesToBounds, switchesToFull;

    DirtyRegionAccumulator(int maxRegions, double fullUploadFraction) {
        if (maxRegions <= 0) throw new IllegalArgumentException("maxRegions must be positive");
        this.maxRegions = maxRegions;
        this.fullUploadFraction = fullUploadFraction;
    }

    /** New surface size: everything pending is dropped and the next drain is a full upload. */
    void reset(int width, int height) {
        this.width = width;
        this.height = height;
        regions.clear();
        full = true;
    }

    void markFull() {
        regions.clear();
        full = true;
    }

    boolean isEmpty() {
        return !full && regions.isEmpty();
    }

    boolean isFull() {
        return full;
    }

    /** Adds a region already clipped to the surface. */
    void add(Rectangle r) {
        if (full || r.width <= 0 || r.height <= 0) return;
        Rectangle merged = new Rectangle(r);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = regions.size() - 1; i >= 0; i--) {
                Rectangle other = regions.get(i);
                if (touches(merged, other)) {
                    merged = merged.union(other);
                    regions.remove(i);
                    changed = true;
                }
            }
        }
        regions.add(merged);
        if (regions.size() > maxRegions) {
            Rectangle bounds = new Rectangle(regions.get(0));
            for (Rectangle x : regions) bounds = bounds.union(x);
            regions.clear();
            regions.add(bounds);
            mergesToBounds++;
        }
        long area = 0;
        for (Rectangle x : regions) area += (long) x.width * x.height;
        if (area > (long) (fullUploadFraction * width * height)) {
            regions.clear();
            full = true;
            switchesToFull++;
        }
    }

    /**
     * Pending regions (one surface-sized region when full), then clears.
     * @return empty when nothing is pending
     */
    Rectangle[] take() {
        Rectangle[] out = full ? new Rectangle[]{new Rectangle(0, 0, width, height)} : regions.toArray(Rectangle[]::new);
        regions.clear();
        full = false;
        return out;
    }

    int regionCount() {
        return full ? 1 : regions.size();
    }

    long mergesToBounds() {
        return mergesToBounds;
    }

    long switchesToFull() {
        return switchesToFull;
    }

    /** Overlapping or sharing an edge (merging adjacent strips keeps the region list short). */
    private static boolean touches(Rectangle a, Rectangle b) {
        return a.x <= b.x + b.width && b.x <= a.x + a.width && a.y <= b.y + b.height && b.y <= a.y + a.height;
    }
}
