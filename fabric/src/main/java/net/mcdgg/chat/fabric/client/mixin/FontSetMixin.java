package net.mcdgg.chat.fabric.client.mixin;

import com.mojang.blaze3d.font.GlyphProvider;
import net.mcdgg.chat.fabric.client.DggFont;
import net.mcdgg.chat.fabric.client.DggGlyphProvider;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Set;

/**
 * Keeps the emote font honest across an options change.
 *
 * <p>{@code reload(Set)} is what a font-options change calls on every existing FontSet: it
 * throws away every atlas page and selects providers again from {@code allProviders},
 * which still holds the emote provider {@code FontManagerMixin} put there. Two things
 * follow for the emote font, and only for it: every glyph's recorded atlas slot is stale
 * and must be forgotten before the next frame writes an animation frame into a page some
 * other font now owns, and the provider may in principle have been dropped, which is worth
 * a log line and a fallback to plain text rather than a screen of boxes.
 *
 * <p>If this ever fails to apply, {@link DggFont#isActive()} stays whatever the manager
 * mixin set at creation, which is the same answer for a font that never changed.
 */
@Mixin(FontSet.class)
public abstract class FontSetMixin {

    @Shadow
    private List<GlyphProvider> activeProviders;

    @Inject(method = "reload(Ljava/util/Set;)V", at = @At("RETURN"))
    private void dggchat$afterReload(Set<FontOption> options, CallbackInfo callback) {
        if (!DggFont.isEmoteFontSet(this)) {
            return;
        }
        DggFont.onFontReloaded();
        boolean kept = false;
        for (GlyphProvider provider : activeProviders) {
            if (provider instanceof DggGlyphProvider) {
                kept = true;
                break;
            }
        }
        DggFont.onProviderSelected(kept);
    }
}
