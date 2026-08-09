package net.mcdgg.chat.neoforge;

import net.mcdgg.chat.api.DggChatIdentity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DGG identities the client has been told about, for as long as it stays connected.
 *
 * <p>Holds no client-only Minecraft types on purpose, so it is safe to reference from the
 * common payload handler without a {@code Dist} guard. When the mod is installed only
 * client-side, or the server has no identity source, this stays empty and every name
 * renders plain, which is the correct degraded state rather than a failure.
 */
public final class ClientIdentities {

    private static final Map<UUID, DggChatIdentity> BY_UUID = new ConcurrentHashMap<>();

    private ClientIdentities() {}

    static void accept(IdentityPayload payload) {
        for (IdentityPayload.Entry entry : payload.entries()) {
            BY_UUID.put(entry.minecraftUuid(), entry.toIdentity());
        }
    }

    public static Optional<DggChatIdentity> get(UUID minecraftUuid) {
        return Optional.ofNullable(BY_UUID.get(minecraftUuid));
    }

    public static boolean isEmpty() {
        return BY_UUID.isEmpty();
    }

    /** Cleared on disconnect: identities belong to a session, not to the game. */
    public static void clear() {
        BY_UUID.clear();
    }
}
