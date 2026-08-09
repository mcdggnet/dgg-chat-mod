package net.mcdgg.chat.neoforge.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.mcdgg.chat.neoforge.client.DggFont;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Makes a newer chat line draw in front of an older one.
 *
 * <p>Vanilla draws every chat line's text at the same depth, {@code z = 50}, walking from
 * the newest at the bottom upwards. Equal depth means the last draw wins, and the last draw
 * is the oldest line — so an older line paints over a newer one wherever they overlap.
 *
 * <p>Ordinary text never overlaps, so this is invisible in vanilla. Emotes are about fifteen
 * units tall on a nine unit line and hang below the baseline exactly as they do on the site,
 * so an emote in an older message reaches down into the message beneath it and covers a line
 * that arrived later. Reading upwards, chat looked like it was stacked the wrong way round.
 *
 * <p>The fix is a depth nudge proportional to how far down the screen the line sits, since
 * lower means newer. Text renders with a {@code LEQUAL} depth test and writes depth, so this
 * is enough to decide the overlap. It is kept small — a fraction of a unit per line — so
 * that chat cannot climb over anything else the GUI draws above it.
 */
@Mixin(ChatComponent.class)
public class ChatComponentMixin {

    /**
     * Depth is spread across the screen height rather than accumulated per pixel.
     *
     * <p>The per-pixel version saturated: at 0.02 a line reached the 4.0 ceiling by y=200,
     * and chat sits below that, so most lines shared one depth. Ties fall back to draw
     * order, and chat draws newest first, so the older line written afterwards won the
     * LEQUAL test and covered the newer one. Scaling by screen height keeps the ordering
     * strict everywhere while still never exceeding the ceiling.
     */
    private static final float MAX_DEPTH = 4.0f;

    @WrapOperation(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString("
                            + "Lnet/minecraft/client/gui/Font;"
                            + "Lnet/minecraft/util/FormattedCharSequence;III)I"))
    private int dggchat$drawNewerLinesInFront(
            GuiGraphics graphics, Font font, FormattedCharSequence text,
            int x, int y, int colour, Operation<Integer> original) {
        if (!DggFont.isActive()) {
            return original.call(graphics, font, text, x, y, colour);
        }
        int height = Math.max(1, graphics.guiHeight());
        float depth = MAX_DEPTH * Math.min(1.0f, Math.max(0, y) / (float) height);
        graphics.pose().pushPose();
        graphics.pose().translate(0.0f, 0.0f, depth);
        int width = original.call(graphics, font, text, x, y, colour);
        graphics.pose().popPose();
        return width;
    }
}
