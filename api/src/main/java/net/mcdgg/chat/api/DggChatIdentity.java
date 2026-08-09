package net.mcdgg.chat.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What the client needs to render one player's name the way destiny.gg would.
 *
 * <p>This is deliberately a subset of {@code net.mcdgg.auth.api.DggIdentity}: the ban is
 * the auth service's business and has nothing to do with drawing a name, so it is not
 * carried here and never crosses the wire.
 *
 * <p>{@code features} are Destiny.gg flair names, verbatim and untranslated, e.g.
 * {@code ["subscriber", "flair33", "moderator"]}. Appearance is resolved from these
 * names against destiny.gg's own {@code flairs.json} at render time. Resolving colours
 * or icons here would fork that logic and go stale the moment the site changes a flair.
 *
 * @param minecraftUuid the player this describes
 * @param dggNick       Destiny.gg nickname, with its own casing
 * @param features      flair names, verbatim; empty for an unlinked player
 * @param subTier       0 for no subscription
 */
public record DggChatIdentity(UUID minecraftUuid, String dggNick, List<String> features, int subTier) {

    public DggChatIdentity {
        Objects.requireNonNull(minecraftUuid, "minecraftUuid");
        Objects.requireNonNull(dggNick, "dggNick");
        // Copied rather than trusted: a source handing over a mutable list must not be
        // able to change what has already been sent to clients.
        features = features == null ? List.of() : List.copyOf(features);
        if (subTier < 0) {
            throw new IllegalArgumentException("subTier must not be negative: " + subTier);
        }
    }

    public boolean hasFeature(String feature) {
        return features.contains(feature);
    }

    public boolean isSubscriber() {
        return subTier > 0;
    }
}
