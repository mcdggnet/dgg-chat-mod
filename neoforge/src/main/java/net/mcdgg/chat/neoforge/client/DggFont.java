package net.mcdgg.chat.neoforge.client;

import com.mojang.logging.LogUtils;
import net.mcdgg.chat.core.BakeManifest;
import net.mcdgg.chat.core.Flair;
import net.mcdgg.chat.core.FlairCatalogue;
import net.mcdgg.chat.neoforge.DggChatMod;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The mapping from emote and flair names to the codepoints chat actually carries.
 *
 * <p>Emotes are drawn as glyphs in a font of their own rather than as quads over the chat
 * box, so Minecraft's text renderer handles baseline, advance, wrapping, scaling and
 * scrolling, and nothing has to fight the other mods on the pack that touch chat rendering.
 * What a chat line ends up holding is ordinary text in the {@code dggchat:emotes} font,
 * one private-use character per emote.
 *
 * <p>Assignment is stable for a given manifest: prefixes are sorted and numbered from
 * {@code U+E000}, so the same bake always produces the same codepoints. Nothing persists
 * them, and nothing needs to.
 */
public final class DggFont {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final ResourceLocation FONT =
            ResourceLocation.fromNamespaceAndPath(DggChatMod.MOD_ID, "emotes");

    /** Private Use Area. Emotes from E000, flairs from F000, which leaves room for 4096. */
    private static final int EMOTE_BASE = 0xE000;
    private static final int EMOTE_CAPACITY = 0x1000;
    private static final int FLAIR_BASE = 0xF000;
    private static final int FLAIR_CAPACITY = 0x0400;

    /**
     * Tile pixels per unit of text.
     *
     * <p>destiny.gg sets chat at 14px with a 19px line, so a 30px emote is about 1.6 lines
     * tall. Minecraft's chat line is 9 units, so the same emote wants roughly 15 units, and
     * two pixels per unit is what gets there. Emotes overflowing the line is not a bug: it
     * is what {@code margin-top: -H} does on the site.
     */
    static final float PIXELS_PER_UNIT = 2.0f;

    private static volatile Glyphs glyphs = Glyphs.EMPTY;

    /**
     * Set once the provider has actually been handed to a {@link net.minecraft.client.gui.font.FontSet}.
     *
     * <p>Chat only substitutes emotes when this is true. If the mixin that installs the
     * provider ever fails to apply, the codepoints would render as missing-glyph boxes,
     * which is far worse than leaving the emote name as text.
     */
    private static volatile boolean active;

    private DggFont() {}

    /**
     * @param emoteNames every emote prefix, sorted. Held ready because tab completion asks
     *                   for it on each keypress and a HashMap key set has no useful order.
     */
    record Glyphs(Map<Integer, DggGlyph> byCodepoint,
                  Map<String, Integer> emoteCodepoints,
                  Map<String, Integer> flairCodepoints,
                  List<String> emoteNames) {

        static final Glyphs EMPTY = new Glyphs(Map.of(), Map.of(), Map.of(), List.of());
    }

