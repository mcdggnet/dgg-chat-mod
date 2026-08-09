package net.mcdgg.chat.neoforge.client;

import net.mcdgg.chat.api.DggChatIdentity;
import net.mcdgg.chat.core.EmoteMatcher;
import net.mcdgg.chat.core.Flair;
import net.mcdgg.chat.core.FlairCatalogue;
import net.mcdgg.chat.neoforge.ClientIdentities;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Where an incoming chat line meets the rewriter.
 *
 * <p>{@link ClientChatReceivedEvent} carries the message after the chat type has decorated
 * it, so what arrives here is the whole line, sender name included, and what this hands
 * back goes straight into the chat window. One hook covers emotes, names and flair icons.
 *
 * <p>This class does the looking up — who sent it, what their flairs are, whether the font
 * is even usable — and {@link MessageRewriter} does the transforming.
 */
final class ChatDecorator {

    private ChatDecorator() {}

    static void onChatReceived(ClientChatReceivedEvent event) {
        try {
            EmoteMatcher matcher = DggFont.isActive() ? DggAssets.matcher() : EmoteMatcher.none();
            MessageRewriter rewriter =
                    new MessageRewriter(matcher, senderStyle(event.getSender()), Util.getMillis());

            Component original = event.getMessage();
            Component rewritten = rewriter.rewrite(original);
            if (rewritten != original) {
                event.setMessage(rewritten);
            }
        } catch (RuntimeException e) {
            // A chat line that fails to decorate must still be a chat line.
            DggChatClient.LOGGER.warn("failed to decorate a chat message", e);
        }
    }

    /** Null when this message has no DGG identity behind it, which is the common case. */
    private static MessageRewriter.SenderStyle senderStyle(UUID sender) {
        if (sender == null || Util.NIL_UUID.equals(sender)) {
            return null;
        }
        Optional<DggChatIdentity> identity = ClientIdentities.get(sender);
        if (identity.isEmpty()) {
            return null;
        }
        FlairCatalogue flairs = DggAssets.flairs();
        if (flairs.isEmpty()) {
            return null;
        }

        List<String> features = identity.get().features();
        Optional<Flair> colour = flairs.usernameColorFlair(features);
        // Icons are glyphs, so without a working font there are no icons, only a colour.
        List<Flair> icons = DggFont.isActive() ? flairs.icons(features) : List.of();
        if (colour.isEmpty() && icons.isEmpty()) {
            return null;
        }

        List<String> names = new ArrayList<>(2);
        PlayerInfo info = playerInfo(sender);
        if (info != null) {
            names.add(info.getProfile().getName());
        }
        String nick = identity.get().dggNick();
        if (!nick.isEmpty() && !names.contains(nick)) {
            names.add(nick);
        }
        return names.isEmpty() ? null : new MessageRewriter.SenderStyle(names, colour.orElse(null), icons);
    }

    private static PlayerInfo playerInfo(UUID uuid) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getConnection() == null ? null : minecraft.getConnection().getPlayerInfo(uuid);
    }
}
