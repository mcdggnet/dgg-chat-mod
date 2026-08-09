package net.mcdgg.chat.core;

import net.mcdgg.chat.core.BakeManifest.BakedEmote;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fixture is the real output of a bake, trimmed to four emotes: a sprite strip played
 * four times, a still image, a GIF whose sampling landed twice inside some frames, and a
 * tier-gated one.
 */
class BakeManifestTest {

    private static final String JSON = """
            {
              "version": 1,
              "generatedAt": "2026-08-08T12:00:00Z",
              "emoteCss": "abc123",
              "emotes": {
                "Askers":   {"file":"Askers.9e03911bffe9.png","tileWidth":53,"tileHeight":34,
                             "tileCount":99,"frameCount":99,"frameMs":35,"iterations":4,
                             "advance":48,"bearingX":-3,"ascent":25},
                "Abathur":  {"file":"Abathur.c3d2096f0fa7.png","tileWidth":82,"tileHeight":30},
                "ALARMA":   {"file":"ALARMA.25a5ae7a390a.png","tileWidth":65,"tileHeight":30,
                             "tileCount":3,"frameCount":6,"frameMs":50,"iterations":0,
                             "sequence":[0,0,1,1,2,2]},
                "TierFive": {"file":"TierFive.png","tileWidth":32,"tileHeight":32,
                             "minimumSubTier":5}
              },
              "flairs": [
                {"name":"flair33","label":"Tier 5","hidden":false,"priority":3,
                 "color":"#eb79da","rainbowColor":true,"order":3,
                 "iconFile":"flair-flair33.efda26884d0b.png","iconWidth":18,"iconHeight":19},
                {"name":"flair5","label":"Contributor","hidden":false,"priority":127,
                 "color":"","rainbowColor":false,"order":127,
                 "iconFile":"flair-flair5.7a600a7986c9.png","iconWidth":18,"iconHeight":18},
                {"name":"moderator","label":"Moderator","hidden":true,"priority":127,
                 "color":"#DB4C1C","rainbowColor":false,"order":2147483647}
              ]
            }
            """;

    private static final String BASE = "https://mcdgg.net/emotes/2026-08-08/manifest.json";

    private final BakeManifest manifest = BakeManifest.parse(JSON, BASE);

    @Test
    void parsesGeometry() {
        BakedEmote askers = manifest.get("Askers").orElseThrow();
        assertEquals("https://mcdgg.net/emotes/2026-08-08/Askers.9e03911bffe9.png", askers.url());
        assertEquals(53, askers.tileWidth());
        assertEquals(99, askers.tileCount());
        assertEquals(48f, askers.advance());
        // Negative because -4px of margin pulls the drawn pixels left of the pen.
        assertEquals(-3f, askers.bearingX());
        assertEquals(25f, askers.ascent());
        assertTrue(askers.isAnimated());
    }

    @Test
    @DisplayName("a still emote needs only its tile size; the rest defaults sensibly")
    void defaultsForAStillImage() {
        BakedEmote abathur = manifest.get("Abathur").orElseThrow();
        assertEquals(1, abathur.frameCount());
        assertEquals(1, abathur.tileCount());
        assertFalse(abathur.isAnimated());
        assertEquals(82f, abathur.advance());
        assertEquals(0f, abathur.bearingX());
        // margin-top:-H with top:H/4 leaves three quarters of the box above the baseline.
        assertEquals(22.5f, abathur.ascent());
        assertEquals(0, abathur.tileAt(999_999L));
    }

    @Test
    void stepsAdvanceOnTheWallClock() {
        BakedEmote askers = manifest.get("Askers").orElseThrow();
        assertEquals(0, askers.tileAt(0L));
        assertEquals(1, askers.tileAt(35L));
        assertEquals(98, askers.tileAt(3464L));
        assertEquals(0, askers.tileAt(3465L), "second iteration restarts");
        // A wall clock can hand this a time before the animation nominally began.
        assertEquals(0, askers.tileAt(-1L));
    }

