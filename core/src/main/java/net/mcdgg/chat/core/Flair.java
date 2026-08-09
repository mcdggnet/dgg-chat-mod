package net.mcdgg.chat.core;

import java.util.Objects;

/**
 * One entry of destiny.gg's flair list, joined with the two things that live only in
 * {@code flairs.css}: the icon's display {@code order} and its {@code display: none}.
 *
 * @param name         the feature string, e.g. {@code "flair33"} or {@code "moderator"}
 * @param label        human name, e.g. {@code "Tier 5 Subscriber"}
 * @param hidden       colours the username but draws no icon
 * @param priority     drives username colour, ascending. Not the icon order.
 * @param color        {@code #RRGGBB}, or null when the flair contributes no colour
 * @param rainbowColor overrides {@code color} with the animated hue ramp
 * @param iconUrl      absolute URL, or null. {@code flair125} publishes an all-null image.
 * @param order        the CSS flex {@code order}, which is what icon sequence comes from
 */
public record Flair(
        String name,
        String label,
        boolean hidden,
        int priority,
        String color,
        boolean rainbowColor,
        String iconUrl,
        int iconWidth,
        int iconHeight,
        int order) {

    /** Where a flair with no CSS {@code order} sorts: after everything that has one. */
    public static final int NO_ORDER = Integer.MAX_VALUE;

    public Flair {
        Objects.requireNonNull(name, "name");
    }

    /**
     * True when this flair decides the username colour, which is what chat-gui tests for.
     *
     * <p>chat-gui writes {@code f.rainbowColor || f.color} and relies on JavaScript
     * truthiness, so an empty string counts as no colour. Two live flairs, {@code flair5}
     * and {@code flair124}, publish exactly that, and treating them as coloured here would
     * hand them the username against what the site shows.
     */
    public boolean hasColor() {
        return rainbowColor || (color != null && !color.isEmpty());
    }

    /** False for {@code flair125}, whose image entry has a null url, mime, width and height. */
    public boolean hasIcon() {
        return iconUrl != null && iconWidth > 0 && iconHeight > 0;
    }

    /** {@code #RRGGBB} as {@code 0xRRGGBB}, or {@code fallback} if unparseable or absent. */
    public int colorRgb(int fallback) {
        if (!hasColor() || color == null) {
            return fallback;
        }
        String hex = color.startsWith("#") ? color.substring(1) : color;
        try {
            return switch (hex.length()) {
                case 6 -> Integer.parseInt(hex, 16);
                // Not seen in the live data, but #abc is legal CSS and cheap to accept.
                case 3 -> expandShorthand(hex);
                default -> fallback;
            };
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int expandShorthand(String hex) {
        int packed = Integer.parseInt(hex, 16);
        int r = (packed >> 8) & 0xF;
        int g = (packed >> 4) & 0xF;
        int b = packed & 0xF;
        return (r * 0x11) << 16 | (g * 0x11) << 8 | (b * 0x11);
    }
}
