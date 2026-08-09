package net.mcdgg.chat.neoforge.client.mixin;

import com.mojang.blaze3d.font.GlyphProvider;
import net.mcdgg.chat.neoforge.client.DggFont;
import net.mcdgg.chat.neoforge.client.DggGlyphProvider;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Puts the emote provider into the {@code dggchat:emotes} font, and nothing else.
 *
 * <p>A font's providers come from resource-pack JSON, and Minecraft's provider types are a
 * closed enum, so a provider backed by images fetched at runtime has no declarative way in.
 *
 * <p>The hook is the single-argument {@code reload}, which is the last thing to touch
 * {@code allProviders} before {@code selectProviders} reads it, and it is reached from both
 * the resource reload and a font-options change. An earlier attempt modified the provider
 * list as an argument to the two-argument overload with {@code @ModifyVariable}; the handler
 * ran, and the list Minecraft went on to use was the unmodified one, which is a silent
 * failure that looks exactly like a broken glyph. Writing the shadowed field is observable.
 *
 * <p>Rebuilt rather than appended, because {@code reload(Set)} is called again whenever font
 * options change and appending would stack a new provider each time.
 *
 * <p>{@code FontSet.reload} is not a method the pack's rendering mods touch. Sodium, Iris
 * and ImmediatelyFast work on chunk geometry, shaders and buffer uploads; the closest any of
 * them comes to text is batching draw calls, well after glyph lookup.
 *
 * <p>If this ever fails to apply, {@link DggFont#isActive()} stays false and chat leaves
 * emote names as plain text rather than filling the screen with missing-glyph boxes.
 */
@Mixin(FontSet.class)
public abstract class FontSetMixin {

    @Shadow
    private List<GlyphProvider.Conditional> allProviders;

    @Shadow
    private List<GlyphProvider> activeProviders;

    @Shadow
    public abstract ResourceLocation name();

    @Inject(method = "reload(Ljava/util/Set;)V", at = @At("HEAD"))
    private void dggchat$installEmoteProvider(Set<FontOption> options, CallbackInfo callback) {
        if (!DggFont.FONT.equals(name())) {
            return;
        }

        List<GlyphProvider.Conditional> combined = new ArrayList<>(allProviders.size() + 1);
        // First, and this is the whole thing. Minecraft appends an AllMissingGlyphProvider
        // to every font as a catch-all, and selectProviders walks the list per codepoint and
        // stops at the first provider that answers. The catch-all answers everything, so a
        // provider added after it is asked for nothing and then dropped as unused — which
        // looks identical to a broken glyph, and cost an evening to find.
        combined.add(new GlyphProvider.Conditional(
                DggFont.createProvider(), FontOption.Filter.ALWAYS_PASS));
        for (GlyphProvider.Conditional existing : allProviders) {
            if (!(existing.provider() instanceof DggGlyphProvider)) {
                combined.add(existing);
            }
        }

        allProviders = combined;
        DggFont.onFontReloaded();
    }

    /**
     * Confirms the provider actually survived selection.
     *
     * <p>It did not, at first: appended after Minecraft's catch-all it was asked for no
     * codepoint and quietly dropped as unused, which renders as boxes and looks like a
     * broken glyph rather than a wiring mistake. Checking here turns a silent failure into
     * a log line, and into emote names staying as readable text.
     */
    @Inject(method = "reload(Ljava/util/Set;)V", at = @At("RETURN"))
    private void dggchat$verifyProviderSurvived(Set<FontOption> options, CallbackInfo callback) {
        if (!DggFont.FONT.equals(name())) {
            return;
        }
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