    @Test
    @DisplayName("a finite animation freezes on its last frame, as it does on the site")
    void finiteAnimationsStop() {
        BakedEmote askers = manifest.get("Askers").orElseThrow();
        assertEquals(4 * 99 * 35, askers.totalDurationMs());
        assertEquals(98, askers.tileAt(askers.totalDurationMs()));
        assertEquals(98, askers.tileAt(askers.totalDurationMs() + 60_000L));
    }

    @Test
    @DisplayName("sequence maps timeline steps onto the deduplicated tiles")
    void sequenceIsApplied() {
        BakedEmote alarma = manifest.get("ALARMA").orElseThrow();
        assertEquals(6, alarma.frameCount());
        assertEquals(3, alarma.tileCount());
        assertEquals(0, alarma.totalDurationMs(), "zero iterations means it never stops");
        assertEquals(0, alarma.tileAt(0L));
        assertEquals(0, alarma.tileAt(50L));
        assertEquals(1, alarma.tileAt(100L));
        assertEquals(2, alarma.tileAt(200L));
        assertEquals(2, alarma.tileAt(250L));
        assertEquals(0, alarma.tileAt(300L), "and wraps");
        assertEquals(1, alarma.tileAt(10L * 300L + 100L));
    }

    @Test
    @DisplayName("tier gating is opt-in, and off means every emote for everyone")
    void tierGating() {
        assertEquals(Set.of("Askers", "Abathur", "ALARMA", "TierFive"), manifest.prefixes());
        assertEquals(Set.of("Askers", "Abathur", "ALARMA"), manifest.prefixesFor(0));
        assertEquals(Set.of("Askers", "Abathur", "ALARMA", "TierFive"), manifest.prefixesFor(5));
        assertEquals(manifest.prefixes(), manifest.prefixesFor(Integer.MAX_VALUE));
    }

    @Test
    @DisplayName("an unreadable version is rejected so the caller can keep its cached copy")
    void versionIsChecked() {
        assertThrows(IllegalArgumentException.class,
                () -> BakeManifest.parse("""
                        {"version": 99, "emotes": {}}""", BASE));
        assertThrows(IllegalArgumentException.class,
                () -> BakeManifest.parse("""
                        {"emotes": {}}""", BASE));
    }

    @Test
    void emptyManifest() {
        assertTrue(BakeManifest.empty().isEmpty());
        assertEquals(0, BakeManifest.empty().size());
        assertTrue(BakeManifest.empty().get("Askers").isEmpty());
    }

    @Test
    @DisplayName("flairs ride along with the bake, with their icons resolved against it")
    void flairsAreCarried() {
        FlairCatalogue flairs = manifest.flairs();
        assertEquals(3, flairs.all().size());
        assertEquals("https://mcdgg.net/emotes/2026-08-08/flair-flair33.efda26884d0b.png",
                flairs.byName("flair33").orElseThrow().iconUrl());
        assertEquals(List.of("flair33", "flair5"),
                flairs.icons(List.of("moderator", "flair5", "flair33")).stream().map(Flair::name).toList());
    }

    @Test
    @DisplayName("flair5 publishes color \"\", which chat-gui reads as no colour at all")
    void emptyColourIsNoColour() {
        Flair contributor = manifest.flairs().byName("flair5").orElseThrow();
        assertFalse(contributor.hasColor());
        assertEquals(0x111111, contributor.colorRgb(0x111111));
        // So a user with only that flair keeps a plain name, exactly as on the site.
        assertTrue(manifest.flairs().usernameColorFlair(List.of("flair5")).isEmpty());
        assertEquals("moderator",
                manifest.flairs().usernameColorFlair(List.of("flair5", "moderator"))
                        .orElseThrow().name());
    }

    @Test
    void manifestWithoutFlairsIsFine() {
        BakeManifest noFlairs = BakeManifest.parse("""
                {"version": 1, "emotes": {}}""", BASE);
        assertTrue(noFlairs.flairs().isEmpty());
    }

    @Test
    void metadataIsCarriedThrough() {
        assertEquals(4, manifest.size());
        assertEquals("2026-08-08T12:00:00Z", manifest.generatedAt());
        assertEquals("abc123", manifest.emoteCssHash());
        assertEquals(1, manifest.version());
    }
}
