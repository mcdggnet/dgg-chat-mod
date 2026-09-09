package net.mcdgg.chat.fabric;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.mcdgg.chat.api.DggChatIdentity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server half: work out who a player is in DGG terms, and tell everyone who can
 * hear it. Dormant unless some other mod implements
 * {@link net.mcdgg.chat.api.DggIdentitySource}; with nothing installed no lookups run,
 * no packets are sent, and every name renders plain.
 */
final class ServerIdentities {

    private static final Logger LOGGER = LoggerFactory.getLogger(DggChatFabric.MOD_ID);

    /** Retry schedule after an empty answer: two seconds, ten, thirty, then it is really
     * not linked. A source may still be resolving a player at the moment they join. */
    private static final int[] RETRY_TICKS = {40, 200, 600};

    private static final Map<UUID, DggChatIdentity> KNOWN = new ConcurrentHashMap<>();
    /** Server thread only. */
    private static final List<Retry> RETRIES = new ArrayList<>();
    private static long tick;
    private static volatile MinecraftServer currentServer;

    private record Retry(UUID player, int attempt, long dueTick) {}

    private ServerIdentities() {}

    static void onServerStarted(MinecraftServer server) {
        currentServer = server;
    }

    static void onServerStopping() {
        currentServer = null;
        KNOWN.clear();
        RETRIES.clear();
    }

    static void onPlayerJoin(ServerPlayer player, MinecraftServer server) {
        // Catch the joiner up on everyone already here, before looking them up in turn.
        if (!KNOWN.isEmpty()) {
            send(player, IdentityPayload.of(KNOWN.values()));
        }
        if (IdentitySources.count() > 0) {
            resolve(server, player.getUUID(), 0);
        }
    }

    static void onPlayerLeave(UUID uuid) {
        KNOWN.remove(uuid);
        RETRIES.removeIf(retry -> retry.player().equals(uuid));
    }

    /** A source signalled a change after the initial ask; re-query and rebroadcast now. */
    static void onIdentityChanged(UUID uuid) {
        MinecraftServer server = currentServer;
        if (server != null) {
            resolve(server, uuid, RETRY_TICKS.length);
        }
    }

    static void onServerTick(MinecraftServer server) {
        tick++;
        if (RETRIES.isEmpty()) {
            return;
        }
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

    /** A lookup may go to the network, so it never happens on the server thread. */
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
        if (server.getPlayerList().getPlayer(uuid) == null) {
            KNOWN.remove(uuid);
            RETRIES.removeIf(retry -> retry.player().equals(uuid));
            return;
        }
        if (identity != null && identity.isPresent()) {
            KNOWN.put(uuid, identity.get());
            IdentityPayload payload = IdentityPayload.of(identity.get());
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                send(player, payload);
            }
            return;
        }
        if (attempt < RETRY_TICKS.length) {
            RETRIES.add(new Retry(uuid, attempt + 1, tick + RETRY_TICKS[attempt]));
        }
    }

    /** Ask before sending: a client without the mod never negotiated the channel. */
    private static void send(ServerPlayer player, IdentityPayload payload) {
        if (ServerPlayNetworking.canSend(player, IdentityPayload.TYPE)) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}
