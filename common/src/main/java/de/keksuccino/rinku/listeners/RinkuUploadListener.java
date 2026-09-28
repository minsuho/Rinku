package de.keksuccino.rinku.listeners;

import de.keksuccino.rinku.RinkuBrowser;

import java.awt.Rectangle;

/**
 * GTWebUI fork: called on the render thread right after a browser view's pixels reached its GPU texture.
 * {@code frame} is the number of the newest CEF paint contained in this upload (frames are counted per browser from
 * 1), so "the texture now shows frame N" is exact even when several paints were merged into one upload.
 */
@FunctionalInterface
public interface RinkuUploadListener {
    void onUploaded(RinkuBrowser browser, long frame, Rectangle[] regions, boolean full);
}
