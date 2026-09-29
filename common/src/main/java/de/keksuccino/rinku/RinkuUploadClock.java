package de.keksuccino.rinku;

/**
 * GTWebUI fork: render-thread time spent draining retained paint regions and uploading them to the GPU, summed over
 * every browser. The caller reads it once per frame and takes the difference (no allocation).
 */
public final class RinkuUploadClock {

    private static volatile long total;
    private static volatile long gl;

    private RinkuUploadClock() {}

    /** Render thread. */
    static void add(long nanos) {
        total += nanos;
    }

    /** Render thread: the GL upload part of {@link #add}. */
    static void addGl(long nanos) {
        gl += nanos;
    }

    /** Cumulative GL upload nanoseconds (the rest of {@link #totalNanos} is copying out of the retained surface). */
    public static long glNanos() {
        return gl;
    }

    /** Cumulative nanoseconds (render thread). */
    public static long totalNanos() {
        return total;
    }
}
