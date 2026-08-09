package net.mcdgg.chat.neoforge.client.mixin;

import com.mojang.blaze3d.font.GlyphProvider;
import net.mcdgg.chat.neoforge.client.DggFont;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.ArrayList;
import java.util.List;

/**
 * Puts the emote provider into the {@code dggchat:emotes} font, and nothing else.
 *
 * <p>A font's providers come from resource-pack JSON, and Minecraft's provider types are a
 * closed enum, so a provider backed by images fetched at runtime has no declarative way in.
 * This is the smallest hook that works: one vanilla method, one parameter, and every font
 * except ours is handed back exactly what it was given.
 *
 * <p>{@code FontSet.reload} is not a method the pack's rendering mods touch. Sodium, Iris
 * and ImmediatelyFast work on chunk geometry, shaders and buffer uploads; the closest any
 * of them comes to text is batching draw calls, which happens well after glyph lookup. It
 * was the low collision risk that argued for a font in the first place.
 *
 * <p>If this ever fails to apply, {@link DggFont#isActive()} stays false and chat leaves
 * emote names as plain text rather than filling the screen with missing-glyph boxes.
 */
@Mixin(FontSet.class)
public class FontSetMixin {

    @ModifyVariable(
            method = "reload(Ljava/util/List;Ljava/util/Set;)V",
            at = @At("HEAD"),
            argsOnly = true,
            index = 1)
    private List<GlyphProvider.Conditional> dggchat$installEmoteProvider(
            List<GlyphProvider.Conditional> providers) {
        FontSet self = (FontSet) (Object) this;
        if (!DggFont.FONT.equals(self.name())) {
            return providers;
        }
        List<GlyphProvider.Conditional> combined = new ArrayList<>(providers);
        combined.add(new GlyphProvider.Conditional(
                DggFont.createProvider(), FontOption.Filter.ALWAYS_PASS));
        DggFont.onFontReloaded();
        return combined;
    }
}