    /**
     * Rebuilds the glyph table from a manifest. Safe to call before or after the font has
     * loaded; a later font reload picks up whatever is current.
     */
    static void install(BakeManifest manifest) {
        Map<Integer, DggGlyph> byCodepoint = new HashMap<>();
        Map<String, Integer> emotes = new HashMap<>();
        Map<String, Integer> flairs = new HashMap<>();

        List<String> prefixes = new ArrayList<>(manifest.prefixes());
        prefixes.sort(String::compareTo);
        int next = EMOTE_BASE;
        for (String prefix : prefixes) {
            if (next >= EMOTE_BASE + EMOTE_CAPACITY) {
                break;
            }
            BakeManifest.BakedEmote emote = manifest.get(prefix).orElseThrow();
            byCodepoint.put(next, DggGlyph.forEmote(emote));
            emotes.put(prefix, next);
            next++;
        }

        FlairCatalogue catalogue = manifest.flairs();
        List<Flair> withIcons = new ArrayList<>();
        for (Flair flair : catalogue.all()) {
            if (flair.hasIcon()) {
                withIcons.add(flair);
            }
        }
        withIcons.sort((a, b) -> a.name().compareTo(b.name()));
        int nextFlair = FLAIR_BASE;
        for (Flair flair : withIcons) {
            if (nextFlair >= FLAIR_BASE + FLAIR_CAPACITY) {
                break;
            }
            byCodepoint.put(nextFlair, DggGlyph.forFlair(flair));
            flairs.put(flair.name(), nextFlair);
            nextFlair++;
        }

        // Built from the emotes that actually received a codepoint rather than from the
        // manifest, so a manifest larger than the reserved block cannot offer completions
        // for emotes that would not draw.
        List<String> named = new ArrayList<>(emotes.keySet());
        named.sort(String::compareTo);

        Glyphs previous = glyphs;
        glyphs = new Glyphs(Map.copyOf(byCodepoint), Map.copyOf(emotes), Map.copyOf(flairs),
                List.copyOf(named));
        // Native memory, so it does not get collected on its own. In practice this only
        // frees anything if a manifest is installed twice in one session.
        for (DggGlyph stale : previous.byCodepoint().values()) {
            stale.releaseImage();
        }
    }

    /**
     * Starts an emote's animation over, which is what makes a message arriving play it.
     *
     * <p>One texture per emote means one timeline per emote, so this restarts every copy on
     * screen at once. The site gives each occurrence its own; matching that would need an
     * atlas slot per occurrence, and a shared timeline is the cheaper half of that trade.
     */
    static void restartAnimation(String prefix, long nowMs) {
        Glyphs current = glyphs;
        Integer codepoint = current.emoteCodepoints().get(prefix);
        if (codepoint == null) {
            return;
        }
        DggGlyph glyph = current.byCodepoint().get(codepoint);
        if (glyph != null && glyph.isAnimated()) {
            glyph.restart(nowMs);
        }
    }

    static Glyphs glyphs() {
        return glyphs;
    }

    /** Every drawable emote name, sorted. Empty until a manifest has loaded. */
    public static List<String> emoteNames() {
        return glyphs.emoteNames();
    }

    /** The character to put in a chat component for this emote, or null if there is none. */
    static String emoteCharacter(String prefix) {
        Integer codepoint = glyphs.emoteCodepoints().get(prefix);
        return codepoint == null ? null : new String(Character.toChars(codepoint));
    }

    static String flairCharacter(String flairName) {
        Integer codepoint = glyphs.flairCodepoints().get(flairName);
        return codepoint == null ? null : new String(Character.toChars(codepoint));
    }

    /**
     * Called from the mixin, on the font reload thread. Also the point at which the mod
     * knows the provider really did get installed.
     */
    public static com.mojang.blaze3d.font.GlyphProvider createProvider() {
        active = true;
        Glyphs current = glyphs;
        LOGGER.info("emote font installed; {} emote and {} flair glyphs known so far",
                current.emoteCodepoints().size(), current.flairCodepoints().size());
        return new DggGlyphProvider();
    }

    /**
     * Called once per font reload with whether the provider survived provider selection.
     * If it did not, emotes must not be substituted: the codepoints would draw as boxes.
     */
    public static void onProviderSelected(boolean kept) {
        active = kept;
        if (!kept) {
            LOGGER.warn("the emote provider was dropped during font selection;"
                    + " emote names will stay as plain text");
        }
    }

    /**
     * A reload throws away every font atlas page, so every glyph's recorded slot is stale.
     * Writing an animation frame to a page Minecraft has since reused would corrupt whatever
     * font now occupies it.
     */
    public static void onFontReloaded() {
        EmoteTextures.onFontReloaded();
    }

    /** Whether emote codepoints will actually draw something. */
    public static boolean isActive() {
        return active && !glyphs.byCodepoint().isEmpty();
    }
}
