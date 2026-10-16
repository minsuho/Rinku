package de.keksuccino.rinku;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;

import java.nio.ByteBuffer;
import java.util.UUID;

import static org.lwjgl.opengl.GL12.GL_BGRA;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL12.GL_UNPACK_ALIGNMENT;
import static org.lwjgl.opengl.GL12.GL_UNPACK_ROW_LENGTH;
import static org.lwjgl.opengl.GL12.GL_UNPACK_SKIP_PIXELS;
import static org.lwjgl.opengl.GL12.GL_UNPACK_SKIP_ROWS;
import static org.lwjgl.opengl.GL12.GL_UNSIGNED_INT_8_8_8_8_REV;
import static org.lwjgl.opengl.GL12.glTexSubImage2D;

public class RinkuRenderer {

    private final boolean transparent;
    private final ResourceLocation textureIdentifier;
    private RinkuDirectTexture texture;
    private int textureWidth;
    private int textureHeight;
    private boolean textureRegistered;
    private boolean textureUploaded;
    private RinkuPboUpload pbo;

    protected RinkuRenderer(boolean transparent) {
        this.transparent = transparent;
        String uniqueId = UUID.randomUUID().toString().toLowerCase().replace("-", "");
        this.textureIdentifier = new ResourceLocation("rinku", "browser_" + uniqueId);
    }

    public void initialize() {
        RenderSystem.assertOnRenderThread();
        if (this.textureRegistered) return;
        this.texture = new RinkuDirectTexture();
        Minecraft.getInstance().getTextureManager().register(this.textureIdentifier, this.texture);
        this.textureRegistered = true;
    }

    public AbstractTexture getTexture() {
        return this.texture;
    }

    /** Returns the texture-manager location used by immediate-mode {@code GuiGraphics} rendering. */
    public ResourceLocation getTextureIdentifier() {
        return this.textureIdentifier;
    }

    public boolean isTextureReady() {
        return this.textureRegistered && this.textureUploaded && this.texture != null && this.textureWidth > 0 && this.textureHeight > 0;
    }

    public int getTextureID() {
        if (!this.isTextureReady() || !RenderSystem.isOnRenderThreadOrInit()) return 0;
        return this.texture.getId();
    }

    public boolean supportsDirtyRectUpload() {
        return this.isTextureReady();
    }

    public int getTextureWidth() {
        return this.textureWidth;
    }

    public int getTextureHeight() {
        return this.textureHeight;
    }

    public boolean isTransparent() {
        return this.transparent;
    }

    protected void cleanup() {
        RenderSystem.assertOnRenderThread();
        this.textureUploaded = false;
        this.textureWidth = 0;
        this.textureHeight = 0;
        if (this.textureRegistered) {
            Minecraft.getInstance().getTextureManager().release(this.textureIdentifier);
            this.textureRegistered = false;
        }
        this.texture = null;
        if (this.pbo != null) {
            this.pbo.close();
            this.pbo = null;
        }
    }

    /**
     * GTWebUI fork (M15-13 step 2): upload through the pixel buffer ring when it is switched on and the texture
     * already has this size.
     *
     * @param regions clipped rects, null = the whole frame
     * @return false when the caller has to upload the usual way
     */
    boolean uploadThroughPbo(ByteBuffer buffer, int width, int height, java.awt.Rectangle[] regions) {
        RenderSystem.assertOnRenderThread();
        if (!RinkuPboUpload.isEnabled() || !this.isTextureReady() || this.textureWidth != width || this.textureHeight != height) {
            return false;
        }
        if (this.pbo == null) this.pbo = new RinkuPboUpload();
        GlStateManager._bindTexture(this.texture.getId());
        this.pbo.upload(buffer, width, height, regions);
        return true;
    }

    /**
     * GTWebUI fork spike (shared texture): the browser texture at this size for a GPU copy, its GL id. BGRA sources
     * are copied byte for byte, so the texture swaps red and blue when sampled. 1.20.1 and 1.21.4: the RinkuDirectTexture's own
     * id, given sized RGBA8 storage (glCopyImageSubData needs it) when it is new or resized.
     */
    int sharedTarget(int width, int height, boolean bgra) {
        RenderSystem.assertOnRenderThread();
        if (this.texture == null) return 0;
        boolean fresh = !this.textureUploaded || this.textureWidth != width || this.textureHeight != height;
        this.texture.ensureStorage(width, height);
        this.textureWidth = width;
        this.textureHeight = height;
        int id = this.texture.getId();
        if (fresh) {
            GlStateManager._bindTexture(id);
            org.lwjgl.opengl.GL11C.glTexImage2D(GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11C.GL_RGBA8, width, height, 0,
                    org.lwjgl.opengl.GL11C.GL_RGBA, org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            org.lwjgl.opengl.GL11C.glTexParameteri(GL_TEXTURE_2D, org.lwjgl.opengl.GL33C.GL_TEXTURE_SWIZZLE_R,
                    bgra ? org.lwjgl.opengl.GL11C.GL_BLUE : org.lwjgl.opengl.GL11C.GL_RED);
            org.lwjgl.opengl.GL11C.glTexParameteri(GL_TEXTURE_2D, org.lwjgl.opengl.GL33C.GL_TEXTURE_SWIZZLE_B,
                    bgra ? org.lwjgl.opengl.GL11C.GL_RED : org.lwjgl.opengl.GL11C.GL_BLUE);
        }
        this.textureUploaded = true;   // the caller copies the frame in right after
        return id;
    }

    /** GTWebUI fork (PBO step B): bind the texture for an upload from a mapped buffer, if it has this size. */
    boolean bindForMappedUpload(int width, int height) {
        RenderSystem.assertOnRenderThread();
        if (!this.isTextureReady() || this.textureWidth != width || this.textureHeight != height) return false;
        GlStateManager._bindTexture(this.texture.getId());
        return true;
    }

    protected void onPaint(ByteBuffer buffer, int width, int height) {
        RenderSystem.assertOnRenderThread();
        if (buffer == null || width <= 0 || height <= 0 || this.texture == null) return;
        if (this.textureWidth != width || this.textureHeight != height) {
            this.texture.ensureStorage(width, height);
            this.textureWidth = width;
            this.textureHeight = height;
            this.textureUploaded = false;
        }
        GlStateManager._pixelStore(GL_UNPACK_ROW_LENGTH, width);
        GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, 0);
        this.upload(buffer, 0, 0, width, height);
        this.textureUploaded = true;
    }

    protected void onPaint(ByteBuffer buffer, int x, int y, int width, int height) {
        RenderSystem.assertOnRenderThread();
        if (buffer == null || width <= 0 || height <= 0 || this.texture == null || !this.textureUploaded) return;
        this.upload(buffer, x, y, width, height);
    }

    private void upload(ByteBuffer buffer, int x, int y, int width, int height) {
        GlStateManager._bindTexture(this.texture.getId());
        try {
            GlStateManager._pixelStore(GL_UNPACK_ALIGNMENT, 4);
            glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
        } finally {
            // Pixel-store parameters are global OpenGL state. Leaving CEF's stride/offset behind corrupts later
            // texture uploads in vanilla, Sodium, and Iris, so every upload restores their conventional defaults.
            GlStateManager._pixelStore(GL_UNPACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL_UNPACK_ALIGNMENT, 4);
        }
    }

}
