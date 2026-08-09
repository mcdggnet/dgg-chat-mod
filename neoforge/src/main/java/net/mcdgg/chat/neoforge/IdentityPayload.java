package net.mcdgg.chat.neoforge;

import net.mcdgg.chat.api.DggChatIdentity;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

/**
 * One player's DGG identity, server to client.
 *
 * <p>Carries feature names rather than resolved appearance. The client already has
 * destiny.gg's {@code flairs.json} and computes colour, icons and ordering from these
 * names exactly as the site does, so sending colours would fork that logic and go
 * stale. This is {@link DggChatIdentity} on the wire, and nothing more.
 */
record IdentityPayload(UUID minecraftUuid, String dggNick, List<String> features, int subTier)
        implements CustomPacketPayload {

    static final CustomPacketPayload.Type<IdentityPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(DggChatMod.MOD_ID, "identity"));

    static final StreamCodec<RegistryFriendlyByteBuf, IdentityPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, IdentityPayload::minecraftUuid,
                    ByteBufCodecs.STRING_UTF8, IdentityPayload::dggNick,
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), IdentityPayload::features,
                    ByteBufCodecs.VAR_INT, IdentityPayload::subTier,
                    IdentityPayload::new);

    static IdentityPayload of(DggChatIdentity identity) {
        return new IdentityPayload(identity.minecraftUuid(), identity.dggNick(),
                identity.features(), identity.subTier());
    }

    DggChatIdentity toIdentity() {
        return new DggChatIdentity(minecraftUuid, dggNick, features, subTier);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
