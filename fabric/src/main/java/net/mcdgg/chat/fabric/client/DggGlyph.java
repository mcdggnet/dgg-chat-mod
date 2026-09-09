package net.mcdgg.chat.fabric.client;

import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.UnbakedGlyph;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.mcdgg.chat.core.BakeManifest;
import net.mcdgg.chat.core.Flair;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;

/**
 * One emote or flair icon, as far as Minecraft's text renderer is concerned.
 *
 * <p>Animation works by re-uploading pixels into the glyph's slot in the font atlas rather
 * than by swapping characters, because a chat line's {@code FormattedCharSequence} is built
 * once when the message arrives and never rebuilt. Changing what the texture contains is
 * the only thing that reaches an already-wrapped line.
 *
 * <p>26.2 hands the atlas page to {@link GlyphBitmap#upload(int, int, GpuTexture)} rather
 * than leaving it bound in GL, and texture writes go through the render device's command
 * encoder, which writes a whole {@link NativeImage} at an offset. So the strip is split into
 * one image per tile when it decodes, and a frame is one {@code writeToTexture} of that tile.
 */
final class DggGlyph implements UnbakedGlyph, GlyphInfo {

    /** Drawn before the image finishes downloading, and for codepoints nothing claims. */
    static final DggGlyph EMPTY = new DggGlyph(null, null, 1, 1, 1, 1f, 0f, 0f, 0f);

    private final String url;
    /** Null for flair icons and for {@link #EMPTY}: only emotes have a timeline. */
    private final BakeManifest.BakedEmote animation;

    private final int tileWidth;
    private final int tileHeight;
    private final int tileCount;
    /** Tile pixels per CSS pixel, below 1 for emotes too big to fit a font page. */
    private final float pixelScale;

    private final float advanceCss;
    private final float bearingXCss;
    private final float ascentCss;

    /** Published by the loader thread, read by the render thread. One image per tile. */
    private volatile NativeImage[] tiles;
    private volatile boolean loading;

    /** Render thread only, all four. */
    private GpuTexture atlasTexture;
    private int atlasX;
    private int atlasY;
    private int uploadedTile = -1;

    private volatile long startedAtMs;

    private DggGlyph(String url, BakeManifest.BakedEmote animation,
                     int tileWidth, int tileHeight, int tileCount, float pixelScale,
                     float advanceCss, float bearingXCss, float ascentCss) {
        this.url = url;
        this.animation = animation;
        this.tileWidth = tileWidth;
        this.tileHeight = tileHeight;
        this.tileCount = tileCount;
        this.pixelScale = pixelScale <= 0f ? 1f : pixelScale;
        this.advanceCss = advanceCss;
        this.bearingXCss = bearingXCss;
        this.ascentCss = ascentCss;
    }

    static DggGlyph forEmote(BakeManifest.BakedEmote emote) {
        return new DggGlyph(emote.url(), emote,
                emote.tileWidth(), emote.tileHeight(), emote.tileCount(), emote.scale(),
                emote.advance(), emote.bearingX(), emote.ascent());
    }

    static DggGlyph forFlair(Flair flair) {
        // Two CSS pixels of trailing space, so a row of icons does not run together, and an
        // ascent that leaves most of the icon above the baseline the way the site does.
        return new DggGlyph(flair.iconUrl(), null,
                flair.iconWidth(), flair.iconHeight(), 1, 1f,
                flair.iconWidth() + 2f, 0f, flair.iconHeight() * 0.85f);
    }

    int tileWidth() {
        return tileWidth;
    }

    int tileHeight() {
        return tileHeight;
    }

    int tileCount() {
        return tileCount;
    }

    // GlyphInfo: the metrics the layout pass reads.

    @Override
    public GlyphInfo info() {
        return this;
    }

    @Override
    public float getAdvance() {
        return advanceCss / DggFont.PIXELS_PER_UNIT;
    }

    /** Emotes are pictures; emboldening one by smearing it sideways looks like a mistake. */
    @Override
    public float getBoldOffset() {
        return 0.0f;
    }

    // UnbakedGlyph: the stitch into an atlas page.

