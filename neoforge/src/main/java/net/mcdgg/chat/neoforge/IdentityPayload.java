package net.mcdgg.chat.neoforge;

import io.netty.buffer.ByteBuf;
import net.mcdgg.chat.api.DggChatIdentity;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

/**
 * DGG identities, server to client.
 *
 * <p>Carries feature names rather than resolved appearance. The client already has
 * destiny.gg's {@code flairs.json} and computes colour, icons and ordering from those names
 * exactly as the site does, so sending colours would fork that logic and go stale. This is
 * {@link DggChatIdentity} on the wire, and nothing more.
 *
 * <p>A list rather than a single entry, because a player joining a busy server needs
 * everyone else's identity at once and one packet per player is a poor way to say that.
 */
record IdentityPayload(List<IdentityPayload.Entry> entries) implements CustomPacketPayload {

    /** Generous next to any real player count, and a bound is required by the codec. */
    private static final int MAX_ENTRIES = 1024;
    /** destiny.gg publishes 46 flairs; this only has to stop a malformed packet allocating. */
    private static final int MAX_FEATURES = 128;

    static final CustomPacketPayload.Type<IdentityPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(DggChatMod.MOD_ID, "identity"));

    static final StreamCodec<ByteBuf, IdentityPayload> STREAM_CODEC =
            Entry.STREAM_CODEC
                    .apply(ByteBufCodecs.list(MAX_ENTRIES))
                    .map(IdentityPayload::new, IdentityPayload::entries);

    static IdentityPayload of(DggChatIdentity identity) {
        return new IdentityPayload(List.of(Entry.of(identity)));
    }

    static IdentityPayload of(Iterable<DggChatIdentity> identities) {
        List<Entry> entries = new java.util.ArrayList<>();
        for (DggChatIdentity identity : identities) {
            entries.add(Entry.of(identity));
        }
        return new IdentityPayload(List.copyOf(entries));
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    record Entry(UUID minecraftUuid, String dggNick, List<String> features, int subTier) {

        static final StreamCodec<ByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Entry::minecraftUuid,
                ByteBufCodecs.STRING_UTF8, Entry::dggNick,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(MAX_FEATURES)), Entry::features,
                ByteBufCodecs.VAR_INT, Entry::subTier,
                Entry::new);

        static Entry of(DggChatIdentity identity) {
            return new Entry(identity.minecraftUuid(), identity.dggNick(),
                    identity.features(), identity.subTier());
        }

        DggChatIdentity toIdentity() {
            return new DggChatIdentity(minecraftUuid, dggNick, features, Math.max(0, subTier));
        }
    }
}
