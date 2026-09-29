package de.keksuccino.rinku;

/**
 * GTWebUI fork: render-thread time spent draining retained paint regions and uploading them to the GPU, summed over
 * every browser. The caller reads it once per frame and takes the difference (no allocation).
 */
public final class RinkuUploadClock {

    private static volatile long total;

    private RinkuUploadClock() {}

    /** Render thread. */
    static void add(long nanos) {
        total += nanos;
    }

    /** Cumulative nanoseconds (render thread). */
    public static long totalNanos() {
        return total;
    }
}
