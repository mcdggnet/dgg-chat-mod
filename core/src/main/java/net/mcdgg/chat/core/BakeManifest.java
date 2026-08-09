package net.mcdgg.chat.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What the baker produces and the client consumes.
 *
 * <p>Every emote becomes one PNG whose distinct frames are stacked vertically, so the
 * client can fetch an emote the first time it is actually seen instead of downloading all
 * 324 up front. Most emotes never appear in a given session.
 *
 * <pre>{@code
 * {
 *   "version": 1,
 *   "generatedAt": "2026-08-08T12:00:00Z",
 *   "emoteCss": "<sha256 of the emotes.css this was baked from>",
 *   "emotes": {
 *     "Askers": { "file": "Askers.9e03911bffe9.png",
 *                 "tileWidth": 53, "tileHeight": 34, "tileCount": 99,
 *                 "frameCount": 99, "frameMs": 35, "iterations": 4,
 *                 "advance": 48, "bearingX": -3, "ascent": 25,
 *                 "minimumSubTier": 0 }
 *   }
 * }
 * }</pre>
 *
 * <p>Timing is described for one iteration and the client repeats it, because baking
 * {@code Askers}' four identical passes would quadruple its download for nothing.
 * {@code frameCount} counts timeline steps while {@code tileCount} counts pictures in the
 * file: they differ whenever sampling landed twice inside the same step, and the optional
 * {@code sequence} array maps step to tile when it is not simply {@code 0..n-1}.
 *
 * <p>Geometry is in destiny.gg CSS pixels, measured from the live DOM rather than derived,
 * so emotes whose transforms draw outside their box come out right: {@code advance} is the
 * horizontal space the emote occupies including its margins, {@code bearingX} the offset
 * from the pen to the tile's left edge, and {@code ascent} the distance from the tile's top
 * edge down to the text baseline.
 */
public final class BakeManifest {

    /** Bumped when the fields change, so an old client ignores a manifest it cannot read. */
    public static final int SUPPORTED_VERSION = 1;

    private final int version;
    private final String generatedAt;
    private final String emoteCssHash;
    private final Map<String, BakedEmote> emotes;
    private final FlairCatalogue flairs;

    private BakeManifest(int version, String generatedAt, String emoteCssHash,
                         Map<String, BakedEmote> emotes, FlairCatalogue flairs) {
        this.version = version;
        this.generatedAt = generatedAt;
        this.emoteCssHash = emoteCssHash;
        this.emotes = Map.copyOf(emotes);
        this.flairs = flairs;
    }

    public static BakeManifest empty() {
        return new BakeManifest(SUPPORTED_VERSION, null, null, Map.of(), FlairCatalogue.empty());
    }

    /**
     * @param json    the manifest body
     * @param baseUrl the URL the manifest itself was fetched from; every {@code file} in it
     *                is relative to that, so a bake can be moved between hosts without
     *                rewriting it. Null leaves file names unresolved, which is only useful
     *                in tests.
     * @throws IllegalArgumentException if the manifest declares a version this build does
     *                                  not understand, which is the signal to keep using the
     *                                  cached copy rather than render nothing
     */
    public static BakeManifest parse(String json, String baseUrl) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        int version = root.has("version") ? root.get("version").getAsInt() : 0;
        if (version != SUPPORTED_VERSION) {
            throw new IllegalArgumentException(
                    "bake manifest version " + version + ", expected " + SUPPORTED_VERSION);
        }

