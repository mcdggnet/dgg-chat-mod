package net.mcdgg.chat.fabric.client;

import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;

/**
 * The font atlas page a glyph was stitched into, and the only place that names the
 * texture type, which moved package in 26.3. This is 26.2's copy: each Minecraft version
 * the mod builds for has its own under src/mc&lt;version&gt;, and the rest of the mod uses
 * this class instead.
 */
final class AtlasPage {

    private final GpuTexture texture;

    private AtlasPage(GpuTexture texture) {
        this.texture = texture;
    }

    boolean isClosed() {
        return texture.isClosed();
    }

    /** Writes a whole image at (x, y) through the render device's command encoder. */
    void write(NativeImage image, int x, int y) {
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, image, 0, 0, x, y);
    }

    /** A {@link GlyphBitmap} whose upload hands over the page rather than the texture. */
    abstract static class Bitmap implements GlyphBitmap {

        @Override
        public final void upload(int x, int y, GpuTexture texture) {
            upload(x, y, new AtlasPage(texture));
        }

        abstract void upload(int x, int y, AtlasPage page);
    }
}
