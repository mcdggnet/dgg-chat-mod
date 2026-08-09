package net.mcdgg.chat.neoforge.client;

import net.mcdgg.chat.core.BakeManifest;
import net.mcdgg.chat.core.EmoteMatcher;
import net.mcdgg.chat.core.Flair;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat rewrite, against real {@link Component} trees.
 *
 * <p>Worth doing here rather than trusting a read-through: the vanilla chat decoration is a
 * translatable component whose sender and body are arguments rather than children, and a
 * transform that walked only siblings would silently do nothing to every player message on
 * the server while looking perfectly correct.
 */
class MessageRewriterTest {

    private static final String MANIFEST = """
            {
              "version": 1,
              "emotes": {
                "PEPE":      {"file":"PEPE.png","tileWidth":30,"tileHeight":30},
                "PEPELAUGH": {"file":"PEPELAUGH.png","tileWidth":30,"tileHeight":30},
                "Askers":    {"file":"Askers.png","tileWidth":53,"tileHeight":34,
                              "tileCount":99,"frameCount":99,"frameMs":35,"iterations":4}
              },
              "flairs": [
                {"name":"flair33","label":"Tier 5","hidden":false,"priority":3,
                 "color":"#eb79da","rainbowColor":true,"order":3,
                 "iconFile":"t5.png","iconWidth":18,"iconHeight":19},
                {"name":"flair12","label":"Contributor","hidden":false,"priority":3,
                 "color":"#E79015","rainbowColor":false,"order":3,
                 "iconFile":"c.png","iconWidth":16,"iconHeight":16},
                {"name":"moderator","label":"Moderator","hidden":true,"priority":127,
                 "color":"#DB4C1C","rainbowColor":false,"order":2147483647}
              ]
            }
            """;

    private static BakeManifest manifest;

    @BeforeAll
    static void installGlyphs() {
        manifest = BakeManifest.parse(MANIFEST, "https://example.invalid/manifest.json");
        DggFont.install(manifest);
    }

    private static EmoteMatcher matcher() {
        return EmoteMatcher.of(manifest.prefixes());
    }

    private static MessageRewriter.SenderStyle sender(String name, String... features) {
        List<String> list = List.of(features);
        Flair colour = manifest.flairs().usernameColorFlair(list).orElse(null);
        return new MessageRewriter.SenderStyle(List.of(name), colour, manifest.flairs().icons(list));
    }

    /** Flattened text, with each emote glyph shown as {@code <Prefix>} for readability. */
    private static String render(Component component) {
        StringBuilder out = new StringBuilder();
        component.visit((style, text) -> {
            if (DggFont.FONT.equals(style.getFont())) {
                out.append('<').append(nameOf(text)).append('>');
            } else {
                out.append(text);
            }
            return java.util.Optional.empty();
        }, Style.EMPTY);
        return out.toString();
    }

    private static String nameOf(String glyphs) {
        StringBuilder names = new StringBuilder();
        glyphs.codePoints().forEach(codepoint -> {
            String found = "?";
            for (var entry : DggFont.glyphs().emoteCodepoints().entrySet()) {
                if (entry.getValue() == codepoint) {
                    found = entry.getKey();
                }
            }
            for (var entry : DggFont.glyphs().flairCodepoints().entrySet()) {
                if (entry.getValue() == codepoint) {
                    found = "icon:" + entry.getKey();
                }
            }
            if (names.length() > 0) {
                names.append('>').append('<');
            }
            names.append(found);
        });
        return names.toString();
    }

    private static List<Integer> colours(Component component) {
        List<Integer> found = new ArrayList<>();
        component.visit((style, text) -> {
            for (int i = 0; i < text.length(); i++) {
                found.add(style.getColor() == null ? null : style.getColor().getValue());
            }
            return java.util.Optional.empty();
        }, Style.EMPTY);
        return found;
    }

    @Test
    void emotesBecomeGlyphs() {
        Component out = new MessageRewriter(matcher(), null, 0L)
                .rewrite(Component.literal("hello PEPE world"));
        assertEquals("hello <PEPE> world", render(out));
    }

    @Test
    @DisplayName("a message with nothing to do comes back as the very same object")
    void unchangedMessagesAreNotCopied() {
        Component original = Component.literal("just talking");
        assertSame(original, new MessageRewriter(matcher(), null, 0L).rewrite(original));
        assertSame(original, new MessageRewriter(EmoteMatcher.none(), null, 0L).rewrite(original));
    }

    @Test
    @DisplayName("the vanilla <%s> %s decoration carries its text in arguments, not siblings")
    void translatableArgumentsAreRewritten() {
        Component decorated = Component.translatable("chat.type.text",
                Component.literal("Steve"), Component.literal("PEPE PEPELAUGH"));
        Component out = new MessageRewriter(matcher(), null, 0L).rewrite(decorated);
        assertEquals("<PEPE> <PEPELAUGH>", render(argument(out, 1)));
    }

    @Test
    void siblingsAreRewrittenToo() {
        Component message = Component.literal("a PEPE ")
                .append(Component.literal("and Askers here"));
        assertEquals("a <PEPE> and <Askers> here", render(new MessageRewriter(matcher(), null, 0L).rewrite(message)));
    }

