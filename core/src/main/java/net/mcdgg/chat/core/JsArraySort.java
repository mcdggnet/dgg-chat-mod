package net.mcdgg.chat.core;

import java.util.Comparator;
import java.util.List;

/**
 * {@code Array.prototype.sort} as V8 implements it, for comparators that are not valid
 * orderings.
 *
 * <p>This exists for one line of chat-gui:
 *
 * <pre>{@code .sort((a, b) => (a.priority - b.priority >= 0 ? 1 : -1))}</pre>
 *
 * <p>That comparator reports "greater" for equal priorities, so it never reports equality
 * and {@code compare(x, x)} is {@code 1}. Java's {@link List#sort} runs TimSort with
 * contract checking and answers such a comparator with
 * {@code IllegalArgumentException: Comparison method violates its general contract}, and
 * any "cleaned up" comparator silently reorders ties. Destiny.gg has real ties that matter:
 * four coloured flairs sit at priority 3, two of them the rainbow ones, so which flair wins
 * the username colour is decided precisely by how the engine breaks those ties.
 *
 * <p>So the sort itself is ported rather than the comparator rewritten. For fewer than 64
 * elements V8's TimSort computes a minimum run length equal to the whole array and reduces
 * to {@code CountAndMakeRun} followed by {@code BinaryInsertionSort} over everything, which
 * is what this reproduces exactly. Destiny.gg publishes 46 flairs, so a user can never
 * reach the merge path; if one ever did, the same binary insertion sort is applied to the
 * whole array, which is a good ordering but not bit-for-bit V8.
 */
public final class JsArraySort {

    /** Above this length V8 starts merging runs and this port stops being exact. */
    private static final int EXACT_UP_TO = 64;

    private JsArraySort() {}

    /** Sorts {@code list} in place the way V8 would, tolerating an inconsistent comparator. */
    public static <T> void sort(List<T> list, Comparator<? super T> comparator) {
        int length = list.size();
        if (length < 2) {
            return;
        }
        int runLength = countAndMakeRun(list, comparator, 0, length);
        binaryInsertionSort(list, comparator, 0, runLength, length);
    }

    /**
     * V8's {@code CountAndMakeRun}: measure the ascending or descending run at the front of
     * the range, reversing it in place if it was descending. A run only continues while the
     * comparator keeps agreeing, so with the flair comparator a descending run stops at the
     * first tie and reversal never touches equal elements.
     */
    private static <T> int countAndMakeRun(List<T> list, Comparator<? super T> cmp, int lowArg, int high) {
        int low = lowArg + 1;
        if (low == high) {
            return 1;
        }

        int runLength = 2;
        T elementLow = list.get(low);
        T elementLowPred = list.get(low - 1);
        boolean descending = cmp.compare(elementLow, elementLowPred) < 0;

        T previous = elementLow;
        for (int idx = low + 1; idx < high; idx++) {
            T current = list.get(idx);
            int order = cmp.compare(current, previous);
            if (descending ? order >= 0 : order < 0) {
                break;
            }
            previous = current;
            runLength++;
        }

        if (descending) {
            reverseRange(list, lowArg, lowArg + runLength);
        }
        return runLength;
    }

    /**
     * V8's {@code BinaryInsertionSort}. The binary search moves left on {@code < 0} only, so
     * an element the comparator calls "greater than" its equals is inserted ahead of them:
     * this is where tie order actually gets decided.
     */
    private static <T> void binaryInsertionSort(
            List<T> list, Comparator<? super T> cmp, int low, int startArg, int high) {
        int start = low == startArg ? startArg + 1 : startArg;
        for (int i = start; i < high; i++) {
            int left = low;
            int right = i;
            T pivot = list.get(right);
            while (left < right) {
                int mid = left + ((right - left) >> 1);
                if (cmp.compare(pivot, list.get(mid)) < 0) {
                    right = mid;
                } else {
                    left = mid + 1;
                }
            }
            for (int j = i; j > left; j--) {
                list.set(j, list.get(j - 1));
            }
            list.set(left, pivot);
        }
    }

    private static <T> void reverseRange(List<T> list, int from, int toExclusive) {
        for (int i = from, j = toExclusive - 1; i < j; i++, j--) {
            T tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }

    /** Whether {@link #sort} is a bit-exact reproduction of V8 at this size. */
    public static boolean isExactFor(int length) {
        return length < EXACT_UP_TO;
    }
}
