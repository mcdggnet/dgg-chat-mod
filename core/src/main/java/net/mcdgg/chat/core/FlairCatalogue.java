package net.mcdgg.chat.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * destiny.gg's flair list, and the two questions chat asks of it: what colour is this
 * person's name, and which icons go in front of it.
 *
 * <p>Colours and hidden flags come from {@code flairs.json}, which was cross-checked
 * against {@code flairs.css} and agrees on all 46 entries. Icon sequence comes from the
 * CSS, because the flex {@code order} property is published nowhere else and is a
 * different number from the JSON's {@code priority}.
 */
public final class FlairCatalogue {

    /** {@code .flair.<name> { ... }}. Ignores {@code .user.<name>}, which only sets colour. */
    private static final Pattern CSS_RULE =
            Pattern.compile("\\.flair\\.([A-Za-z0-9_-]+)\\s*\\{([^}]*)}");
    private static final Pattern CSS_ORDER = Pattern.compile("\\border\\s*:\\s*(-?\\d+)");
    private static final Pattern CSS_DISPLAY_NONE = Pattern.compile("\\bdisplay\\s*:\\s*none");

    /** Catalogue order, which is the tie-break for icons that share a CSS {@code order}. */
    private final List<Flair> all;
    private final Map<String, Flair> byName;

    private FlairCatalogue(List<Flair> all) {
        this.all = List.copyOf(all);
        Map<String, Flair> index = new HashMap<>(all.size() * 2);
        for (Flair flair : all) {
            index.put(flair.name(), flair);
        }
        this.byName = Map.copyOf(index);
    }

    public static FlairCatalogue empty() {
        return new FlairCatalogue(List.of());
    }

    public static FlairCatalogue of(List<Flair> flairs) {
        return new FlairCatalogue(flairs);
    }

    /**
     * @param flairsJson the body of {@code flairs.json}
     * @param flairsCss  the body of {@code flairs.css}, or null to fall back to JSON alone,
     *                   which costs correct icon ordering but still gives correct colours
     */
    public static FlairCatalogue parse(String flairsJson, String flairsCss) {
        Map<String, CssFlair> css = flairsCss == null ? Map.of() : parseCss(flairsCss);
        JsonArray array = JsonParser.parseString(flairsJson).getAsJsonArray();
        List<Flair> flairs = new ArrayList<>(array.size());

        for (JsonElement element : array) {
            JsonObject object = element.getAsJsonObject();
            String name = string(object, "name");
            if (name == null) {
                continue;
            }
            CssFlair rule = css.getOrDefault(name, CssFlair.ABSENT);
            JsonObject image = firstImage(object);

            flairs.add(new Flair(
                    name,
                    orDefault(string(object, "label"), name),
                    bool(object, "hidden") || rule.hidden(),
                    integer(object, "priority", Integer.MAX_VALUE),
                    emptyToNull(string(object, "color")),
                    bool(object, "rainbowColor"),
                    image == null ? null : string(image, "url"),
                    image == null ? 0 : integer(image, "width", 0),
                    image == null ? 0 : integer(image, "height", 0),
                    rule.order()));
        }
        return new FlairCatalogue(flairs);
    }

    private static Map<String, CssFlair> parseCss(String css) {
        Map<String, CssFlair> rules = new LinkedHashMap<>();
        Matcher matcher = CSS_RULE.matcher(css);
        while (matcher.find()) {
            String name = matcher.group(1);
            String body = matcher.group(2);

            CssFlair existing = rules.getOrDefault(name, CssFlair.ABSENT);
            Matcher order = CSS_ORDER.matcher(body);
            // Later rules win, as they would in the cascade.
            int resolvedOrder = order.find() ? Integer.parseInt(order.group(1)) : existing.order();
            boolean resolvedHidden = existing.hidden() || CSS_DISPLAY_NONE.matcher(body).find();
            rules.put(name, new CssFlair(resolvedOrder, resolvedHidden));
        }
        return rules;
    }

    public List<Flair> all() {
        return all;
    }

    public Optional<Flair> byName(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public boolean isEmpty() {
        return all.isEmpty();
    }

    /**
     * chat-gui's {@code usernameColorFlair}, ported line for line:
     *
     * <pre>{@code
     * allFlairs
     *   .filter((flair) => user.features.some((userFlair) => userFlair === flair.name))
     *   .sort((a, b) => (a.priority - b.priority >= 0 ? 1 : -1))
     *   .find((f) => f.rainbowColor || f.color);
     * }</pre>
     *
     * <p>Lower priority wins, and only the first flair carrying a colour counts; the rest
     * contribute icons only. The comparator is deliberately the broken one from the site,
     * which is why the sort is {@link JsArraySort} and not {@link List#sort}.
     */
    public Optional<Flair> usernameColorFlair(Collection<String> features) {
        if (features == null || features.isEmpty()) {
            return Optional.empty();
        }
        List<Flair> matched = new ArrayList<>();
        for (Flair flair : all) {
            if (features.contains(flair.name())) {
                matched.add(flair);
            }
        }
        JsArraySort.sort(matched, (a, b) -> a.priority() - b.priority() >= 0 ? 1 : -1);
        for (Flair flair : matched) {
            if (flair.hasColor()) {
                return Optional.of(flair);
            }
        }
        return Optional.empty();
    }

    /**
     * The icon row, in the sequence the site draws it: CSS {@code order} ascending, with
     * unknown features, hidden flairs and flairs with no usable image dropped.
     *
     * <p>Equal {@code order} values are common — most visible flairs sit at 127 — and
     * flexbox falls back to document order there. Catalogue order stands in for that, which
     * keeps the result stable rather than dependent on how {@code features} was built.
     */
    public List<Flair> icons(Collection<String> features) {
        if (features == null || features.isEmpty()) {
            return List.of();
        }
        List<Flair> icons = new ArrayList<>();
        for (Flair flair : all) {
            if (!flair.hidden() && flair.hasIcon() && features.contains(flair.name())) {
                icons.add(flair);
            }
        }
        icons.sort((a, b) -> Integer.compare(a.order(), b.order()));
        return List.copyOf(icons);
    }

    private static JsonObject firstImage(JsonObject flair) {
        JsonElement images = flair.get("image");
        if (images == null || !images.isJsonArray() || images.getAsJsonArray().isEmpty()) {
            return null;
        }
        JsonElement first = images.getAsJsonArray().get(0);
        return first.isJsonObject() ? first.getAsJsonObject() : null;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && !value.isJsonNull() && value.getAsBoolean();
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsInt();
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }

    /** chat-gui tests {@code f.color} for truthiness, so an empty string is no colour. */
    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private record CssFlair(int order, boolean hidden) {
        static final CssFlair ABSENT = new CssFlair(Flair.NO_ORDER, false);
    }
}
