package net.mcdgg.chat.neoforge.client;

import com.mojang.logging.LogUtils;
import net.mcdgg.chat.neoforge.ClientIdentities;
import net.mcdgg.chat.neoforge.DggChatMod;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
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

        // Last, deliberately. A chat mod that rebuilds the component from its text would
        // drop the font off the emote characters, and ATM10 ships two that touch chat. This
        // was not the cause of anything observed — it is cheap insurance that our styling is
        // the version that survives.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ChatDecorator::onChatReceived);
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
            selfCheck();
            EmoteTextures.animate(Util.getMillis());
        }
    }

    private static boolean selfChecked;

    /**
     * Asks Minecraft, once, how wide it thinks an emote character is.
     *
     * <p>An emote that fails to draw looks the same however it failed: a hollow box. This
     * distinguishes the causes without needing anything on screen. Measuring goes through
     * {@code FontSet.getGlyphInfo}, so a width matching the emote's own advance proves the
     * font resolved and the provider answered, and the fallback width proves it did not —
     * which is the difference between a broken glyph and a style that never survived.
     */
    private static void selfCheck() {
        if (selfChecked) {
            return;
        }
        selfChecked = true;
        try {
            String prefix = DggFont.glyphs().emoteCodepoints().keySet().stream().findFirst().orElse(null);
            String character = prefix == null ? null : DggFont.emoteCharacter(prefix);
            if (character == null) {
                return;
            }
            int styled = Minecraft.getInstance().font
                    .width(net.minecraft.network.chat.Component.literal(character)
                            .setStyle(MessageRewriter.EMOTE_STYLE));
            int plain = Minecraft.getInstance().font.width(character);
            LOGGER.info("font self-check: {} (U+{}) measures {}px in {}, {}px in the default font",
                    prefix,
                    Integer.toHexString(character.codePointAt(0)).toUpperCase(),
                    styled,
                    DggFont.FONT,
                    plain);
        } catch (RuntimeException e) {
            LOGGER.warn("font self-check failed", e);
        }
    }

    /** Identities belong to a session. The next server is a different set of people. */
    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientIdentities.clear();
    }
}
