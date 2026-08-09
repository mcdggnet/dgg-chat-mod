package net.mcdgg.chat.neoforge.client;

import net.mcdgg.chat.core.ChatToken;
import net.mcdgg.chat.core.EmoteMatcher;
import net.mcdgg.chat.core.Flair;
import net.mcdgg.chat.core.Rainbow;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.List;

/**
 * Turns one chat component into what destiny.gg would have shown.
 *
 * <p>Kept apart from {@link ChatDecorator} and free of any {@code Minecraft.getInstance()}
 * lookup so it can be tested against real components without a game running. Everything it
 * needs is decided by the caller and handed in.
 *
 * <p>The rewrite is a single pass. Each run of literal text is examined once: the sender's
 * name is styled where it is found, and everything else is split on emote names. Handling
 * the name inside that same pass is what stops a player called {@code PEPE} from having
 * their username replaced with a picture of a frog.
 */
final class MessageRewriter {

    /**
     * Emote glyphs must inherit nothing from the text around them. Colour multiplies into
     * the glyph, so anything but white tints the picture; obfuscated would swap it for a
     * random glyph of the same width; bold would try to smear it sideways.
     */
    static final Style EMOTE_STYLE = Style.EMPTY
            .withFont(DggFont.FONT)
            .withColor(0xFFFFFF)
            .withBold(false)
            .withItalic(false)
            .withObfuscated(false)
            .withUnderlined(false)
            .withStrikethrough(false);

    /**
     * @param names  the text to look for, in order of preference: the sender's Minecraft
     *               name, then their DGG nickname if a server renders that instead
     * @param colour the flair that decides the username colour, or null for a plain name
     * @param icons  flair icons to draw before the name, already in the site's order
     */
    record SenderStyle(List<String> names, Flair colour, List<Flair> icons) {}

    /**
     * Vanilla's plain chat line, {@code "<%s> %s"} with the sender and the body as arguments.
     * Deliberately only this one: {@code /me}, whispers, team chat and announcements have
     * their own formats that carry meaning, and flattening those would lose it.
     */
    private static final String VANILLA_CHAT = "chat.type.text";

    private final EmoteMatcher matcher;
    private final SenderStyle sender;
    private final boolean nameColonFormat;
    private final long now;

    private boolean nameStyled;
    private boolean changed;

    MessageRewriter(EmoteMatcher matcher, SenderStyle sender, boolean nameColonFormat, long nowMs) {
        this.matcher = matcher == null ? EmoteMatcher.none() : matcher;
        this.sender = sender;
        this.nameColonFormat = nameColonFormat;
        this.now = nowMs;
    }

    /** @return the rewritten message, or the original instance when nothing changed */
    Component rewrite(Component message) {
        if (sender == null && !hasEmotes() && !nameColonFormat) {
            return message;
        }
        Component result = visit(message);
        return changed ? result : message;
    }

    private boolean hasEmotes() {
        return matcher != EmoteMatcher.none();
    }

    private MutableComponent visit(Component node) {
        ComponentContents contents = node.getContents();
        MutableComponent out;

        if (contents instanceof PlainTextContents plain) {
            out = rewriteText(plain.text());
        } else if (contents instanceof TranslatableContents translatable
                && nameColonFormat
                && VANILLA_CHAT.equals(translatable.getKey())
                && translatable.getArgs().length == 2) {
            out = asNameColon(translatable.getArgs());
        } else if (contents instanceof TranslatableContents translatable) {
            // The vanilla "<%s> %s" decoration puts the sender and the message body in
            // here, so a rewrite that skipped arguments would skip the whole line.
            Object[] args = translatable.getArgs();
            Object[] rewritten = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                // Vanilla's decoration passes Components, but a plain String is equally
                // legal in a translation argument and several server chat plugins send
                // them. Skipping those leaves the message body untouched.
                rewritten[i] = switch (args[i]) {
                    case Component child -> visit(child);
                    case String text -> rewriteText(text);
                    default -> args[i];
                };
            }
            out = MutableComponent.create(new TranslatableContents(
                    translatable.getKey(), translatable.getFallback(), rewritten));
        } else {
            out = MutableComponent.create(contents);
        }

