package net.mcdgg.chat.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Colour expectations come from running chat-gui's {@code usernameColorFlair} in V8 against
 * destiny.gg's live {@code flairs.json}, not from this implementation.
 */
class FlairCatalogueTest {

    private final FlairCatalogue catalogue = Fixtures.flairs();

    private String winner(String... features) {
        return catalogue.usernameColorFlair(List.of(features)).map(Flair::name).orElse(null);
    }

    @Test
    @DisplayName("four coloured flairs tie at priority 3 and flair33 takes the name")
    void rainbowTierWins() {
        assertEquals("flair33", winner("subscriber", "flair33", "flair42", "flair7", "flair12"));
        assertTrue(catalogue.byName("flair33").orElseThrow().rainbowColor());
    }

    @Test
    void lowerPriorityWinsAcrossDifferentValues() {
        assertEquals("flair13", winner("moderator", "flair13", "subscriber"));
        assertEquals("flair17", winner("admin", "flair17"));
    }

    @Test
    @DisplayName("a tie resolves the same way whichever order the features arrive in")
    void tieIsIndependentOfFeatureOrder() {
        assertEquals("flair26", winner("flair26", "flair8"));
        assertEquals("flair26", winner("flair8", "flair26"));
    }

    @Test
    void everyFlairAtOnceStillResolvesToTheLowestPriority() {
        String[] all = catalogue.all().stream().map(Flair::name).toArray(String[]::new);
        assertEquals("flair17", winner(all));
    }

    @Test
    void unknownAndEmptyFeaturesGetNoColour() {
        assertEquals(Optional.empty(), catalogue.usernameColorFlair(List.of("nope", "alsonope")));
        assertEquals(Optional.empty(), catalogue.usernameColorFlair(List.of()));
        assertEquals(Optional.empty(), catalogue.usernameColorFlair(null));
    }

    @Test
    @DisplayName("icons follow the CSS order property, not priority and not feature order")
    void iconOrder() {
        List<String> icons = catalogue
                .icons(List.of("moderator", "flair22", "admin", "flair26", "flair12", "flair7", "flair33"))
                .stream()
                .map(Flair::name)
                .toList();
        // orders: flair33/flair7/flair12 at 3, flair26 at 4, flair22 at 6. Hidden ones dropped.
        assertEquals(List.of("flair33", "flair7", "flair12", "flair26", "flair22"), icons);
    }

    @Test
    void hiddenFlairsColourTheNameButDrawNothing() {
        assertTrue(catalogue.byName("moderator").orElseThrow().hidden());
        assertTrue(catalogue.icons(List.of("moderator", "admin", "subscriber")).isEmpty());
        assertEquals("moderator", winner("moderator"));
    }

    @Test
    @DisplayName("flair125 publishes an all-null image and must not be assumed drawable")
    void flairWithoutAnImage() {
        Flair headMod = catalogue.byName("flair125").orElseThrow();
        assertFalse(headMod.hasIcon());
        assertTrue(headMod.hasColor());
        assertEquals(0xFFD88C, headMod.colorRgb(0));
    }

    @Test
    void coloursParseToRgb() {
        assertEquals(0xEB79DA, catalogue.byName("flair33").orElseThrow().colorRgb(0));
        assertEquals(0xEE1F1F, catalogue.byName("admin").orElseThrow().colorRgb(0));
        assertEquals(0x123456, new Flair("x", "x", false, 1, "nonsense", false, null, 0, 0, 0).colorRgb(0x123456));
        assertEquals(0x112233, new Flair("x", "x", false, 1, "#123", false, null, 0, 0, 0).colorRgb(0));
        assertEquals(0x999999, new Flair("x", "x", false, 1, null, false, null, 0, 0, 0).colorRgb(0x999999));
    }

    @Test
    @DisplayName("without the CSS, colours still work and icon order degrades to catalogue order")
    void cssIsOptional() {
        FlairCatalogue jsonOnly = FlairCatalogue.parse(Fixtures.FLAIRS_JSON, null);
        assertEquals("flair33",
                jsonOnly.usernameColorFlair(List.of("flair33", "flair7")).map(Flair::name).orElse(null));
        assertEquals(Flair.NO_ORDER, jsonOnly.byName("flair33").orElseThrow().order());
    }

    @Test
    void emptyCatalogueAnswersNothing() {
        assertTrue(FlairCatalogue.empty().isEmpty());
        assertEquals(Optional.empty(), FlairCatalogue.empty().usernameColorFlair(List.of("admin")));
        assertTrue(FlairCatalogue.empty().icons(List.of("admin")).isEmpty());
    }
}
