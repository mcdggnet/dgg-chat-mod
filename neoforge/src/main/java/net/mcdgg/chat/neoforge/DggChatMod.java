package net.mcdgg.chat.neoforge;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Entry point for the half that runs on both sides.
 *
 * <p>The client half lives in {@code net.mcdgg.chat.neoforge.client} behind its own
 * {@code dist = Dist.CLIENT} entry point, so nothing that touches rendering is ever loaded
 * on a dedicated server.
 */
@Mod(DggChatMod.MOD_ID)
public final class DggChatMod {

    public static final String MOD_ID = "dggchat";

    private static final Logger LOGGER = LogUtils.getLogger();

    public DggChatMod(IEventBus modBus) {
        modBus.addListener(Network::register);

        // Game bus, not the mod bus: these fire per player and per tick, on whichever
        // server is running, including the integrated one in singleplayer.
        NeoForge.EVENT_BUS.addListener(ServerIdentities::onPlayerJoin);
        NeoForge.EVENT_BUS.addListener(ServerIdentities::onPlayerLeave);
        NeoForge.EVENT_BUS.addListener(ServerIdentities::onServerTick);

        // A source resolving identities asynchronously (dggauth) signals here when an
        // answer lands after the initial ask, so a mid-session /link propagates at once
        // instead of dying against the relay's ~42s retry ladder.
        IdentitySources.addChangeListener(ServerIdentities::onIdentityChanged);

        LOGGER.info("DGG Chat loading; {} identity source(s) available", IdentitySources.count());
    }
}
