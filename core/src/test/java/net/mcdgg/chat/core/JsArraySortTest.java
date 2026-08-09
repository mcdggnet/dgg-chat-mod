package net.mcdgg.chat.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Expected orderings were produced by running chat-gui's own comparator through V8:
 *
 * <pre>{@code arr.sort((a, b) => (a.priority - b.priority >= 0 ? 1 : -1))}</pre>
 *
 * <p>They are not derived from this implementation, which is the entire point.
 */
class JsArraySortTest {

    private record Element(int id, int priority) {}

    /** chat-gui's comparator, reproduced including the fact that it never returns zero. */
    private static final Comparator<Element> DGG =
            (a, b) -> a.priority() - b.priority() >= 0 ? 1 : -1;

    private static List<Integer> sortIds(int... priorities) {
        List<Element> elements = new ArrayList<>();
        for (int i = 0; i < priorities.length; i++) {
            elements.add(new Element(i, priorities[i]));
        }
        JsArraySort.sort(elements, DGG);
        return elements.stream().map(Element::id).toList();
    }

    @Test
    @DisplayName("an all-equal array is left alone, because the comparator calls it ascending")
    void allEqual() {
        assertEquals(List.of(0, 1, 2, 3), sortIds(1, 1, 1, 1));
        assertEquals(List.of(0, 1), sortIds(1, 1));
    }

    @Test
    void alreadyAscending() {
        assertEquals(List.of(0, 1, 2, 3, 4), sortIds(1, 2, 3, 4, 5));
    }

    @Test
    void strictlyDescendingIsReversedWholesale() {
        assertEquals(List.of(4, 3, 2, 1, 0), sortIds(5, 4, 3, 2, 1));
    }

    @Test
    void tiesInTheMiddle() {
        assertEquals(List.of(1, 2, 4, 3, 0, 5), sortIds(3, 1, 1, 2, 1, 3));
    }

    @Test
    @DisplayName("a spread of real flair priorities")
    void realPriorities() {
        assertEquals(List.of(1, 9, 0, 3, 5, 7, 6, 2, 8, 4), sortIds(3, 1, 5, 3, 7, 3, 4, 3, 6, 1));
    }

    @Test
    void manyInterleavedTies() {
        assertEquals(
                List.of(3, 4, 8, 10, 0, 1, 2, 7, 11, 5, 6, 9),
                sortIds(2, 2, 2, 1, 1, 3, 3, 2, 1, 3, 1, 2));
    }

    @Test
    void degenerateSizes() {
        assertEquals(List.of(), sortIds());
        assertEquals(List.of(0), sortIds(9));
    }

    @Test
    @DisplayName("the result is always ascending by priority, whatever happens to ties")
    void alwaysAscending() {
        List<Integer> ids = sortIds(9, 3, 7, 3, 1, 8, 1, 5, 5, 2);
        int[] priorities = {9, 3, 7, 3, 1, 8, 1, 5, 5, 2};
        int previous = Integer.MIN_VALUE;
        for (int id : ids) {
            assertTrue(priorities[id] >= previous, "not ascending at id " + id);
            previous = priorities[id];
        }
    }

    @Test
    @DisplayName("List.sort rejects this comparator, which is why the port exists")
    void javaRefusesTheComparator() {
        List<Element> elements = new ArrayList<>();
        // TimSort only notices once it has enough runs to start merging them; below a few
        // hundred elements it silently produces a different order instead, which is worse.
        for (int i = 0; i < 200; i++) {
            elements.add(new Element(i, i % 7));
        }
        assertThrows(IllegalArgumentException.class, () -> elements.sort(DGG));
    }

    @Test
    void exactnessIsAdvertisedHonestly() {
        assertTrue(JsArraySort.isExactFor(46));
        assertTrue(JsArraySort.isExactFor(63));
        assertTrue(!JsArraySort.isExactFor(64));
    }
}
