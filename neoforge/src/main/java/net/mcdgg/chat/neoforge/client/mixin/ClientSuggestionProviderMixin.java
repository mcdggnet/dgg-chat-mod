package net.mcdgg.chat.neoforge.client.mixin;

import net.mcdgg.chat.neoforge.client.DggFont;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Offers emote names to tab completion in chat.
 *
 * <p>{@code getCustomTabSugggestions} (Mojang's spelling) is what {@code CommandSuggestions}
 * consults for anything that is not a command, which is where the online player names come
 * from. Adding to it is the whole feature: pressing Tab already filters, ranks, inserts and
 * draws the popup.
 *
 * <p>It fits emotes better than it might look. Matching there is case-insensitive while the
 * text it inserts keeps the original casing, and emote matching in chat is case-sensitive —
 * so typing {@code pepe} and pressing Tab produces {@code PEPE}, which then renders, where
 * typing it out by hand would not have.
 */
@Mixin(ClientSuggestionProvider.class)
public class ClientSuggestionProviderMixin {

    @Inject(method = "getCustomTabSugggestions", at = @At("RETURN"), cancellable = true)
    private void dggchat$suggestEmoteNames(CallbackInfoReturnable<Collection<String>> callback) {
        // Only what can actually be drawn. Completing a name that would render as text is
        // worse than not completing it.
        if (!DggFont.isActive()) {
            return;
        }
        List<String> emotes = DggFont.emoteNames();
        if (emotes.isEmpty()) {
            return;
        }

        Collection<String> existing = callback.getReturnValue();
        List<String> combined = new ArrayList<>(existing.size() + emotes.size());
        // Players first: on a server, the person you are replying to matters more than an
        // emote that happens to share their first two letters.
        combined.addAll(existing);
        combined.addAll(emotes);
        callback.setReturnValue(combined);
    }
}