    @Override
    public BakedGlyph bake(UnbakedGlyph.Stitcher stitcher) {
        EmoteTextures.request(this);
        return stitcher.stitch(this, new GlyphBitmap() {
            @Override
            public int getPixelWidth() {
                return tileWidth;
            }

            @Override
            public int getPixelHeight() {
                return tileHeight;
            }

            @Override
            public boolean isColored() {
                return true;
            }

            @Override
            public float getOversample() {
                return DggFont.PIXELS_PER_UNIT * pixelScale;
            }

            @Override
            public float getBearingLeft() {
                return bearingXCss / DggFont.PIXELS_PER_UNIT;
            }

            @Override
            public float getBearingTop() {
                return ascentCss / DggFont.PIXELS_PER_UNIT;
            }

            @Override
            public void upload(int x, int y, GpuTexture texture) {
                atlasTexture = texture;
                atlasX = x;
                atlasY = y;
                uploadedTile = -1;
                // The slot is uninitialised GPU memory until something writes to it, so it
                // gets a frame now whether or not the download has landed.
                if (!uploadTile(0)) {
                    EmoteTextures.uploadBlank(texture, x, y, tileWidth, tileHeight);
                }
                EmoteTextures.track(DggGlyph.this);
            }
        });
    }

    /** Called by the loader thread once the strip has decoded and been split into tiles. */
    void attach(NativeImage[] decoded) {
        this.tiles = decoded;
        this.loading = false;
        if (decoded != null) {
            // Force the next animate() to write, even if it lands on the tile already
            // shown. uploadedTile is render-thread state and this is the loader thread,
            // so the write hops rather than racing bake()'s upload(); the frame of
            // delay costs nothing because animate() cannot draw before tiles is set,
            // and it reads tiles through the volatile.
            net.minecraft.client.Minecraft.getInstance().execute(() -> this.uploadedTile = -1);
        }
    }

    /** Whether this glyph holds decoded images, i.e. native memory worth releasing. */
    boolean holdsImage() {
        return tiles != null;
    }

    String url() {
        return url;
    }

    boolean needsLoad() {
        return url != null && tiles == null && !loading;
    }

    void markLoading() {
        this.loading = true;
    }

    boolean isAnimated() {
        return animation != null && animation.isAnimated();
    }

    /**
     * Restarts the timeline, which is what makes a finite animation play when a message
     * carrying the emote arrives.
     *
     * <p>Every instance of an emote shares one texture and therefore one timeline, so two
     * copies on screen animate in lockstep and a new message restarts the older ones. That
     * is a visible difference from the site, and the alternative is a separate atlas slot
     * per occurrence, which is not worth it.
     */
    void restart(long nowMs) {
        this.startedAtMs = nowMs;
    }

    /** Render thread. Writes the current frame into the atlas if it has changed. */
    void animate(long nowMs) {
        if (atlasTexture == null || tiles == null) {
            return;
        }
        if (atlasTexture.isClosed()) {
            // The page went away under us (a reload this mod did not see). Writing to a
            // closed texture throws, and a throw here would take the frame down.
            detach();
            return;
        }
        if (animation == null) {
            // A still image needs exactly one upload, and bake() may not have managed it:
            // the slot is blanked there when the download has not landed yet, and for a
            // still there is no later frame to correct it. Without this a flair whose PNG
            // arrived after its glyph was baked stays blank for the rest of the session,
            // which reads as a correctly sized gap where the icon should be.
            if (uploadedTile != 0) {
                uploadTile(0);
            }
            return;
        }
        int tile = animation.tileAt(nowMs - startedAtMs);
        if (tile != uploadedTile) {
            uploadTile(tile);
        }
    }

    private boolean uploadTile(int tile) {
        NativeImage[] current = tiles;
        if (current == null || atlasTexture == null || tile < 0 || tile >= current.length) {
            return false;
        }
        try {
            RenderSystem.getDevice().createCommandEncoder()
                    .writeToTexture(atlasTexture, current[tile], 0, 0, atlasX, atlasY);
        } catch (IllegalStateException closed) {
            // The image was closed under us. releaseImage() runs on this thread now, so
            // this should be unreachable, but a throw here escapes into the frame and
            // takes the whole client down, which is never a fair price for one emote.
            // Drop the images; the atlas keeps its last frame.
            tiles = null;
            return false;
        }
        uploadedTile = tile;
        return true;
    }

    /**
     * Forgets where this glyph lives in the atlas, because after a resource reload it does
     * not live there any more. Uploading to the old page would scribble on whatever font
     * Minecraft has since put in its place.
     */
    void detach() {
        atlasTexture = null;
        uploadedTile = -1;
    }

    /**
     * Frees the decoded tiles. Render thread only: animate() uploads from these images on
     * the render thread, and closing native memory mid-upload is a use-after-free, so
     * the close must be serialized behind any in-flight frame. {@link DggFont#install}
     * gets there by queueing this through {@code Minecraft.getInstance().execute}.
     */
    void releaseImage() {
        NativeImage[] held = tiles;
        tiles = null;
        loading = false;
        if (held != null) {
            for (NativeImage image : held) {
                image.close();
            }
        }
    }
}