    @Test
    @DisplayName("emote glyphs never inherit colour, obfuscation or bold from the text around them")
    void emoteStyleIsIsolated() {
        Component message = Component.literal("look PEPE")
                .setStyle(Style.EMPTY.withColor(0xFF0000).withObfuscated(true).withBold(true));
        Component out = new MessageRewriter(matcher(), null, 0L).rewrite(message);

        out.visit((style, text) -> {
            if (DggFont.FONT.equals(style.getFont())) {
                assertEquals(0xFFFFFF, style.getColor().getValue(), "emote must not be tinted");
                assertTrue(!style.isObfuscated(), "an obfuscated emote would be a random glyph");
                assertTrue(!style.isBold(), "a bold emote would be smeared sideways");
            }
            return java.util.Optional.empty();
        }, Style.EMPTY);
    }

    @Test
    void nameTakesTheWinningFlairColour() {
        Component decorated = Component.translatable("chat.type.text",
                Component.literal("Steve"), Component.literal("hi"));
        Component out = new MessageRewriter(matcher(), sender("Steve", "flair12"), 0L).rewrite(decorated);

        assertTrue(colours(argument(out, 0)).contains(0xE79015),
                "the name should be painted with flair12's colour");
    }

    @Test
    @DisplayName("a rainbow flair colours the name a character at a time")
    void rainbowNames() {
        Component decorated = Component.translatable("chat.type.text",
                Component.literal("Steve"), Component.literal("hi"));
        Component out = new MessageRewriter(matcher(), sender("Steve", "flair33", "flair12"), 0L)
                .rewrite(decorated);

        // The component also holds the two flair icons, which come first.
        List<Integer> all = colours(argument(out, 0));
        List<Integer> name = all.subList(all.size() - 5, all.size());
        assertEquals(5, name.stream().distinct().count(), "one distinct colour per character");
    }

    @Test
    @DisplayName("flair icons are drawn before the name, hidden flairs excluded")
    void flairIconsPrecedeTheName() {
        Component decorated = Component.translatable("chat.type.text",
                Component.literal("Steve"), Component.literal("hi"));
        Component out = new MessageRewriter(matcher(), sender("Steve", "flair33", "flair12", "moderator"), 0L)
                .rewrite(decorated);

        assertEquals("<icon:flair33><icon:flair12>Steve", render(argument(out, 0)));
    }

    @Test
    @DisplayName("a player called PEPE keeps their name; only the message body gets emotes")
    void aNameThatIsAlsoAnEmote() {
        Component decorated = Component.translatable("chat.type.text",
                Component.literal("PEPE"), Component.literal("look PEPE"));
        Component out = new MessageRewriter(matcher(), sender("PEPE", "flair12"), 0L).rewrite(decorated);

        assertEquals("<icon:flair12>PEPE", render(argument(out, 0)),
                "the username stays text, with its flair icon in front");
        assertEquals("look <PEPE>", render(argument(out, 1)));
    }

    @Test
    @DisplayName("only the first occurrence of the name is styled, so quoting someone is not")
    void nameIsStyledOnce() {
        Component decorated = Component.translatable("chat.type.text",
                Component.literal("Steve"), Component.literal("Steve said Steve"));
        Component out = new MessageRewriter(matcher(), sender("Steve", "flair12"), 0L).rewrite(decorated);

        assertTrue(colours(argument(out, 0)).contains(0xE79015));
        assertTrue(colours(argument(out, 1)).stream().noneMatch(c -> c != null && c == 0xE79015));
    }

    @Test
    void nameMatchingRespectsWordBoundaries() {
        assertEquals(0, MessageRewriter.wholeWordIndexOf("Bob: hi", "Bob"));
        assertEquals(-1, MessageRewriter.wholeWordIndexOf("Bob: hi", "Bo"));
        assertEquals(-1, MessageRewriter.wholeWordIndexOf("Bobby", "Bob"));
        assertEquals(5, MessageRewriter.wholeWordIndexOf("said Bob loudly", "Bob"));
        assertEquals(-1, MessageRewriter.wholeWordIndexOf("nothing", ""));
    }

    @Test
    @DisplayName("with no glyphs installed the emote name stays readable text")
    void withoutGlyphsNothingIsLost() {
        DggFont.install(BakeManifest.empty());
        try {
            Component out = new MessageRewriter(matcher(), null, 0L)
                    .rewrite(Component.literal("hello PEPE"));
            assertEquals("hello PEPE", render(out));
        } finally {
            DggFont.install(manifest);
        }
    }

    @Test
    void styleOnTheOriginalSurvivesTheRewrite() {
        Component message = Component.literal("PEPE ok")
                .setStyle(Style.EMPTY.withItalic(true));
        Component out = new MessageRewriter(matcher(), null, 0L).rewrite(message);
        assertTrue(out.getStyle().isItalic(), "the surrounding style must be preserved");
        assertNotEquals(message, out);
    }

    /** Reaches into a translatable component's nth argument. */
    private static Component argument(Component component, int index) {
        var contents = (net.minecraft.network.chat.contents.TranslatableContents) component.getContents();
        return (Component) contents.getArgs()[index];
    }

    /** Guards the assumption that MutableComponent.create keeps siblings out of the way. */
    @Test
    void rewriterProducesMutableComponents() {
        Component out = new MessageRewriter(matcher(), null, 0L).rewrite(Component.literal("PEPE"));
        assertTrue(out instanceof MutableComponent);
    }
}
