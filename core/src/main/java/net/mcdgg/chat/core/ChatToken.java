package net.mcdgg.chat.core;

/** A run of chat text, split into the parts that stay text and the parts that become images. */
public sealed interface ChatToken {

    /** Literal text, including any whitespace that separated emotes. */
    record Text(String text) implements ChatToken {}

    /** An emote name that matched, carrying the prefix rather than the resolved emote. */
    record EmoteRef(String prefix) implements ChatToken {}
}
