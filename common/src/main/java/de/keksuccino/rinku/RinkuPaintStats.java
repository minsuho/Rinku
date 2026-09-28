package de.keksuccino.rinku;

/**
 * GTWebUI fork: cumulative paint-path counters of one browser view (dirty-rect path only).
 *
 * @param paints          CEF paints accepted
 * @param copiedBytes     bytes copied out of CEF buffers (should equal the dirty area × 4)
 * @param drains          render-thread drains that uploaded something
 * @param uploadedBytes   bytes handed to the GPU upload
 * @param fullUploads     full-frame uploads (size change, resync, threshold)
 * @param mergesToBounds  times the region list collapsed into its bounding box (too many regions)
 * @param switchesToFull  times the pending area crossed the full-upload threshold
 * @param pendingRegions  regions waiting right now
 */
public record RinkuPaintStats(long paints, long copiedBytes, long drains, long uploadedBytes, long fullUploads,
                              long mergesToBounds, long switchesToFull, int pendingRegions) {
}
