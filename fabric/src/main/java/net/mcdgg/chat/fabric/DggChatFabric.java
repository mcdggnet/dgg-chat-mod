package net.mcdgg.chat.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the half that runs on both sides: the payload type, and the server
 * relay that tells clients who everyone is in DGG terms. The client half is
 * {@code net.mcdgg.chat.fabric.client}, behind its own entrypoint, so a dedicated
 * server never loads a class that touches rendering.
 *
 * <p>The NeoForge mod's {@code PayloadRegistrar.optional()} has no counterpart here
 * because it does not need one: Fabric payload types are optional by construction. A
 * vanilla client connects normally and never receives identities, and a client with
 * the mod joins any server, where nothing ever arrives on the channel.
 */
public final class DggChatFabric implements ModInitializer {

    public static final String MOD_ID = "dggchat";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.clientboundPlay().register(IdentityPayload.TYPE, IdentityPayload.STREAM_CODEC);

        ServerLifecycleEvents.SERVER_STARTED.register(ServerIdentities::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> ServerIdentities.onServerStopping());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                ServerIdentities.onPlayerJoin(handler.player, server));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                ServerIdentities.onPlayerLeave(handler.player.getUUID()));
        ServerTickEvents.END_SERVER_TICK.register(ServerIdentities::onServerTick);
        // A source resolving identities asynchronously signals here when an answer lands
        // after the initial ask, so a change propagates at once instead of dying against
        // the relay's retry ladder.
        IdentitySources.addChangeListener(ServerIdentities::onIdentityChanged);
        LOGGER.info("DGG Chat loading; {} identity source(s) available", IdentitySources.count());
    }
}
