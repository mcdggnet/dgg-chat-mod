package net.mcdgg.chat.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * destiny.gg's flair list, and the two questions chat asks of it: what colour is this
 * person's name, and which icons go in front of it.
 *
 * <p>Built by {@link BakeManifest}, which is the only thing that constructs one. Flairs
 * reach the client through the bake rather than from destiny.gg directly, because two of
 * the visible icons are WebP and Minecraft's image reader handles PNG only, and because the
 * CDN answers 403 to user agents it does not recognise. Joining the JSON against the CSS
 * for colour, {@code hidden} and icon {@code order} therefore happens in the baker; this
 * holds the result and answers questions about it.
 */
public final class FlairCatalogue {

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

    /** @param flairs in destiny.gg's own order, which is what breaks priority ties */
    public static FlairCatalogue of(List<Flair> flairs) {
        return new FlairCatalogue(flairs);
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
}
