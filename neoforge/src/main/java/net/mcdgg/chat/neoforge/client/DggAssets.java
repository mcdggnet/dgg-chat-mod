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
    private static final String NAME_COLON_KEY = "nameColonFormat";
    private static final String SYSTEM_PROPERTY = "dggchat.manifest";

    /** Emote art changes when destiny.gg adds emotes, which is weeks apart, not minutes. */
    private static final Duration REVALIDATE_AFTER = Duration.ofHours(6);

    private static volatile EmoteMatcher matcher = EmoteMatcher.none();
    private static volatile FlairCatalogue flairs = FlairCatalogue.empty();
    private static volatile boolean nameColonFormat = true;

    private DggAssets() {}

    static EmoteMatcher matcher() {
        return matcher;
    }

    static FlairCatalogue flairs() {
        return flairs;
    }

    /** Whether to render vanilla chat as {@code name: message} rather than {@code <name>}. */
    static boolean nameColonFormat() {
        return nameColonFormat;
    }

    /**
     * Kicks off the load. Returns immediately; everything after this happens on a loader
     * thread and publishes through the two volatile fields above.
     */
    static void load(Path gameDirectory, Path configDirectory) {
        Properties config = readConfig(configDirectory);
        nameColonFormat = !"false".equalsIgnoreCase(
                config.getProperty(NAME_COLON_KEY, "true").trim());

        Path cacheDirectory = gameDirectory.resolve("dggchat-cache");
        AssetCache cache = new AssetCache(cacheDirectory, REVALIDATE_AFTER);
        EmoteTextures.useCache(cache);

        String url = manifestUrl(config);
        Thread loader = new Thread(() -> loadManifest(cache, url), "dggchat-manifest");
        loader.setDaemon(true);
        loader.start();
    }

    private static void loadManifest(AssetCache cache, String url) {
        try {
            // Parse before the cache persists, not after. BakeManifest.parse throwing on
            // an unreadable manifest (a version from the future, garbage from a captive
            // portal) is the documented signal to keep using the cached copy — which only
            // works if the download has not already overwritten it. The check hands the
            // rejection to AssetCache, which then serves the last-good bytes as stale.
            BakeManifest[] fresh = new BakeManifest[1];
            AssetCache.Result result = cache.fetch(url, bytes ->
                    fresh[0] = BakeManifest.parse(
                            new String(bytes, java.nio.charset.StandardCharsets.UTF_8), url));
            // fresh[0] is set only when this call downloaded a parseable manifest;
            // otherwise the bytes are the cached copy and get parsed here.
            BakeManifest manifest = fresh[0] != null ? fresh[0] : BakeManifest.parse(result.text(), url);
            if (result.stale()) {
                LOGGER.info("using the cached bake: the manifest at {} is unreachable "
                        + "or not readable by this build", url);
            }
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
     * Reads the config file, writing it out with its defaults when it is missing, because a
     * setting nobody can find is not a setting.
     */
    private static Properties readConfig(Path configDirectory) {
        Properties properties = new Properties();
        Path file = configDirectory.resolve(CONFIG_FILE);

        if (Files.isRegularFile(file)) {
            try (var in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (IOException e) {
                LOGGER.warn("could not read {}; every setting falls back to its default", file, e);
            }
            return properties;
        }

        try {
            Files.createDirectories(configDirectory);
            Files.writeString(file, """
                    # Where dgg-chat-mod fetches baked emotes and flairs from.
                    # Produced by the baker in this repository; see its README.
                    %s=%s

                    # Render chat as "name: message" the way destiny.gg does, instead of
                    # vanilla's "<name> message". Set false to leave the line shape alone.
                    %s=true
                    """.formatted(MANIFEST_KEY, DEFAULT_MANIFEST_URL, NAME_COLON_KEY));
        } catch (IOException e) {
            LOGGER.debug("could not write {}; defaults still apply", file, e);
        }
        return properties;
    }

    /** The system property wins, so a launcher or a test can override without touching disk. */
    private static String manifestUrl(Properties config) {
        String override = System.getProperty(SYSTEM_PROPERTY);
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        String configured = config.getProperty(MANIFEST_KEY);
        return configured == null || configured.isBlank() ? DEFAULT_MANIFEST_URL : configured.trim();
    }
}
