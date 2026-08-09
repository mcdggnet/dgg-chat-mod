package net.mcdgg.chat.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmoteMatcherTest {

    private static final EmoteMatcher MATCHER =
            EmoteMatcher.of(List.of("PEPE", "PEPELAUGH", "glorp", "CuckCrab", "OOOO"));

    private static List<String> shape(String text) {
        return MATCHER.tokenize(text).stream()
                .map(token -> switch (token) {
                    case ChatToken.Text t -> "'" + t.text() + "'";
                    case ChatToken.EmoteRef e -> "<" + e.prefix() + ">";
                })
                .toList();
    }

    @Test
    void aBareEmote() {
        assertEquals(List.of("<PEPE>"), shape("PEPE"));
    }

    @Test
    void emoteInASentence() {
        assertEquals(List.of("'hello '", "<PEPE>", "' world'"), shape("hello PEPE world"));
    }

    @Test
    @DisplayName("matching is whitespace-delimited, so an emote inside a word is plain text")
    void notInsideAWord() {
        assertEquals(List.of("'xPEPEy'"), shape("xPEPEy"));
        assertEquals(List.of("'PEPEs'"), shape("PEPEs"));
        assertEquals(List.of("'aPEPE'"), shape("aPEPE"));
        assertEquals(List.of("'https://x.gg/glorp'"), shape("https://x.gg/glorp"));
    }

    @Test
    @DisplayName("the trailing lookahead makes the longer name win regardless of list order")
    void longerNameWins() {
        assertEquals(List.of("<PEPELAUGH>"), shape("PEPELAUGH"));
        assertEquals(List.of("'say '", "<PEPELAUGH>", "' now'"), shape("say PEPELAUGH now"));
        // Declared the other way round, to prove order really does not matter.
        EmoteMatcher reversed = EmoteMatcher.of(List.of("PEPELAUGH", "PEPE"));
        assertEquals(1, reversed.tokenize("PEPELAUGH").size());
        assertEquals(new ChatToken.EmoteRef("PEPELAUGH"), reversed.tokenize("PEPELAUGH").get(0));
    }

    @Test
    @DisplayName("adjacent emotes both match, because the leading space is captured not consumed")
    void adjacentEmotes() {
        assertEquals(List.of("<PEPE>", "' '", "<PEPE>", "' '", "<glorp>"), shape("PEPE PEPE glorp"));
    }

    @Test
    void caseSensitive() {
        assertEquals(List.of("'pepe'"), shape("pepe"));
        assertEquals(List.of("'cuckcrab'"), shape("cuckcrab"));
        assertEquals(List.of("<CuckCrab>"), shape("CuckCrab"));
    }

    @Test
    @DisplayName("tabs, newlines and non-breaking spaces all count as delimiters")
    void otherWhitespace() {
        assertEquals(List.of("<PEPE>", "'\t'", "<glorp>"), shape("PEPE\tglorp"));
        assertEquals(List.of("<PEPE>", "'\n'", "<glorp>"), shape("PEPE\nglorp"));
        assertEquals(List.of("<PEPE>", "'\u00a0'", "<glorp>"), shape("PEPE\u00a0glorp"));
    }

    @Test
    void textWithNoEmoteComesBackWhole() {
        assertEquals(List.of("'just some chat'"), shape("just some chat"));
        assertFalse(MATCHER.containsEmote("just some chat"));
        assertTrue(MATCHER.containsEmote("some PEPE chat"));
    }

    @Test
    void emptyInput() {
        assertEquals(List.of(), MATCHER.tokenize(""));
        assertEquals(List.of(), MATCHER.tokenize(null));
        assertFalse(MATCHER.containsEmote(""));
        assertFalse(MATCHER.containsEmote(null));
    }

    @Test
    @DisplayName("with no emotes loaded nothing is ever replaced")
    void emptyCatalogue() {
        EmoteMatcher none = EmoteMatcher.of(List.of());
        assertEquals(List.of(new ChatToken.Text("PEPE")), none.tokenize("PEPE"));
        assertFalse(none.containsEmote("PEPE"));
        assertFalse(EmoteMatcher.none().containsEmote("anything"));
    }

    @Test
    @DisplayName("a prefix containing regex syntax matches literally instead of corrupting the pattern")
    void metacharactersAreQuoted() {
        EmoteMatcher awkward = EmoteMatcher.of(List.of("o_O", "C:\\", "a.b"));
        assertEquals(new ChatToken.EmoteRef("a.b"), awkward.tokenize("a.b").get(0));
        assertEquals(List.of(new ChatToken.Text("axb")), awkward.tokenize("axb"));
        assertEquals(new ChatToken.EmoteRef("C:\\"), awkward.tokenize("C:\\").get(0));
    }
}
