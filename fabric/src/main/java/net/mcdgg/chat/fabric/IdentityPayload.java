package net.mcdgg.chat.fabric;

import io.netty.buffer.ByteBuf;
import net.mcdgg.chat.api.DggChatIdentity;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.UUID;

/**
 * DGG identities, server to client. Feature names rather than resolved appearance:
 * the client computes colour, icons and ordering from destiny.gg's own flairs.json,
 * so sending colours would fork that logic and go stale. Wire-identical to the NeoForge
 * mod's payload, channel {@code dggchat:identity}.
 */
public record IdentityPayload(List<IdentityPayload.Entry> entries) implements CustomPacketPayload {

    private static final int MAX_ENTRIES = 1024;
    private static final int MAX_FEATURES = 128;

    public static final CustomPacketPayload.Type<IdentityPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(DggChatFabric.MOD_ID, "identity"));

    public static final StreamCodec<ByteBuf, IdentityPayload> STREAM_CODEC =
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

    public record Entry(UUID minecraftUuid, String dggNick, List<String> features, int subTier) {
        static final StreamCodec<ByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Entry::minecraftUuid,
                ByteBufCodecs.STRING_UTF8, Entry::dggNick,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(MAX_FEATURES)), Entry::features,
                ByteBufCodecs.VAR_INT, Entry::subTier,
                Entry::new);

        static Entry of(DggChatIdentity identity) {
            return new Entry(identity.minecraftUuid(), identity.dggNick(), identity.features(), identity.subTier());
        }

        DggChatIdentity toIdentity() {
            return new DggChatIdentity(minecraftUuid, dggNick, features, Math.max(0, subTier));
        }
    }
}
