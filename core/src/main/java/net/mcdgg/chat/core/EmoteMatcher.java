package net.mcdgg.chat.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * chat-gui's emote matcher.
 *
 * <p>The site builds one regex over every prefix the sender may use:
 *
 * <pre>{@code new RegExp(`(^|\\s)(${prefixes.join('|')})(?=$|\\s)`, 'gm')}</pre>
 *
 * <p>Which gives, precisely:
 *
 * <ul>
 *   <li>whitespace-delimited only, so {@code PEPE} inside {@code xPEPEy} is plain text;
 *   <li>case sensitive, so {@code pepe} is not {@code PEPE};
 *   <li>longer names win over shorter prefixes of themselves regardless of list order,
 *       because the trailing lookahead forces a backtrack when {@code PEPE} is not followed
 *       by whitespace and {@code PEPELAUGH} is;
 *   <li>adjacent emotes both match, because the leading whitespace is captured by a group
 *       rather than consumed by the match.
 * </ul>
 *
 * <p>Two deliberate differences from the JavaScript. Prefixes are quoted before they go
 * into the alternation, so an emote name containing a regex metacharacter would match
 * literally here instead of corrupting the whole pattern as it would on the site. And
 * {@code \s} is compiled with {@link Pattern#UNICODE_CHARACTER_CLASS}, because Java's
 * default {@code \s} is ASCII-only while JavaScript's is not.
 */
public final class EmoteMatcher {

    private static final EmoteMatcher NONE = new EmoteMatcher(null);

    /** Null when there are no prefixes: an empty alternation matches the empty string. */
    private final Pattern pattern;

    private EmoteMatcher(Pattern pattern) {
        this.pattern = pattern;
    }

    /** A matcher that never matches, for before the emote manifest has loaded. */
    public static EmoteMatcher none() {
        return NONE;
    }

    public static EmoteMatcher of(Collection<String> prefixes) {
        if (prefixes == null || prefixes.isEmpty()) {
            return NONE;
        }
        StringBuilder alternation = new StringBuilder(prefixes.size() * 12);
        for (String prefix : prefixes) {
            if (prefix == null || prefix.isEmpty()) {
                continue;
            }
            if (alternation.length() > 0) {
                alternation.append('|');
            }
            alternation.append(Pattern.quote(prefix));
        }
        if (alternation.length() == 0) {
            return NONE;
        }
        Pattern pattern = Pattern.compile(
                "(^|\\s)(" + alternation + ")(?=$|\\s)",
                Pattern.MULTILINE | Pattern.UNICODE_CHARACTER_CLASS);
        return new EmoteMatcher(pattern);
    }

    /** Cheap enough to run on every incoming message before allocating anything. */
    public boolean containsEmote(String text) {
        return pattern != null && text != null && !text.isEmpty() && pattern.matcher(text).find();
    }

    /**
     * Splits {@code text} into literal runs and emote references. A message with no emote
     * comes back as a single {@link ChatToken.Text} holding the original string.
     */
    public List<ChatToken> tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        if (pattern == null) {
            return List.of(new ChatToken.Text(text));
        }

        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return List.of(new ChatToken.Text(text));
        }

        List<ChatToken> tokens = new ArrayList<>();
        int cursor = 0;
        do {
            // group(1) is the captured leading whitespace, which stays text so that two
            // adjacent emotes keep the space between them.
            int emoteStart = matcher.start(2);
            if (emoteStart > cursor) {
                tokens.add(new ChatToken.Text(text.substring(cursor, emoteStart)));
            }
            tokens.add(new ChatToken.EmoteRef(matcher.group(2)));
            cursor = matcher.end(2);
        } while (matcher.find());

        if (cursor < text.length()) {
            tokens.add(new ChatToken.Text(text.substring(cursor)));
        }
        return tokens;
    }
}