        Map<String, BakedEmote> emotes = new LinkedHashMap<>();
        JsonObject entries = root.getAsJsonObject("emotes");
        if (entries != null) {
            for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
                emotes.put(entry.getKey(),
                        BakedEmote.parse(entry.getKey(), entry.getValue().getAsJsonObject(), baseUrl));
            }
        }
        return new BakeManifest(
                version,
                optionalString(root, "generatedAt"),
                optionalString(root, "emoteCss"),
                emotes,
                parseFlairs(root.getAsJsonArray("flairs"), baseUrl));
    }

    /**
     * Flairs travel with the bake rather than being fetched from destiny.gg directly. Two
     * of the visible icons are WebP and Minecraft's image reader handles PNG only, so they
     * have to pass through the baker anyway; carrying the metadata alongside them means the
     * client talks to one host instead of two and needs no browser user agent to be served.
     */
    private static FlairCatalogue parseFlairs(JsonArray array, String baseUrl) {
        if (array == null) {
            return FlairCatalogue.empty();
        }
        List<Flair> flairs = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            JsonObject object = element.getAsJsonObject();
            String name = optionalString(object, "name");
            if (name == null) {
                continue;
            }
            String colour = optionalString(object, "color");
            flairs.add(new Flair(
                    name,
                    optionalString(object, "label") == null ? name : optionalString(object, "label"),
                    object.has("hidden") && object.get("hidden").getAsBoolean(),
                    object.has("priority") ? object.get("priority").getAsInt() : Integer.MAX_VALUE,
                    colour == null || colour.isEmpty() ? null : colour,
                    object.has("rainbowColor") && object.get("rainbowColor").getAsBoolean(),
                    resolve(baseUrl, optionalString(object, "iconFile")),
                    object.has("iconWidth") ? object.get("iconWidth").getAsInt() : 0,
                    object.has("iconHeight") ? object.get("iconHeight").getAsInt() : 0,
                    object.has("order") ? object.get("order").getAsInt() : Flair.NO_ORDER));
        }
        return FlairCatalogue.of(flairs);
    }

    private static String resolve(String baseUrl, String file) {
        if (file == null) {
            return null;
        }
        if (baseUrl == null) {
            return file;
        }
        try {
            return URI.create(baseUrl).resolve(file).toString();
        } catch (IllegalArgumentException e) {
            return file;
        }
    }

    public int version() {
        return version;
    }

    public String generatedAt() {
        return generatedAt;
    }

    public String emoteCssHash() {
        return emoteCssHash;
    }

    /** The flair list this bake carried, or an empty catalogue if it carried none. */
    public FlairCatalogue flairs() {
        return flairs;
    }

    /** Every emote the client can actually draw, which is what the matcher is built from. */
    public Set<String> prefixes() {
        return emotes.keySet();
    }

    /**
     * The prefixes a sender at {@code subTier} may use, which is the set chat-gui builds its
     * regex from. Pass {@link Integer#MAX_VALUE} to disable gating, which is the right
     * default: without the server half there is no sender tier to gate on, and gating would
     * make a Minecraft player's emotes depend on their destiny.gg subscription.
     */
    public Set<String> prefixesFor(int subTier) {
        if (subTier == Integer.MAX_VALUE) {
            return prefixes();
        }
        Set<String> allowed = new LinkedHashSet<>();
        for (Map.Entry<String, BakedEmote> entry : emotes.entrySet()) {
            if (entry.getValue().minimumSubTier() <= subTier) {
                allowed.add(entry.getKey());
            }
        }
        return allowed;
    }

    public Optional<BakedEmote> get(String prefix) {
        return Optional.ofNullable(emotes.get(prefix));
    }

    public int size() {
        return emotes.size();
    }

    public boolean isEmpty() {
        return emotes.isEmpty();
    }

    private static String optionalString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    /**
     * @param tileCount  distinct pictures in the file; may be fewer than {@code frameCount}
     * @param scale      tile pixels per CSS pixel, below 1 when a tile was too big to fit a
     *                   font page. Geometry stays in CSS pixels, so dividing by this
     *                   recovers the size the emote is meant to be drawn at.
     * @param frameCount timeline steps in one iteration; 1 for a still image
     * @param iterations how many times to play before freezing, or 0 to repeat forever
     * @param sequence   step-to-tile map of length {@code frameCount}, or null for identity.
     *                   Held as an array for lookup cost, which means this record's
     *                   {@code equals} compares it by identity; nothing depends on that.
     */
    public record BakedEmote(
            String prefix,
            String url,
            int tileWidth,
            int tileHeight,
            int tileCount,
            float scale,
            int frameCount,
            int frameMs,
            int iterations,
            int[] sequence,
            float advance,
            float bearingX,
            float ascent,
            int minimumSubTier) {

        static BakedEmote parse(String prefix, JsonObject object, String baseUrl) {
            int tileWidth = object.get("tileWidth").getAsInt();
            int tileHeight = object.get("tileHeight").getAsInt();
            int frameCount = Math.max(1, intOr(object, "frameCount", 1));
            return new BakedEmote(
                    prefix,
                    resolve(baseUrl, object.get("file").getAsString()),
                    tileWidth,
                    tileHeight,
                    Math.max(1, intOr(object, "tileCount", frameCount)),
                    floatOr(object, "scale", 1f),
                    frameCount,
                    Math.max(1, intOr(object, "frameMs", 40)),
                    Math.max(0, intOr(object, "iterations", 0)),
                    intArrayOrNull(object, "sequence"),
                    floatOr(object, "advance", tileWidth),
                    floatOr(object, "bearingX", 0f),
                    // The CSS rule is margin-top:-H with top:H/4, so three quarters of the
                    // box sits above the baseline and a quarter hangs below it.
                    floatOr(object, "ascent", tileHeight * 0.75f),
                    Math.max(0, intOr(object, "minimumSubTier", 0)));
        }

        public boolean isAnimated() {
            return frameCount > 1;
        }

        /** Total playing time before it freezes, or 0 when it never does. */
        public long totalDurationMs() {
            return iterations == 0 ? 0L : (long) frameCount * frameMs * iterations;
        }

        /**
         * Which picture in the file to draw, {@code elapsedMs} after the animation started.
         *
         * <p>A finite animation freezes on its last frame once it has played out, which is
         * what the site does: an emote animates as its message arrives and then sits still.
         */
        public int tileAt(long elapsedMs) {
            if (frameCount <= 1) {
                return 0;
            }
            long periodMs = (long) frameCount * frameMs;
            long elapsed = Math.max(0L, elapsedMs);

            int step;
            if (iterations > 0 && elapsed >= periodMs * iterations) {
                step = frameCount - 1;
            } else {
                step = (int) (elapsed % periodMs / frameMs);
                if (step >= frameCount) {
                    step = frameCount - 1;
                }
            }
            if (sequence == null) {
                return step;
            }
            int tile = sequence[step];
            return tile >= 0 && tile < tileCount ? tile : 0;
        }

        private static int intOr(JsonObject object, String key, int fallback) {
            JsonElement value = object.get(key);
            return value == null || value.isJsonNull() ? fallback : value.getAsInt();
        }

        private static float floatOr(JsonObject object, String key, float fallback) {
            JsonElement value = object.get(key);
            return value == null || value.isJsonNull() ? fallback : value.getAsFloat();
        }

        private static int[] intArrayOrNull(JsonObject object, String key) {
            JsonElement value = object.get(key);
            if (value == null || !value.isJsonArray()) {
                return null;
            }
            JsonArray array = value.getAsJsonArray();
            int[] result = new int[array.size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = array.get(i).getAsInt();
            }
            return result;
        }
    }
}
