package net.mcdgg.chat.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RainbowTest {

    @Test
    @DisplayName("the ramp starts on the first CSS stop, hsl(0, 100%, 65%)")
    void firstStop() {
        assertEquals(0xFF4D4D, Rainbow.hslToRgb(0, 1.0, 0.65));
        assertEquals(0xFF4D4D, Rainbow.rgbAt(0.0, 0L));
    }

    @Test
    @DisplayName("hue 360 closes the loop back onto hue 0, so the gradient repeats seamlessly")
    void rampWraps() {
        assertEquals(Rainbow.hslToRgb(0, 1.0, 0.65), Rainbow.hslToRgb(360, 1.0, 0.65));
        assertEquals(Rainbow.rgbAt(0.0, 0L), Rainbow.rgbAt(1.0, 0L));
    }

    @Test
    void repeatsEveryThreeSeconds() {
        assertEquals(3000L, Rainbow.PERIOD_MS);
        for (double u = 0; u < 1.0; u += 0.17) {
            assertEquals(Rainbow.rgbAt(u, 250L), Rainbow.rgbAt(u, 250L + Rainbow.PERIOD_MS));
            assertEquals(Rainbow.rgbAt(u, 250L), Rainbow.rgbAt(u, 250L + 10 * Rainbow.PERIOD_MS));
        }
    }

    @Test
    @DisplayName("negative timestamps are as valid as any other, since callers pass a wall clock")
    void handlesNegativeTime() {
        assertEquals(Rainbow.rgbAt(0.3, 500L), Rainbow.rgbAt(0.3, 500L - Rainbow.PERIOD_MS));
    }

    @Test
    @DisplayName("scrolling runs backwards through the ramp, matching background-position-x: -100%")
    void scrollsBackwards() {
        // A quarter period is six of the eight segments back around the ring.
        assertEquals(Rainbow.hslToRgb(270, 1.0, 0.65), Rainbow.rgbAt(0.0, Rainbow.PERIOD_MS / 4));
        assertNotEquals(Rainbow.rgbAt(0.0, 0L), Rainbow.rgbAt(0.0, Rainbow.PERIOD_MS / 4));
    }

    @Test
    void charactersSampleTheirOwnMidpoint() {
        assertEquals(Rainbow.rgbAt(0.5, 0L), Rainbow.rgbForCharacter(0, 1, 0L));
        assertEquals(Rainbow.rgbAt(0.25, 0L), Rainbow.rgbForCharacter(0, 2, 0L));
        assertEquals(Rainbow.rgbAt(0.75, 0L), Rainbow.rgbForCharacter(1, 2, 0L));
        // An empty name must not divide by zero.
        assertEquals(0xFF4D4D, Rainbow.rgbForCharacter(0, 0, 0L));
    }

    @Test
    void everySampleIsInRange() {
        for (int i = 0; i <= 200; i++) {
            int rgb = Rainbow.rgbAt(i / 200.0, i * 37L);
            assertEquals(rgb & 0xFFFFFF, rgb, "channel overflow at " + i);
        }
    }
}
