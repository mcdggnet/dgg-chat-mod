package net.mcdgg.chat.neoforge.client;

import net.mcdgg.chat.core.ChatToken;
import net.mcdgg.chat.core.EmoteMatcher;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.StringUtil;

/**
 * Emotes in titles and subtitles.
 *
 * <p>The old server put an AngelThump on screen when Destiny went live by sending a
 * private-use glyph its resource pack mapped. This server has no pack; instead the
 * server sends the emote's plain name and this rewrite turns it into the glyph on
 * clients that have the mod - the same contract chat already uses. A client without
 * the mod reads the word "AngelThump", which degrades better than a missing-glyph box.
 *
 * <p>Deliberately conservative: only components whose entire visible text is emote
 * tokens and whitespace are rebuilt. A title like "AngelThump" or "OOOO dggL" becomes
 * glyphs; anything with real words keeps its exact component tree, styles and all,
 * because a rebuild from getString() would flatten them.
 */
public final class TitleEmotes {

    private TitleEmotes() {}

    public static Component rewrite(Component component) {
        if (component == null || !DggFont.isActive()) {
            return component;
        }
        EmoteMatcher matcher = DggAssets.matcher();
        String text = component.getString();
        if (text.isEmpty() || !matcher.containsEmote(text)) {
            return component;
        }

        MutableComponent out = Component.empty().setStyle(component.getStyle());
        for (ChatToken token : matcher.tokenize(text)) {
            switch (token) {
                case ChatToken.Text plain -> {
                    if (!StringUtil.isBlank(plain.text())) {
                        return component;   // real words present: leave the title alone
                    }
                    out.append(Component.literal(plain.text()));
                }
                case ChatToken.EmoteRef emote -> {
                    String glyph = DggFont.emoteCharacter(emote.prefix());
                    if (glyph == null) {
                        return component;   // known emote, no glyph baked: keep the text
                    }
                    DggFont.restartAnimation(emote.prefix(), net.minecraft.Util.getMillis());
                    out.append(Component.literal(glyph).setStyle(MessageRewriter.EMOTE_STYLE));
                }
            }
        }
        return out;
    }
}
