/*
 * Copyright 2008-2026 Vassilios Karakoidas
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.firej.dfa.fire;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A set of {@code char} values held as sorted, disjoint, inclusive ranges.
 *
 * <p>This is the type the preprocessor existed to avoid needing. The 2007 engine
 * used an automaton library that could not express a character class, so
 * {@code [a-z]} had to be rewritten into a form that library accepted, and every
 * shorthand had to be expanded into explicit ranges in the pattern text before
 * parsing. Here a class is a value: {@code \d} is one {@code CharSet}, a class
 * body is a union of them, and a negated class is a complement. Nothing is
 * rewritten and nothing is re-parsed.
 *
 * <p>Immutable. The universe is {@code 0..0xFFFF}, the {@code char} range, which
 * is what the generated code walks. That is a ceiling on <em>code units</em>, not
 * code points: a character above the Basic Multilingual Plane is two of them, an
 * escape naming one is refused, and {@code .} matches either half of a surrogate
 * pair. The automaton backend has the same ceiling, so this is a property of the
 * library rather than of this engine; {@code NativeEngineTest} pins it.
 */
public final class CharSet {

    /** The largest code unit the engine matches; above the BMP is rejected. */
    public static final int MAX = 0xFFFF;

    /** Pairs of inclusive bounds: {@code lo0, hi0, lo1, hi1, ...}, sorted and disjoint. */
    private final int[] spans;

    public static final CharSet EMPTY = new CharSet(new int[0]);
    public static final CharSet ANY = new CharSet(new int[] {0, MAX});

    private CharSet(int[] spans) {
        this.spans = spans;
    }

    /** One character. */
    public static CharSet of(char c) {
        return new CharSet(new int[] {c, c});
    }

    /** One inclusive range; {@code lo} must not exceed {@code hi}. */
    public static CharSet range(int lo, int hi) {
        if (lo > hi) {
            throw new IllegalArgumentException("Inverted character range: " + lo + " > " + hi);
        }
        return new CharSet(new int[] {clamp(lo), clamp(hi)});
    }

    /** The union of a list of ranges, given as pairs. */
    public static CharSet ranges(int... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("ranges() takes pairs");
        }
        CharSet s = EMPTY;
        for (int i = 0; i < pairs.length; i += 2) {
            s = s.union(range(pairs[i], pairs[i + 1]));
        }
        return s;
    }

    private static int clamp(int c) {
        return c < 0 ? 0 : Math.min(c, MAX);
    }

    public boolean isEmpty() {
        return spans.length == 0;
    }

    public boolean contains(int c) {
        int lo = 0;
        int hi = spans.length / 2 - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (c < spans[2 * mid]) {
                hi = mid - 1;
            } else if (c > spans[2 * mid + 1]) {
                lo = mid + 1;
            } else {
                return true;
            }
        }
        return false;
    }

    /** The number of disjoint ranges. */
    public int rangeCount() {
        return spans.length / 2;
    }

    public int lo(int i) {
        return spans[2 * i];
    }

    public int hi(int i) {
        return spans[2 * i + 1];
    }

    public CharSet union(CharSet other) {
        if (isEmpty()) {
            return other;
        }
        if (other.isEmpty()) {
            return this;
        }
        int[] all = Arrays.copyOf(spans, spans.length + other.spans.length);
        System.arraycopy(other.spans, 0, all, spans.length, other.spans.length);
        return normalise(all);
    }

    public CharSet complement() {
        List<Integer> out = new ArrayList<>();
        int next = 0;
        for (int i = 0; i < spans.length; i += 2) {
            if (spans[i] > next) {
                out.add(next);
                out.add(spans[i] - 1);
            }
            next = Math.max(next, spans[i + 1] + 1);
        }
        if (next <= MAX) {
            out.add(next);
            out.add(MAX);
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return new CharSet(arr);
    }


    /** Sort the pairs and merge everything that touches or overlaps. */
    private static CharSet normalise(int[] pairs) {
        int n = pairs.length / 2;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Integer.compare(pairs[2 * a], pairs[2 * b]));
        List<Integer> out = new ArrayList<>();
        int curLo = -1;
        int curHi = -2;
        for (int idx = 0; idx < n; idx++) {
            int lo = pairs[2 * order[idx]];
            int hi = pairs[2 * order[idx] + 1];
            if (curHi < curLo) {
                curLo = lo;
                curHi = hi;
            } else if (lo <= curHi + 1) {
                curHi = Math.max(curHi, hi);
            } else {
                out.add(curLo);
                out.add(curHi);
                curLo = lo;
                curHi = hi;
            }
        }
        if (curHi >= curLo) {
            out.add(curLo);
            out.add(curHi);
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return new CharSet(arr);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CharSet c && Arrays.equals(spans, c.spans);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(spans);
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < spans.length; i += 2) {
            b.append((char) spans[i]);
            if (spans[i + 1] > spans[i]) {
                b.append('-').append((char) spans[i + 1]);
            }
        }
        return b.append(']').toString();
    }
}
