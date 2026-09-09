package net.mcdgg.chat.fabric.client.mixin;

import net.mcdgg.chat.fabric.client.ChatDecorator;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Runs the emote and name rewrite over every line entering the chat window.
 *
 * <p>The private four-argument {@code addMessage} is the funnel: {@code addPlayerMessage},
 * {@code addServerSystemMessage} and {@code addClientSystemMessage} all end there, so one
 * hook sees signed chat, the game messages No Chat Reports turns chat into, and system
 * lines alike. Fabric API's message events cannot do this: {@code MODIFY_GAME} covers
 * only game messages, and player chat has no modify hook at all because its component is
 * derived from the signed body. Rewriting here changes what is displayed and logged, never
 * what was signed, which is exactly the boundary the mod wants.
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {

    @ModifyVariable(
            method = "addMessage(Lnet/minecraft/network/chat/Component;"
                    + "Lnet/minecraft/network/chat/MessageSignature;"
                    + "Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;"
                    + "Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
            at = @At("HEAD"), argsOnly = true)
    private Component dggchat$decorate(Component message) {
        return ChatDecorator.rewrite(message);
    }
}
