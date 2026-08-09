package net.mcdgg.chat.neoforge.client.mixin;

import net.mcdgg.chat.neoforge.client.TabListNames;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives the tab list the same flair icons and username colour as chat.
 *
 * <p>{@code getNameForDisplay} is the single point every tab entry passes through, whether
 * the name came from a scoreboard team, a server-set display name, or the raw profile, so
 * decorating here covers all three without caring which produced it.
 */
@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin {

    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
    private void dggchat$decorateTabName(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
        Component decorated = TabListNames.decorate(info, cir.getReturnValue());
        if (decorated != null) {
            cir.setReturnValue(decorated);
        }
    }
}
