package net.mcdgg.chat.neoforge.client.mixin;

import net.mcdgg.chat.neoforge.client.RainbowText;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Swaps {@link RainbowText} sentinel colours for the live gradient, per glyph, per frame.
 *
 * <p>This is the draw half of the baked-line animation trick: the component tree holds a
 * position-encoding sentinel (see {@link RainbowText}), and this mixin resolves it against
 * the clock at the last moment before the glyph is emitted. Hooked here rather than in any
 * chat-specific place because every styled text path funnels through
 * {@code Font$StringRenderOutput} - chat history, the tab list, tooltips - so one hook
 * animates a rainbow name anywhere it appears.
 *
 * <p>Cost: one colour comparison per glyph. The style is only rebuilt for sentinel
 * glyphs, so ordinary text pays a masked integer compare and nothing else.
 */
@Mixin(targets = "net.minecraft.client.gui.Font$StringRenderOutput")
public abstract class FontMixin {

    @ModifyVariable(method = "accept(ILnet/minecraft/network/chat/Style;I)Z",
            at = @At("HEAD"), argsOnly = true)
    private Style dggchat$animateRainbow(Style style) {
        TextColor color = style.getColor();
        if (color != null && RainbowText.isSentinel(color.getValue())) {
            return style.withColor(TextColor.fromRgb(
                    RainbowText.resolve(color.getValue(), System.currentTimeMillis())));
        }
        return style;
    }
}
