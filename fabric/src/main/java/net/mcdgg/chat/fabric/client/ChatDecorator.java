package net.mcdgg.chat.fabric.client;

import net.mcdgg.chat.api.DggChatIdentity;
import net.mcdgg.chat.core.EmoteMatcher;
import net.mcdgg.chat.core.Flair;
import net.mcdgg.chat.core.FlairCatalogue;
import net.mcdgg.chat.fabric.ClientIdentities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Where an incoming chat line meets the rewriter.
 *
 * <p>Hooked at {@code ChatComponent.addMessage}, which every line passes through on its way
 * into the chat window: signed player chat, system messages, and the game messages No Chat
 * Reports turns player chat into. What arrives is the whole decorated line, sender name
 * included, and what this hands back is what gets wrapped and drawn. One hook covers
 * emotes, names and flair icons.
 *
 * <p>This class does the looking up (who sent it, what their flairs are, whether the font
 * is even usable) and {@link MessageRewriter} does the transforming.
 */
public final class ChatDecorator {

    private ChatDecorator() {}

    /** @return the line to display; the same instance when nothing needed doing */
    public static Component rewrite(Component original) {
        try {
            EmoteMatcher matcher = DggFont.isActive() ? DggAssets.matcher() : EmoteMatcher.none();
            MessageRewriter rewriter = new MessageRewriter(
                    matcher, senderStyle(resolveSender(original)),
                    DggAssets.nameColonFormat(), Util.getMillis());

            Component rewritten = rewriter.rewrite(original);
            if (rewritten == original) {
                reportMissedEmote(matcher, original);
            }
            return rewritten;
        } catch (RuntimeException e) {
            // A chat line that fails to decorate must still be a chat line.
            DggChatFabricClient.LOGGER.warn("failed to decorate a chat message", e);
            return original;
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
        DggChatFabricClient.LOGGER.warn(
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
            names.add(info.getProfile().name());
        }
        String nick = identity.get().dggNick();
        if (!nick.isEmpty() && !names.contains(nick)) {
            names.add(nick);
        }
        return names.isEmpty() ? null : new MessageRewriter.SenderStyle(names, colour.orElse(null), icons);
    }

    /**
     * The sender, read out of the line itself.
     *
     * <p>{@code ChatComponent.addMessage} carries no sender; the NeoForge event did, but
     * even there it was null for the common case, because No Chat Reports turns player
     * chat into a game message to strip signatures and a game message has no sender.
     * Reading the leading name is what worked on both, so it is the only path here.
     *
     * <p>Matching on the leading name is safe because it only ever looks at the part
     * before the separator, and only accepts a name belonging to a player currently in
     * the tab list.
     */
    private static UUID resolveSender(Component message) {
        String name = leadingName(message.getString());
        if (name == null) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return null;
        }
        for (PlayerInfo info : minecraft.getConnection().getOnlinePlayers()) {
            if (info.getProfile().name().equals(name)) {
                return info.getProfile().id();
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
