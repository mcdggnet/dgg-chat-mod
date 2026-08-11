package net.mcdgg.chat.neoforge.client.mixin;

import net.mcdgg.chat.neoforge.client.TitleEmotes;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Runs the emote rewrite over incoming titles and subtitles, so a server can put an
 * emote on the whole screen by sending its plain name. See {@link TitleEmotes} for
 * why this exists and how conservatively it rewrites.
 */
@Mixin(Gui.class)
public abstract class GuiMixin {

    @ModifyVariable(method = "setTitle", at = @At("HEAD"), argsOnly = true)
    private Component dggchat$titleEmotes(Component title) {
        return TitleEmotes.rewrite(title);
    }

    @ModifyVariable(method = "setSubtitle", at = @At("HEAD"), argsOnly = true)
    private Component dggchat$subtitleEmotes(Component subtitle) {
        return TitleEmotes.rewrite(subtitle);
    }
}
