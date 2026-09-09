package net.mcdgg.chat.fabric.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.mcdgg.chat.fabric.ClientIdentities;
import net.mcdgg.chat.fabric.DggChatFabric;
import net.mcdgg.chat.fabric.IdentityPayload;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for everything that only exists on a client.
 *
 * <p>Behind the {@code client} entrypoint so that a dedicated server never loads a class
 * that touches rendering. The server half runs perfectly well without any of this, and a
 * client with the mod on a server without it runs with only this.
 *
 * <p>Chat rewriting and per-frame animation are mixins rather than events, because 26.2
 * Fabric API has no hook that lets a mod replace a signed chat line before it is wrapped
 * (see {@code ChatComponentMixin}) and no render-frame event (see {@code GameRendererMixin}).
 */
public final class DggChatFabricClient implements ClientModInitializer {

    static final Logger LOGGER = LoggerFactory.getLogger(DggChatFabric.MOD_ID);

    @Override
    public void onInitializeClient() {
        FabricLoader loader = FabricLoader.getInstance();
        DggAssets.load(loader.getGameDir(), loader.getConfigDir());

        ClientPlayNetworking.registerGlobalReceiver(IdentityPayload.TYPE, (payload, context) ->
                context.client().execute(() -> ClientIdentities.accept(payload)));
        // Identities belong to a session. The next server is a different set of people.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientIdentities.clear());
    }

    private static boolean selfChecked;

    /**
     * Asks Minecraft, once, how wide it thinks an emote character is.
     *
     * <p>An emote that fails to draw looks the same however it failed: a hollow box. This
     * distinguishes the causes without needing anything on screen. Measuring goes through
     * {@code FontSet}, so a width matching the emote's own advance proves the font resolved
     * and the provider answered, and the fallback width proves it did not, which is the
     * difference between a broken glyph and a style that never survived.
     */
    public static void selfCheck() {
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
}
