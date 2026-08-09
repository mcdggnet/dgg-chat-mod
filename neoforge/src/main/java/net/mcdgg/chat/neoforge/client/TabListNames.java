package net.mcdgg.chat.neoforge.client;

import net.mcdgg.chat.api.DggChatIdentity;
import net.mcdgg.chat.core.Flair;
import net.mcdgg.chat.core.FlairCatalogue;
import net.mcdgg.chat.neoforge.ClientIdentities;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.List;
import java.util.Optional;

/**
 * Tab list entries, styled to match chat.
 *
 * <p>Rebuilt from the identity rather than rewritten from the existing component: a tab
 * entry is just a name, with none of the surrounding text a chat line has, so there is
 * nothing to search for and matching would only risk hitting a substring of it.
 */
public final class TabListNames {

    private TabListNames() {}

    /**
     * @return the decorated name, or null to leave the vanilla one alone, which is the
     *         answer for any player with no DGG identity
     */
    public static Component decorate(PlayerInfo info, Component vanilla) {
        if (info == null) {
            return null;
        }
        Optional<DggChatIdentity> identity = ClientIdentities.get(info.getProfile().getId());
        if (identity.isEmpty()) {
            return null;
        }
        FlairCatalogue flairs = DggAssets.flairs();
        if (flairs.isEmpty()) {
            return null;
        }

        List<String> features = identity.get().features();
        Optional<Flair> colour = flairs.usernameColorFlair(features);
        List<Flair> icons = DggFont.isActive() ? flairs.icons(features) : List.of();
        if (colour.isEmpty() && icons.isEmpty()) {
            return null;
        }

        String nick = identity.get().dggNick();
        // The server already renames players to their DGG nick, so the vanilla entry is
        // usually right already. Falling back to it keeps this correct on a server that
        // does not.
        String name = nick.isEmpty() ? vanilla.getString() : nick;

        MutableComponent out = Component.empty();
        for (Flair flair : icons) {
            String glyph = DggFont.flairCharacter(flair.name());
            if (glyph != null) {
                out.append(Component.literal(glyph).setStyle(MessageRewriter.EMOTE_STYLE));
            }
        }
        MutableComponent named = Component.literal(name);
        colour.map(f -> f.colorRgb(0xFFFFFF))
                .ifPresent(rgb -> named.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb))));
        return out.append(named);
    }
}
