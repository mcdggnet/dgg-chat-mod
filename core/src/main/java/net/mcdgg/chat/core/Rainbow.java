package net.mcdgg.chat.core;

/**
 * The scrolling gradient behind {@code rainbowColor} flairs, sampled per character.
 *
 * <p>{@code flairs.css} paints those names with
 *
 * <pre>{@code
 * repeating-linear-gradient(90deg,
 *   hsl(0,100%,65%), hsl(45,...), ... hsl(360,...) 50%)
 * background-size: 200% 100%;
 * animation: move 3s linear infinite;   // to { background-position-x: -100% }
 * }</pre>
 *
 * <p>Nine stops evenly spaced from 0% to 50% of a gradient line that is twice the element
 * wide, so one full hue cycle covers exactly one element width, and the animation scrolls
 * it by one whole cycle every three seconds.
 *
 * <p>Minecraft colours text per character rather than per gradient, so the faithful
 * approximation is to sample that same ramp at each character's midpoint. Interpolation is
 * done between the stop colours in sRGB, not between hues, because that is what a CSS
 * gradient does and the two differ slightly in saturation between stops.
 */
public final class Rainbow {

    /** One full scroll of the gradient, from {@code animation: move 3s linear infinite}. */
    public static final long PERIOD_MS = 3000L;

    private static final int[] STOP_HUES = {0, 45, 90, 135, 180, 225, 270, 315, 360};
    private static final double SATURATION = 1.0d;
    private static final double LIGHTNESS = 0.65d;

    /** Resolved once: the nine gradient stops as packed 0xRRGGBB. */
    private static final int[] STOPS = buildStops();

    private Rainbow() {}

    private static int[] buildStops() {
        int[] stops = new int[STOP_HUES.length];
        for (int i = 0; i < STOP_HUES.length; i++) {
            stops[i] = hslToRgb(STOP_HUES[i], SATURATION, LIGHTNESS);
        }
        return stops;
    }

    /**
     * Colour at fractional position {@code u} across the name at wall-clock {@code timeMs}.
     *
     * <p>{@code background-position-x} runs to {@code -100%}, which against a background
     * twice the element's width resolves to a positive offset of one element width, so the
     * gradient slides forward and the hue at any fixed point runs backwards. Hence the
     * subtraction.
     */
    public static int rgbAt(double u, long timeMs) {
        double phase = (double) Math.floorMod(timeMs, PERIOD_MS) / PERIOD_MS;
        double position = u - phase;
        position -= Math.floor(position);
        return sample(position);
    }

    /** Convenience for the common case: character {@code index} of a name {@code length} long. */
    public static int rgbForCharacter(int index, int length, long timeMs) {
        if (length <= 0) {
            return STOPS[0];
        }
        return rgbAt((index + 0.5) / length, timeMs);
    }

    private static int sample(double position) {
        int segments = STOPS.length - 1;
        double scaled = position * segments;
        int index = (int) scaled;
        if (index >= segments) {
            index = segments - 1;
        }
        return lerpRgb(STOPS[index], STOPS[index + 1], scaled - index);
    }

    private static int lerpRgb(int from, int to, double t) {
        return lerpChannel(from, to, 16, t) << 16
                | lerpChannel(from, to, 8, t) << 8
                | lerpChannel(from, to, 0, t);
    }

    private static int lerpChannel(int from, int to, int shift, double t) {
        int a = (from >> shift) & 0xFF;
        int b = (to >> shift) & 0xFF;
        return Math.clamp(Math.round(a + (b - a) * t), 0, 255);
    }

    /**
     * Done in double precision on purpose. At float width the 65% lightness lands on
     * 76.49999 and rounds to 76, one below the 77 a browser produces for the same
     * declaration, which is visible as a slightly darker ramp.
     */
    static int hslToRgb(double hueDegrees, double saturation, double lightness) {
        double h = ((hueDegrees % 360d) + 360d) % 360d / 60d;
        double c = (1d - Math.abs(2d * lightness - 1d)) * saturation;
        double x = c * (1d - Math.abs(h % 2d - 1d));
        double m = lightness - c / 2d;

        double r;
        double g;
        double b;
        if (h < 1d) {
            r = c; g = x; b = 0d;
        } else if (h < 2d) {
            r = x; g = c; b = 0d;
        } else if (h < 3d) {
            r = 0d; g = c; b = x;
        } else if (h < 4d) {
            r = 0d; g = x; b = c;
        } else if (h < 5d) {
            r = x; g = 0d; b = c;
        } else {
            r = c; g = 0d; b = x;
        }
        return channel(r + m) << 16 | channel(g + m) << 8 | channel(b + m);
    }

    private static int channel(double value) {
        return Math.clamp(Math.round(value * 255d), 0, 255);
    }
}
