package net.mcdgg.chat.neoforge.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.mcdgg.chat.core.AssetCache;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
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
final class EmoteTextures {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Its own threads, deliberately.
     *
     * <p>These calls block on the network and Minecraft's shared background pool is a
     * work-stealing pool sized for CPU work; parking two of its threads on a slow CDN on a
     * pack as heavy as ATM10 is exactly the kind of stall this mod must not cause.
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
                glyph.attach(NativeImage.read(new ByteArrayInputStream(bytes)));
            } catch (Exception e) {
                // One emote that will not load is one emote that stays blank. Remember it so
                // a busy chat does not retry the same broken URL on every frame.
                FAILED.add(glyph.url());
                glyph.attach(null);
                LOGGER.warn("could not load emote image {}", glyph.url(), e);
            }
        });
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
    static void animate(long nowMs) {
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
    static void uploadBlank(int texture, int x, int y, int width, int height) {
        int previous = GlStateManager._getInteger(GL11.GL_TEXTURE_BINDING_2D);
        GlStateManager._bindTexture(texture);
        try (NativeImage blank = new NativeImage(NativeImage.Format.RGBA, width, height, true)) {
            blank.upload(0, x, y, 0, 0, width, height, false, false);
        }
        GlStateManager._bindTexture(previous);
    }
}
