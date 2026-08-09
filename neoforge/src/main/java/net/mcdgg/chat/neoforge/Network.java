package net.mcdgg.chat.neoforge;

import com.mojang.logging.LogUtils;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

/**
 * Payload registration.
 *
 * <p>{@link PayloadRegistrar#optional()} is the whole compatibility story and is not a
 * detail to drop. A required payload makes the channel mandatory, and a server running
 * this mod then refuses every client that lacks it. Optional means a vanilla client
 * connects normally and simply never receives identities, and a client with the mod can
 * join any server at all because nothing ever arrives on the channel.
 */
final class Network {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Bumped when the payload shape changes, so mismatched ends degrade rather than misparse. */
    private static final String PROTOCOL_VERSION = "1";

    private Network() {}

    /**
     * Wired from the mod constructor rather than {@code @EventBusSubscriber}, whose
     * {@code bus} attribute is deprecated for removal in this NeoForge line.
     */
    static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION).optional();
        registrar.playToClient(IdentityPayload.TYPE, IdentityPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientIdentities.accept(payload)));
        LOGGER.debug("registered optional payload channel, protocol {}", PROTOCOL_VERSION);
    }
}
