package net.mcdgg.chat.neoforge;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Entry point.
 *
 * <p>Skeleton: the build, the wire format and the identity handoff are real, the
 * rendering is not yet. See README.md for the design this is being built towards.
 */
@Mod(DggChatMod.MOD_ID)
public final class DggChatMod {

    public static final String MOD_ID = "dggchat";

    private static final Logger LOGGER = LogUtils.getLogger();

    public DggChatMod(IEventBus modBus) {
        modBus.addListener(Network::register);
        LOGGER.info("DGG Chat loading; {} identity source(s) available", IdentitySources.count());
    }
}
