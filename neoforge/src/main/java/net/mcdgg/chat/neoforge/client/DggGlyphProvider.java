package net.mcdgg.chat.neoforge.client;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;

/**
 * Supplies the {@code dggchat:emotes} font.
 *
 * <p>The supported set is a fixed block of private-use codepoints rather than exactly the
 * emotes currently known, and unclaimed codepoints resolve to a transparent glyph rather
 * than to null. Both are deliberate. {@link net.minecraft.client.gui.font.FontSet} decides
 * at reload time which providers are worth keeping by asking each one for every codepoint
 * it claims and dropping any that answers null to all of them, so a provider whose manifest
 * has not downloaded yet would be discarded and never consulted again. Claiming the block
 * up front means the font survives a cold start, and the manifest can arrive whenever it
 * arrives without forcing a resource reload on a pack where that costs several seconds.
 */
public final class DggGlyphProvider implements GlyphProvider {

    /** Emotes. 321 today; the room is free. */
    static final int EMOTE_FIRST = 0xE000;
    static final int EMOTE_LAST = 0xE7FF;
    /** Flair icons. 25 are visible today, out of 46 published. */
    static final int FLAIR_FIRST = 0xF000;
    static final int FLAIR_LAST = 0xF07F;

    private static final IntSet SUPPORTED = buildSupported();

    public DggGlyphProvider() {}

    private static IntSet buildSupported() {
        IntOpenHashSet set = new IntOpenHashSet(
                EMOTE_LAST - EMOTE_FIRST + 1 + FLAIR_LAST - FLAIR_FIRST + 1);
        for (int codepoint = EMOTE_FIRST; codepoint <= EMOTE_LAST; codepoint++) {
            set.add(codepoint);
        }
        for (int codepoint = FLAIR_FIRST; codepoint <= FLAIR_LAST; codepoint++) {
            set.add(codepoint);
        }
        return IntSets.unmodifiable(set);
    }

    @Override
    public IntSet getSupportedGlyphs() {
        return SUPPORTED;
    }

    /**
     * Read live rather than from a snapshot taken when the provider was built. The manifest
     * downloads on its own thread and can land either side of the font reload; capturing the
     * table would mean losing that race permanently and drawing nothing all session.
     */
    @Override
    public GlyphInfo getGlyph(int codepoint) {
        DggGlyph glyph = DggFont.glyphs().byCodepoint().get(codepoint);
        if (glyph != null) {
            return glyph;
        }
        return SUPPORTED.contains(codepoint) ? DggGlyph.EMPTY : null;
    }

    /**
     * Images outlive the provider on purpose: a resource reload builds a new provider over
     * the same glyph table, and re-downloading every emote in use because the player changed
     * a resource pack would be a poor trade for the memory it saves.
     */
    @Override
    public void close() {}
}
