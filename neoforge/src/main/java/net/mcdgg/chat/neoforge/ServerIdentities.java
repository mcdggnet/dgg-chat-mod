package net.mcdgg.chat.neoforge;

import com.mojang.logging.LogUtils;
import net.mcdgg.chat.api.DggChatIdentity;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server half: work out who a player is in DGG terms, and tell everyone who can hear it.
 *
 * <p>Dormant unless some other mod implements {@link net.mcdgg.chat.api.DggIdentitySource}.
 * With nothing installed, no lookups run, no packets are sent, and every name renders plain,
 * which is the correct degraded state rather than a failure.
 */
final class ServerIdentities {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * When to try again, in ticks after the previous attempt came back empty.
     *
     * <p>A provider may still be resolving a player at the moment they join: dggauth looks
     * identities up over the network at login and the answer can land slightly late. Two
     * seconds, ten, then thirty, and then it is genuinely not linked and asking again is
     * just noise.
     */
    private static final int[] RETRY_TICKS = {40, 200, 600};

    /** Online players only: an entry is dropped on logout so a rejoin re-resolves. */
    private static final Map<UUID, DggChatIdentity> KNOWN = new ConcurrentHashMap<>();

    /** Server thread only. */
    private static final List<Retry> RETRIES = new ArrayList<>();
    private static long tick;

    private record Retry(UUID player, int attempt, long dueTick) {}

    private ServerIdentities() {}

    static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // Catch the joiner up on everyone already here, before looking them up in turn.
        if (!KNOWN.isEmpty()) {
            send(player, IdentityPayload.of(KNOWN.values()));
        }
        if (IdentitySources.count() > 0) {
            resolve(player.server, player.getUUID(), 0);
        }
    }

    static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.getEntity().getUUID();
        KNOWN.remove(uuid);
        RETRIES.removeIf(retry -> retry.player().equals(uuid));
    }

    static void onServerTick(ServerTickEvent.Post event) {
        tick++;
        if (RETRIES.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        // Copy before iterating: resolve() can add a further retry for the same player.
        List<Retry> due = new ArrayList<>();
        RETRIES.removeIf(retry -> {
            if (retry.dueTick() > tick) {
                return false;
            }
            due.add(retry);
            return true;
        });
        for (Retry retry : due) {
            if (server.getPlayerList().getPlayer(retry.player()) != null) {
                resolve(server, retry.player(), retry.attempt());
            }
        }
    }

    /**
     * A lookup may go to the network, so it never happens on the server thread. The result
     * is handed back to the server thread before anything touches shared state or sends.
     */
    private static void resolve(MinecraftServer server, UUID uuid, int attempt) {
        CompletableFuture
                .supplyAsync(() -> IdentitySources.identityFor(uuid), Util.backgroundExecutor())
                .whenComplete((identity, error) ->
                        server.execute(() -> accept(server, uuid, attempt, identity, error)));
    }

    private static void accept(MinecraftServer server, UUID uuid, int attempt,
                               Optional<DggChatIdentity> identity, Throwable error) {
        if (error != null) {
            LOGGER.warn("identity lookup failed for {}; that player renders plain", uuid, error);
            return;
        }
        if (identity != null && identity.isPresent()) {
            KNOWN.put(uuid, identity.get());
            broadcast(server, IdentityPayload.of(identity.get()));
            return;
        }
        if (attempt < RETRY_TICKS.length) {
            RETRIES.add(new Retry(uuid, attempt + 1, tick + RETRY_TICKS[attempt]));
        } else {
            LOGGER.debug("no identity source has an answer for {}", uuid);
        }
    }

    private static void broadcast(MinecraftServer server, IdentityPayload payload) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            send(player, payload);
        }
    }

    /**
     * The rule that keeps this mod from breaking a server for everyone else, made explicit.
     *
     * <p>Registering the payload as optional stops the handshake rejecting a client that
     * lacks the mod, but it does not stop the server from then sending on a channel that
     * client never negotiated. Asking first is what makes a vanilla player's session
     * genuinely untouched.
     */
    private static void send(ServerPlayer player, IdentityPayload payload) {
        if (player.connection.hasChannel(IdentityPayload.TYPE)) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }
}
