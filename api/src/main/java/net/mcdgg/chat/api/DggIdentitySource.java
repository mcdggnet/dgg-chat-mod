package net.mcdgg.chat.api;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Supplies Destiny.gg identities for online players.
 *
 * <p>This mod does not resolve identities itself. {@code dggauth} already does that at
 * login, so it implements this interface and declares the implementation in
 * {@code META-INF/services/net.mcdgg.chat.api.DggIdentitySource}. The server half
 * discovers it with {@link java.util.ServiceLoader} at startup.
 *
 * <p>The coupling runs one way on purpose. This mod has no compile-time knowledge of
 * {@code dggauth}; if nothing implements this interface the server half stays dormant
 * and clients render plain names. On the other side {@code dggauth} depends on this
 * artifact as {@code compileOnly}, so its implementing class is only ever loaded when
 * this mod's {@code ServiceLoader} goes looking for it. With this mod absent, nothing
 * references the missing types and nothing breaks.
 *
 * <p>Implementations are called from the server thread on player join and must not
 * block. Resolve asynchronously elsewhere and answer from a cache here; returning
 * {@link Optional#empty()} for an identity that is not ready yet is correct and
 * expected, since the relay re-asks when the source signals a change.
 */
public interface DggIdentitySource {

    /**
     * The identity for a connected player, or empty when unlinked, unresolved, or not
     * yet cached. Never blocks, and never throws for the ordinary "no identity" case.
     */
    Optional<DggChatIdentity> identityFor(UUID minecraftUuid);

    /**
     * Tie-break when more than one source is installed; highest wins. The default suits
     * any single source, which is the expected deployment.
     */
    default int priority() {
        return 0;
    }

    /**
     * Registers the relay's change callback: the "signals a change" half of the
     * {@link #identityFor} contract. Fire it with a player's UUID whenever their
     * identity changes after the initial ask — a lookup that was still in flight
     * resolving, or a player linking mid-session — and the relay re-asks
     * {@code identityFor} for that player and rebroadcasts the answer.
     *
     * <p>Called once per source when the server half starts, before any lookups. Fire
     * the callback from the server thread; it is cheap (the relay schedules its own
     * re-query, it does not resolve inline) and safe to fire spuriously or for a
     * player who has since disconnected.
     *
     * <p>The default is a no-op so existing sources stay source- and binary-compatible:
     * a source that never signals simply leaves the relay to its own retry schedule.
     */
    default void addChangeListener(Consumer<UUID> listener) {
    }
}
