package net.mcdgg.chat.fabric.client.mixin;

import com.mojang.blaze3d.font.GlyphProvider;
import net.minecraft.client.gui.font.FontSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Read access to the providers a FontSet kept after selection, for the survival check. */
@Mixin(FontSet.class)
public interface FontSetAccessor {

    @Accessor("activeProviders")
    List<GlyphProvider> dggchat$activeProviders();
}
