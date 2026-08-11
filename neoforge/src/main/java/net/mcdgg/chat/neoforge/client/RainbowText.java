package net.mcdgg.chat.neoforge.client;

import net.mcdgg.chat.core.Rainbow;

/**
 * The trick that lets baked chat lines animate: a sentinel colour per character.
 *
 * <p>A chat line's wrapped form is built once, on arrival, and never rebuilt, so a
 * scrolling gradient cannot live in the component tree - the colours there are frozen.
 * What does run every frame is the font renderer, glyph by glyph, reading each glyph's
 * style. So the component tree carries a marker instead of a colour: each character of a
 * rainbow name is styled with a sentinel RGB that encodes the character's position across
 * the name, and {@code FontMixin} swaps it for the live gradient colour at draw time.
 *
 * <p>The sentinel range is {@code 0xFF__01}: red 255, blue 1, position in green. Blue=1
 * rather than 0 so a real colour cannot land in the range by being a plain warm hue -
 * every value is one blue-step off the pure red-to-yellow line, which no palette uses.
 * And if the mixin ever fails to apply, the fallback rendering is that warm ramp itself:
 * a static red-to-gold name rather than garbage.
 */
public final class RainbowText {

    private static final int MARKER_RED = 0xFF0000;
    private static final int MARKER_BLUE = 0x01;

    private RainbowText() {}

    /**
     * How much of the hue cycle a name spans at any instant.
     *
     * <p>Not 1.0, although the site's CSS nominally puts one full cycle across the
     * element: a browser shades smoothly through every pixel of every glyph, so the
     * full spectrum reads as a scrolling sheen. Minecraft gives each letter a single
     * flat colour, and the same full-spectrum mapping reads as individually-coloured
     * candy letters instead. A narrower window keeps adjacent letters in nearby hues,
     * so what the eye sees is one coherent band sweeping across the name - which is
     * what the site effect looks like, even though it is not what its CSS says.
     */
    private static final float NAME_SPAN = 0.3f;

    /** Style colour for character {@code index} of a rainbow name {@code length} long. */
    public static int encode(int index, int length) {
        int position = length <= 1 ? 0
                : Math.clamp(Math.round(index * NAME_SPAN * 255f / (length - 1)), 0, 255);
        return MARKER_RED | position << 8 | MARKER_BLUE;
    }

    public static boolean isSentinel(int rgb) {
        return (rgb & 0xFF00FF) == (MARKER_RED | MARKER_BLUE);
    }

    /** The live gradient colour a sentinel stands for, at wall-clock {@code timeMs}. */
    public static int resolve(int rgb, long timeMs) {
        double u = ((rgb >> 8) & 0xFF) / 255d;
        return Rainbow.rgbAt(u, timeMs);
    }
}
