package net.mcdgg.chat.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Features are what flair icons and username colour are computed from, so the shape
 * they arrive in is worth pinning down.
 */
class DggChatIdentityTest {

    private static final UUID UUID_ = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static DggChatIdentity of(List<String> features, int tier) {
        return new DggChatIdentity(UUID_, "Destiny", features, tier);
    }

    @Test
    void featuresAreKeptVerbatim() {
        DggChatIdentity id = of(List.of("subscriber", "flair33", "moderator"), 5);
        assertEquals(List.of("subscriber", "flair33", "moderator"), id.features());
        assertTrue(id.hasFeature("flair33"));
        assertFalse(id.hasFeature("flair42"));
    }

    @Test
    void nullFeaturesBecomeEmptyRatherThanThrowing() {
        // An unlinked or half-resolved player is an ordinary outcome, not an error.
        assertTrue(of(null, 0).features().isEmpty());
    }

    @Test
    void featuresAreDefensivelyCopied() {
        List<String> mutable = new ArrayList<>(List.of("subscriber"));
        DggChatIdentity id = of(mutable, 1);
        mutable.add("admin");
        assertEquals(List.of("subscriber"), id.features());
    }

    @Test
    void subscriberIsAnyNonZeroTier() {
        assertFalse(of(List.of(), 0).isSubscriber());
        assertTrue(of(List.of(), 1).isSubscriber());
        assertThrows(IllegalArgumentException.class, () -> of(List.of(), -1));
    }
}
