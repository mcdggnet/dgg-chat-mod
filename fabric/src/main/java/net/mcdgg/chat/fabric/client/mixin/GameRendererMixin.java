package net.mcdgg.chat.fabric.client.mixin;

import net.mcdgg.chat.fabric.client.DggChatFabricClient;
import net.mcdgg.chat.fabric.client.DggFont;
import net.mcdgg.chat.fabric.client.EmoteTextures;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Advances emote animation once per rendered frame, on the wall clock.
 *
 * <p>Not on ticks: emotes are baked at twenty to thirty frames a second, and a twenty
 * tick clock would alias against that. A frame clock also keeps chat moving while the
 * game is paused, which is exactly when someone is reading it. Fabric API has no
 * render-frame event, hence the mixin; {@code GameRenderer.render} is the top of every
 * frame, before the GUI draws from the atlas this writes into. Targeted by name and taking
 * no arguments, since 26.3 dropped the ones 26.2's {@code render} had.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void dggchat$animateEmotes(CallbackInfo ci) {
        if (DggFont.isActive()) {
            DggChatFabricClient.selfCheck();
            EmoteTextures.animate(Util.getMillis());
        }
    }
}
