package net.mcdgg.chat.neoforge.client;

import com.mojang.logging.LogUtils;
import net.mcdgg.chat.neoforge.ClientIdentities;
import net.mcdgg.chat.neoforge.DggChatMod;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Entry point for everything that only exists on a client.
 *
 * <p>Separate from {@link DggChatMod} and scoped to {@link Dist#CLIENT} so that a dedicated
 * server never loads a class that touches rendering. The server half runs perfectly well
 * without any of this, and a client with the mod on a server without it runs with only this.
 */
@Mod(value = DggChatMod.MOD_ID, dist = Dist.CLIENT)
public final class DggChatClient {

    static final Logger LOGGER = LogUtils.getLogger();

    public DggChatClient(IEventBus modBus) {
        modBus.addListener(DggChatClient::onClientSetup);

        NeoForge.EVENT_BUS.addListener(ChatDecorator::onChatReceived);
        NeoForge.EVENT_BUS.addListener(DggChatClient::onRenderFrame);
        NeoForge.EVENT_BUS.addListener(DggChatClient::onLoggingOut);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        DggAssets.load(Minecraft.getInstance().gameDirectory.toPath(), FMLPaths.CONFIGDIR.get());
    }

    /**
     * Animation advances on the wall clock and once per rendered frame.
     *
     * <p>Not on ticks: emotes are baked at twenty to thirty frames a second, and a twenty
     * tick clock would alias against that. A frame clock also keeps chat moving while the
     * game is paused, which is exactly when someone is reading it.
     */
    private static void onRenderFrame(RenderFrameEvent.Pre event) {
        if (DggFont.isActive()) {
            EmoteTextures.animate(Util.getMillis());
        }
    }

    /** Identities belong to a session. The next server is a different set of people. */
    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientIdentities.clear();
    }
}
