package net.mcdgg.chat.neoforge.client;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.SheetGlyphInfo;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import net.mcdgg.chat.core.BakeManifest;
import net.mcdgg.chat.core.Flair;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import org.lwjgl.opengl.GL11;

import java.util.function.Function;

/**
 * One emote or flair icon, as far as Minecraft's text renderer is concerned.
 *
 * <p>Animation works by re-uploading pixels into the glyph's slot in the font atlas rather
 * than by swapping characters, because a chat line's {@code FormattedCharSequence} is built
 * once when the message arrives and never rebuilt. Changing what the texture contains is
 * the only thing that reaches an already-wrapped line.
 *
 * <p>{@link net.minecraft.client.gui.font.FontTexture#add} binds the atlas page before
 * calling {@link SheetGlyphInfo#upload}, so the page currently bound at that moment is the
 * one this glyph landed on. Reading the binding there is how a later frame knows where to
 * write, and it avoids having to guess which of the font's pages was used.
 */
final class DggGlyph implements GlyphInfo {

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

    /** Published by the loader thread, read by the render thread. */
    private volatile NativeImage image;
    private volatile boolean loading;

    /** Render thread only, all four. */
    private int atlasTexture = -1;
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

    @Override
    public float getAdvance() {
        return advanceCss / DggFont.PIXELS_PER_UNIT;
    }

    /** Emotes are pictures; emboldening one by smearing it sideways looks like a mistake. */
    @Override
    public float getBoldOffset() {
        return 0.0f;
    }

    @Override
    public BakedGlyph bake(Function<SheetGlyphInfo, BakedGlyph> baker) {
        EmoteTextures.request(this);
        return baker.apply(new SheetGlyphInfo() {
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
            public void upload(int x, int y) {
                atlasTexture = GlStateManager._getInteger(GL11.GL_TEXTURE_BINDING_2D);
                atlasX = x;
                atlasY = y;
                uploadedTile = -1;
                // The slot is uninitialised GPU memory until something writes to it, so it
                // gets a frame now whether or not the download has landed.
                if (!uploadTile(0)) {
                    EmoteTextures.uploadBlank(atlasTexture, x, y, tileWidth, tileHeight);
                }
                EmoteTextures.track(DggGlyph.this);
            }
        });
    }

    /** Called by the loader thread once the strip has decoded. */
    void attach(NativeImage decoded) {
        this.image = decoded;
        this.loading = false;
        // Force the next animate() to write, even if it lands on the tile already shown.
        this.uploadedTile = -1;
    }

    String url() {
        return url;
    }

    boolean needsLoad() {
        return url != null && image == null && !loading;
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
        if (animation == null || atlasTexture < 0 || image == null) {
            return;
        }
        int tile = animation.tileAt(nowMs - startedAtMs);
        if (tile != uploadedTile) {
            uploadTile(tile);
        }
    }

    private boolean uploadTile(int tile) {
        NativeImage current = image;
        if (current == null || atlasTexture < 0 || tile < 0 || tile >= tileCount) {
            return false;
        }
        int previous = GlStateManager._getInteger(GL11.GL_TEXTURE_BINDING_2D);
        GlStateManager._bindTexture(atlasTexture);
        current.upload(0, atlasX, atlasY, 0, tile * tileHeight, tileWidth, tileHeight, false, false);
        GlStateManager._bindTexture(previous);
        uploadedTile = tile;
        return true;
    }

    /**
     * Forgets where this glyph lives in the atlas, because after a resource reload it does
     * not live there any more. Uploading to the old page would scribble on whatever font
     * Minecraft has since put in its place.
     */
    void detach() {
        atlasTexture = -1;
        uploadedTile = -1;
    }

    void releaseImage() {
        NativeImage held = image;
        image = null;
        loading = false;
        if (held != null) {
            held.close();
        }
    }
}
