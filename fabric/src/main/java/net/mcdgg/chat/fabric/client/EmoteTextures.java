package net.mcdgg.chat.fabric.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.mcdgg.chat.core.AssetCache;
import net.mcdgg.chat.fabric.DggChatFabric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fetching emote images, and advancing them once they are on the GPU.
 *
 * <p>Images are pulled the first time an emote is actually drawn, not up front. A session
 * typically sees a few dozen of the 321 emotes, so downloading them all at launch would
 * spend twelve megabytes to save nothing.
 */
public final class EmoteTextures {

    private static final Logger LOGGER = LoggerFactory.getLogger(DggChatFabric.MOD_ID);

    /**
     * Its own threads, deliberately.
     *
     * <p>These calls block on the network and Minecraft's shared background pool is a
     * work-stealing pool sized for CPU work; parking two of its threads on a slow CDN is
     * exactly the kind of stall this mod must not cause.
     */
    private static final ExecutorService LOADER = Executors.newFixedThreadPool(2, daemonThreads());

    /** Glyphs that have a slot in the atlas, which is the set worth animating. */
    private static final List<DggGlyph> STITCHED = new CopyOnWriteArrayList<>();
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

    private static volatile AssetCache cache;

    private EmoteTextures() {}

    private static ThreadFactory daemonThreads() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "dggchat-emote-loader-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    static void useCache(AssetCache assetCache) {
        cache = assetCache;
    }

    /** Idempotent: the first draw of an emote starts its download, later ones do nothing. */
    static void request(DggGlyph glyph) {
        AssetCache assetCache = cache;
        if (assetCache == null || !glyph.needsLoad() || FAILED.contains(glyph.url())) {
            return;
        }
        glyph.markLoading();
        LOADER.execute(() -> {
            try {
                byte[] bytes = assetCache.fetch(glyph.url()).bytes();
                glyph.attach(splitTiles(NativeImage.read(bytes), glyph));
            } catch (Exception e) {
                // One emote that will not load is one emote that stays blank. Remember it so
                // a busy chat does not retry the same broken URL on every frame.
                FAILED.add(glyph.url());
                glyph.attach(null);
                LOGGER.warn("could not load emote image {}", glyph.url(), e);
            }
        });
    }

    /**
     * Cuts the vertical strip into one image per tile, then frees the strip.
     *
     * <p>The command encoder writes a whole image at an offset; there is no sub-rectangle
     * upload the way GL's {@code glTexSubImage2D} with a row stride gave the 1.21 mod. Per-tile
     * images cost the same native memory as the strip did, and make each frame one call.
     * A strip shorter than the manifest promised yields as many tiles as fit, so a
     * mismatched bake shows fewer frames rather than reading past the end.
     */
    private static NativeImage[] splitTiles(NativeImage strip, DggGlyph glyph) {
        int width = Math.min(glyph.tileWidth(), strip.getWidth());
        int height = glyph.tileHeight();
        int count = Math.max(1, Math.min(glyph.tileCount(), strip.getHeight() / Math.max(1, height)));
        NativeImage[] tiles = new NativeImage[count];
        try (strip) {
            for (int i = 0; i < count; i++) {
                NativeImage tile = new NativeImage(NativeImage.Format.RGBA, glyph.tileWidth(), height, true);
                int rows = Math.min(height, strip.getHeight() - i * height);
                // NativeImage.copyRect copies FROM this INTO the first argument.
                strip.copyRect(tile, 0, i * height, 0, 0, width, rows, false, false);
                tiles[i] = tile;
            }
        } catch (RuntimeException e) {
            for (NativeImage tile : tiles) {
                if (tile != null) {
                    tile.close();
                }
            }
            throw e;
        }
        return tiles;
    }

    static void track(DggGlyph glyph) {
        if (!STITCHED.contains(glyph)) {
            STITCHED.add(glyph);
        }
    }

    /**
     * Called once per rendered frame, before the GUI draws.
     *
     * <p>A frame rather than a tick: emotes are baked at 20 to 30 frames a second and ticks
     * arrive at 20, so animating on ticks would alias. It also keeps emotes moving while the
     * game is paused with chat open, which is when people are actually looking at chat.
     */
    public static void animate(long nowMs) {
        for (DggGlyph glyph : STITCHED) {
            glyph.animate(nowMs);
        }
    }

    /** After a resource reload every atlas slot is gone, so no glyph may write to one. */
    static void onFontReloaded() {
        for (DggGlyph glyph : STITCHED) {
            glyph.detach();
        }
        STITCHED.clear();
    }

    /** Writes transparent pixels into a freshly stitched slot, which is otherwise garbage. */
    static void uploadBlank(GpuTexture texture, int x, int y, int width, int height) {
        // calloc'd, so every pixel is already transparent black.
        try (NativeImage blank = new NativeImage(NativeImage.Format.RGBA, width, height, true)) {
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, blank, 0, 0, x, y);
        }
    }
}
