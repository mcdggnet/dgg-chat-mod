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
            MessageRewriter rewriter = new MessageRewriter(
                    matcher, senderStyle(resolveSender(event)),
                    DggAssets.nameColonFormat(), Util.getMillis());

            Component original = event.getMessage();
            Component rewritten = rewriter.rewrite(original);
            if (rewritten != original) {
                event.setMessage(rewritten);
            } else {
                reportMissedEmote(matcher, original);
            }
        } catch (RuntimeException e) {
            // A chat line that fails to decorate must still be a chat line.
            DggChatClient.LOGGER.warn("failed to decorate a chat message", e);
        }
    }

    /** Only ever true once: this is a diagnostic, not a running commentary. */
    private static boolean reportedMiss;

    /**
     * Says something when a message plainly contained an emote and came out unchanged.
     *
     * <p>Silent whenever the mod is working, because it only fires when the flattened text
     * matches an emote the mod knows about and the rewrite still produced nothing. That is
     * always a bug, and the two most likely causes look identical from the outside: the text
     * living somewhere the tree walk does not reach, or the server having already replaced
     * it. Printing the content type separates them.
     */
    private static void reportMissedEmote(EmoteMatcher matcher, Component message) {
        if (reportedMiss || matcher == EmoteMatcher.none()) {
            return;
        }
        String flattened = message.getString();
        if (!matcher.containsEmote(flattened)) {
            return;
        }
        reportedMiss = true;
        DggChatClient.LOGGER.warn(
                "chat contained an emote but nothing was substituted."
                        + " contents={} tree={} text={}",
                message.getContents().getClass().getName(),
                describe(message),
                flattened);
    }

    /** A compact sketch of the component tree, for the diagnostic above. */
    private static String describe(Component node) {
        StringBuilder out = new StringBuilder(node.getContents().getClass().getSimpleName());
        if (node.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t) {
            out.append('(').append(t.getKey()).append(") args=[");
            Object[] args = t.getArgs();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(args[i] == null ? "null" : args[i].getClass().getSimpleName());
            }
            out.append(']');
        }
        if (!node.getSiblings().isEmpty()) {
            out.append(" +[");
            for (int i = 0; i < node.getSiblings().size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(describe(node.getSiblings().get(i)));
            }
            out.append(']');
        }
        return out.toString();
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

    /**
     * The sender's UUID, falling back to reading the name out of the line.
     *
     * <p>{@code getSender()} is null whenever the line did not arrive as signed player
     * chat. That is not an edge case here: ATM10 ships No Chat Reports with
     * {@code convertToGameMessage} enabled, which deliberately turns player chat into a
     * game message to strip signatures, and a game message has no sender. Without this
     * fallback every message on the pack loses its identity, so names render plain and no
     * flair is ever drawn, while the rest of the rewrite still happens: the failure looks
     * like broken flair rather than a missing sender.
     *
     * <p>Matching on the leading name is safe because it only ever looks at the part
     * before the separator, and only accepts a name belonging to a player currently in
     * the tab list.
     */
    private static UUID resolveSender(ClientChatReceivedEvent event) {
        UUID sender = event.getSender();
        if (sender != null && !Util.NIL_UUID.equals(sender)) {
            return sender;
        }
        String name = leadingName(event.getMessage().getString());
        if (name == null) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return null;
        }
        for (PlayerInfo info : minecraft.getConnection().getOnlinePlayers()) {
            if (info.getProfile().getName().equals(name)) {
                return info.getProfile().getId();
            }
        }
        return null;
    }

    /**
     * The name from "&lt;Name&gt; message" or "Name: message", or null if the line is not
     * shaped like chat at all. Deliberately strict: a system line such as
     * "JourneyMap: Press [J]" yields "JourneyMap", which simply matches no player.
     */
    private static String leadingName(String text) {
        if (text.startsWith("<")) {
            int close = text.indexOf('>');
            return close > 1 ? text.substring(1, close) : null;
        }
        int colon = text.indexOf(':');
        // A name cannot contain a space, so anything before the colon that does is not one.
        if (colon <= 0 || text.lastIndexOf(' ', colon) >= 0) {
            return null;
        }
        return text.substring(0, colon);
    }

    private static PlayerInfo playerInfo(UUID uuid) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getConnection() == null ? null : minecraft.getConnection().getPlayerInfo(uuid);
    }
}
