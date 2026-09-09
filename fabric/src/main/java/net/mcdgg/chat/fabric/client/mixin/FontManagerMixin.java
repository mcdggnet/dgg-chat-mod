package net.mcdgg.chat.fabric.client.mixin;

import com.mojang.blaze3d.font.GlyphProvider;
import net.mcdgg.chat.fabric.client.DggFont;
import net.mcdgg.chat.fabric.client.DggGlyphProvider;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Puts the emote provider into the {@code dggchat:emotes} font, and nothing else.
 *
 * <p>A font's providers come from resource-pack JSON, and Minecraft's provider types are a
 * closed set, so a provider backed by images fetched at runtime has no declarative way in.
 *
 * <p>26.2 builds every FontSet through {@code createFontSet(id, providers, options)}, which
 * is the one place the font's name and its provider list meet: the FontSet itself no longer
 * knows its name. So the list is rewritten on the way in, and the FontSet that comes out is
 * remembered so {@code FontSetMixin} can recognise it on later option-change reloads.
 */
@Mixin(FontManager.class)
public abstract class FontManagerMixin {

    @ModifyVariable(
            method = "createFontSet(Lnet/minecraft/resources/Identifier;Ljava/util/List;Ljava/util/Set;)"
                    + "Lnet/minecraft/client/gui/font/FontSet;",
            at = @At("HEAD"), argsOnly = true)
    private List<GlyphProvider.Conditional> dggchat$installEmoteProvider(
            List<GlyphProvider.Conditional> providers, Identifier id) {
        if (!DggFont.FONT.equals(id)) {
            return providers;
        }
        List<GlyphProvider.Conditional> combined = new ArrayList<>(providers.size() + 1);
        // First, and this is the whole thing. Minecraft appends an AllMissingGlyphProvider
        // to every font as a catch-all, and selectProviders walks the list per codepoint and
        // stops at the first provider that answers. The catch-all answers everything, so a
        // provider added after it is asked for nothing and then dropped as unused, which
        // looks identical to a broken glyph.
        combined.add(new GlyphProvider.Conditional(
                DggFont.createProvider(), FontOption.Filter.ALWAYS_PASS));
        for (GlyphProvider.Conditional existing : providers) {
            if (!(existing.provider() instanceof DggGlyphProvider)) {
                combined.add(existing);
            }
        }
        return combined;
    }

    /**
     * Remembers the FontSet and confirms the provider survived selection.
     *
     * <p>The initial {@code reload(List, Set)} ran inside {@code createFontSet}, before the
     * FontSet could be remembered, so {@code FontSetMixin} did not see it. This is where
     * the first verification and the first atlas reset happen; later option-change reloads
     * are the FontSet mixin's job.
     */
    @Inject(
            method = "createFontSet(Lnet/minecraft/resources/Identifier;Ljava/util/List;Ljava/util/Set;)"
                    + "Lnet/minecraft/client/gui/font/FontSet;",
            at = @At("RETURN"))
    private void dggchat$rememberEmoteFont(Identifier id, List<GlyphProvider.Conditional> providers,
                                           Set<FontOption> options, CallbackInfoReturnable<FontSet> cir) {
        if (!DggFont.FONT.equals(id)) {
            return;
        }
        FontSet fontSet = cir.getReturnValue();
        DggFont.rememberFontSet(fontSet);
        DggFont.onFontReloaded();
        DggFont.onProviderSelected(
                ((FontSetAccessor) fontSet).dggchat$activeProviders().stream()
                        .anyMatch(provider -> provider instanceof DggGlyphProvider));
    }
}
