package net.mcdgg.chat.neoforge.client;

import com.mojang.logging.LogUtils;
import net.mcdgg.chat.core.AssetCache;
import net.mcdgg.chat.core.BakeManifest;
import net.mcdgg.chat.core.EmoteMatcher;
import net.mcdgg.chat.core.FlairCatalogue;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

/**
 * Loads the bake and keeps hold of it.
 *
 * <p>All of it is off the game thread and none of it is required for the game to run. Until
 * the manifest lands the matcher matches nothing, the flair catalogue is empty, and chat
 * looks exactly like vanilla chat.
 */
final class DggAssets {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Where the baked emotes are served from: GitHub Pages, published by this repository's
     * bake workflow. Overridable, and a custom domain can be pointed at the same site
     * without anything here changing but this line.
     */
    private static final String DEFAULT_MANIFEST_URL =
            "https://mcdggnet.github.io/dgg-chat-mod/manifest.json";

    private static final String CONFIG_FILE = "dggchat.properties";
    private static final String MANIFEST_KEY = "manifestUrl";
    private static final String SYSTEM_PROPERTY = "dggchat.manifest";

    /** Emote art changes when destiny.gg adds emotes, which is weeks apart, not minutes. */
    private static final Duration REVALIDATE_AFTER = Duration.ofHours(6);

    private static volatile EmoteMatcher matcher = EmoteMatcher.none();
    private static volatile FlairCatalogue flairs = FlairCatalogue.empty();

    private DggAssets() {}

    static EmoteMatcher matcher() {
        return matcher;
    }

    static FlairCatalogue flairs() {
        return flairs;
    }

    /**
     * Kicks off the load. Returns immediately; everything after this happens on a loader
     * thread and publishes through the two volatile fields above.
     */
    static void load(Path gameDirectory, Path configDirectory) {
        Path cacheDirectory = gameDirectory.resolve("dggchat-cache");
        AssetCache cache = new AssetCache(cacheDirectory, REVALIDATE_AFTER);
        EmoteTextures.useCache(cache);

        String url = manifestUrl(configDirectory);
        Thread loader = new Thread(() -> loadManifest(cache, url), "dggchat-manifest");
        loader.setDaemon(true);
        loader.start();
    }

    private static void loadManifest(AssetCache cache, String url) {
        try {
            BakeManifest manifest = BakeManifest.parse(cache.fetchText(url), url);
            DggFont.install(manifest);
            flairs = manifest.flairs();
            matcher = EmoteMatcher.of(manifest.prefixes());
            LOGGER.info("loaded {} emotes and {} flairs, baked {}",
                    manifest.size(), manifest.flairs().all().size(), manifest.generatedAt());
        } catch (IOException e) {
            LOGGER.warn("no emote manifest from {} and nothing cached; chat stays plain", url, e);
        } catch (RuntimeException e) {
            LOGGER.warn("emote manifest from {} could not be read; chat stays plain", url, e);
        }
    }

    /**
     * System property first so a launcher or a test can override without touching disk, then
     * the config file, then the default. The file is written out when it is missing, because
     * a setting nobody can find is not a setting.
     */
    private static String manifestUrl(Path configDirectory) {
        String override = System.getProperty(SYSTEM_PROPERTY);
        if (override != null && !override.isBlank()) {
            return override.trim();
        }

        Path file = configDirectory.resolve(CONFIG_FILE);
        if (Files.isRegularFile(file)) {
            Properties properties = new Properties();
            try (var in = Files.newInputStream(file)) {
                properties.load(in);
                String configured = properties.getProperty(MANIFEST_KEY);
                if (configured != null && !configured.isBlank()) {
                    return configured.trim();
                }
            } catch (IOException e) {
                LOGGER.warn("could not read {}; using the default manifest URL", file, e);
            }
            return DEFAULT_MANIFEST_URL;
        }

        try {
            Files.createDirectories(configDirectory);
            Files.writeString(file, """
                    # Where dgg-chat-mod fetches baked emotes and flairs from.
                    # Produced by the baker in this repository; see its README.
                    %s=%s
                    """.formatted(MANIFEST_KEY, DEFAULT_MANIFEST_URL));
        } catch (IOException e) {
            LOGGER.debug("could not write {}; the default URL still applies", file, e);
        }
        return DEFAULT_MANIFEST_URL;
    }
}