        out.setStyle(node.getStyle());
        for (Component sibling : node.getSiblings()) {
            out.append(visit(sibling));
        }
        return out;
    }

    /**
     * Rebuilds vanilla's {@code <name> message} as {@code name: message}, the way destiny.gg
     * reads.
     *
     * <p>It has to happen here rather than on the server. The angle brackets come from the
     * chat type, whose format string lives in the client's own language file, so a server
     * cannot change it; the only server-side escape is to stop sending player chat and
     * broadcast a system message instead, and a system message carries no sender UUID — which
     * is precisely what flair and username colour are resolved from. Rewriting the component
     * here keeps the identity and changes the shape.
     *
     * <p>The name keeps whatever style it arrived with, which on a server running LuckPerms
     * can include bold from a prefix. That style stays on the name because it is a child:
     * siblings inherit from their parent, not from the sibling before them. Hoisting it onto
     * the wrapper instead is what makes the colon and the whole message body go bold too.
     */
    private MutableComponent asNameColon(Object[] args) {
        MutableComponent out = Component.empty();
        out.append(rewriteArgument(args[0]));
        out.append(Component.literal(": "));
        out.append(rewriteArgument(args[1]));
        changed = true;
        return out;
    }

    private Component rewriteArgument(Object argument) {
        return switch (argument) {
            case Component child -> visit(child);
            case String text -> rewriteText(text);
            default -> Component.literal(String.valueOf(argument));
        };
    }

    private MutableComponent rewriteText(String text) {
        if (text.isEmpty()) {
            return MutableComponent.create(PlainTextContents.EMPTY);
        }
        if (!nameStyled && sender != null) {
            for (String name : sender.names()) {
                int at = wholeWordIndexOf(text, name);
                if (at >= 0) {
                    nameStyled = true;
                    changed = true;
                    MutableComponent out = emotes(text.substring(0, at));
                    appendIcons(out);
                    out.append(styledName(text.substring(at, at + name.length())));
                    out.append(emotes(text.substring(at + name.length())));
                    return out;
                }
            }
        }
        return emotes(text);
    }

    private void appendIcons(MutableComponent out) {
        for (Flair flair : sender.icons()) {
            String glyph = DggFont.flairCharacter(flair.name());
            if (glyph != null) {
                out.append(Component.literal(glyph).setStyle(EMOTE_STYLE));
            }
        }
    }

    /**
     * The rainbow flairs are a gradient scrolling across the name, and Minecraft colours per
     * character, so each character samples that same ramp at its own position.
     *
     * <p>Sampled once, as the message arrives, rather than every frame. A chat line's wrapped
     * form is built on arrival and never rebuilt, so an animated gradient would mean
     * re-wrapping the whole chat buffer every frame on a pack that cannot spare it. The
     * gradient is there; it just does not scroll.
     */
    private MutableComponent styledName(String name) {
        if (sender.colour() == null) {
            return Component.literal(name);
        }
        if (!sender.colour().rainbowColor()) {
            return Component.literal(name).setStyle(
                    Style.EMPTY.withColor(TextColor.fromRgb(sender.colour().colorRgb(0xFFFFFF))));
        }
        MutableComponent out = Component.empty();
        int length = name.length();
        for (int i = 0; i < length; i++) {
            out.append(Component.literal(String.valueOf(name.charAt(i))).setStyle(
                    Style.EMPTY.withColor(TextColor.fromRgb(Rainbow.rgbForCharacter(i, length, now)))));
        }
        return out;
    }

    private MutableComponent emotes(String text) {
        if (text.isEmpty()) {
            return Component.empty();
        }
        if (!matcher.containsEmote(text)) {
            return Component.literal(text);
        }
        MutableComponent out = Component.empty();
        for (ChatToken token : matcher.tokenize(text)) {
            switch (token) {
                case ChatToken.Text plain -> out.append(Component.literal(plain.text()));
                case ChatToken.EmoteRef emote -> {
                    String glyph = DggFont.emoteCharacter(emote.prefix());
                    if (glyph == null) {
                        // Known to the matcher but with no glyph: leave the name readable.
                        out.append(Component.literal(emote.prefix()));
                    } else {
                        DggFont.restartAnimation(emote.prefix(), now);
                        out.append(Component.literal(glyph).setStyle(EMOTE_STYLE));
                        changed = true;
                    }
                }
            }
        }
        return out;
    }

    /**
     * Names match on word boundaries, so a player called {@code Bo} is not found inside
     * {@code Bob} and a name mentioned mid-sentence is not styled as a second username.
     */
    static int wholeWordIndexOf(String text, String name) {
        if (name == null || name.isEmpty()) {
            return -1;
        }
        int from = 0;
        while (true) {
            int at = text.indexOf(name, from);
            if (at < 0) {
                return -1;
            }
            int end = at + name.length();
            boolean startsClean = at == 0 || !isNameChar(text.charAt(at - 1));
            boolean endsClean = end == text.length() || !isNameChar(text.charAt(end));
            if (startsClean && endsClean) {
                return at;
            }
            from = at + 1;
        }
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
