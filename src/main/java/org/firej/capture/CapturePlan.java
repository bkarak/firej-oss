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
package org.firej.capture;

import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import org.firej.RegexCompilationException;

import org.firej.MatchResult;
import org.firej.capture.Expr.Alt;
import org.firej.capture.Expr.Any;
import org.firej.capture.Expr.AnyString;
import org.firej.capture.Expr.Capture;
import org.firej.capture.Expr.Ch;
import org.firej.capture.Expr.Cls;
import org.firej.capture.Expr.Complement;
import org.firej.capture.Expr.Concat;
import org.firej.capture.Expr.Empty;
import org.firej.capture.Expr.Intersect;
import org.firej.capture.Expr.Interval;
import org.firej.capture.Expr.Nothing;
import org.firej.capture.Expr.Repeat;
import org.firej.capture.Expr.Str;

/**
 * Recover capturing-group offsets from a match span. The DFA still decides
 * where the match is; this walks the pattern AST on that slice only.
 *
 * <p>A plan is immutable and shared by every matcher for the same pattern.
 * The per-matcher scratch state lives in an {@link Engine}, which a
 * {@link org.firej.Regex} keeps and reuses across runs.
 */
public final class CapturePlan {
    private static final CapturePlan NONE = new CapturePlan(new Empty(), 0);

    private final Expr root;
    private final int groupCount;
    /** Every node of the tree numbered 0..n-1, so the engine can memoize in arrays. */
    private final IdentityHashMap<Expr, Integer> nodeIndex = new IdentityHashMap<>();

    private CapturePlan(Expr root, int groupCount) {
        this.root = root;
        this.groupCount = groupCount;
        number(root);
    }

    private void number(Expr expr) {
        nodeIndex.put(expr, nodeIndex.size());
        switch (expr) {
            case Concat(Expr[] parts) -> {
                for (Expr part : parts) {
                    number(part);
                }
            }
            case Alt(Expr[] alts) -> {
                for (Expr alt : alts) {
                    number(alt);
                }
            }
            case Repeat(Expr child, int min, int max) -> number(child);
            case Capture(int index, Expr child) -> number(child);
            case Intersect(Expr left, Expr right) -> {
                number(left);
                number(right);
            }
            case Complement(Expr child) -> number(child);
            default -> {
            }
        }
    }

    /**
     * The plan for a pattern, or a compile failure.
     *
     * <p>A pattern with no groups gets {@link #NONE} and costs nothing. A pattern
     * that <em>has</em> groups but whose plan cannot be built used to get
     * {@code NONE} as well — so {@code groupCount()} answered 0 for a pattern with
     * two groups, and every group came back null from a match that had otherwise
     * succeeded. That is the failure mode this library's own rule forbids: do not
     * silently succeed, throw.
     *
     * <p>It happens because the plan is still parsed through the dk.brics front end
     * whatever engine compiled the automaton, so a pattern the native engine accepts
     * and that front end rejects has a working DFA and no plan. Four rows of the
     * regexlib corpus are in exactly that position. Lowering the native engine's own
     * syntax tree to a plan would remove the asymmetry; until then the pattern is
     * refused rather than half-supported.
     */
    public static CapturePlan compile(String pattern) {
        try {
            ExprParser.Parsed parsed = ExprParser.parse(pattern);
            if (parsed.groupCount() == 0) {
                return NONE;
            }
            return new CapturePlan(parsed.root(), parsed.groupCount());
        } catch (RuntimeException e) {
            if (!hasCapturingGroup(pattern)) {
                return NONE;                    // nothing to recover; the failure is moot
            }
            throw new RegexCompilationException(
                    "The automaton compiles but no capturing-group plan can be built for: " + pattern
                            + " -- the plan is parsed through the dk.brics front end, which rejects it ("
                            + e.getMessage() + ")", e);
        }
    }

    /**
     * Whether the pattern has a group whose contents would be captured, skipping
     * escapes, character classes and the non-capturing forms.
     */
    private static boolean hasCapturingGroup(String pattern) {
        boolean inClass = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                i++;
            } else if (inClass) {
                if (c == ']') {
                    inClass = false;
                }
            } else if (c == '[') {
                inClass = true;
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '^') {
                    i++;
                }
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == ']') {
                    i++;
                }
            } else if (c == '(') {
                if (i + 1 >= pattern.length() || pattern.charAt(i + 1) != '?') {
                    return true;                // a plain group
                }
                boolean named = pattern.startsWith("(?P<", i)
                        || (pattern.startsWith("(?<", i) && i + 3 < pattern.length()
                                && pattern.charAt(i + 3) != '=' && pattern.charAt(i + 3) != '!');
                if (named) {
                    return true;
                }
            }
        }
        return false;
    }

    public int groupCount() {
        return groupCount;
    }

    public Engine newEngine() {
        return new Engine(this);
    }

    /**
     * Memoized exact-span matcher. The DFA already fixed {@code [from, to)}; we
     * only recover one greedy parse of that slice so {@code .*} cannot explode.
     *
     * <p>Every sub-span the engine asks about lies inside {@code [from, to]}, so
     * the memo is a flat {@code byte[]} indexed by node, span start and span
     * end ({@code 0} unknown, {@code 1} no, {@code 2} yes) — no boxing, no
     * hashing, and the arrays are kept between runs. Not thread-safe; one per
     * matcher.
     */
    public static final class Engine {
        private static final byte NO = 1;
        private static final byte YES = 2;

        private final Expr root;
        private final int groupCount;
        private final Map<Expr, Integer> nodeIndex;
        private final int nodeCount;
        private final int[] start;
        private final int[] end;
        private final HashMap<RepeatKey, Boolean> repeatMemo = new HashMap<>();
        private CharSequence input = "";
        private int base;
        private int width;
        private byte[] memo = new byte[0];
        private final byte[][] concatMemo;

        Engine(CapturePlan plan) {
            this.root = plan.root;
            this.groupCount = plan.groupCount;
            this.nodeIndex = plan.nodeIndex;
            this.nodeCount = plan.nodeIndex.size();
            this.start = new int[groupCount + 1];
            this.end = new int[groupCount + 1];
            this.concatMemo = new byte[nodeCount][];
        }

        public void fill(CharSequence input, int from, int to, MatchResult result) {
            if (groupCount == 0 || to < from) {
                return;
            }
            reset(input, from, to);
            if (!exact(root, from, to)) {
                return;
            }
            reconstruct(root, from, to);
            for (int g = 1; g <= groupCount; g++) {
                result.capture(g, start[g], end[g]);
            }
        }

        private void reset(CharSequence newInput, int from, int to) {
            this.input = newInput;
            this.base = from;
            this.width = to - from + 1;
            Arrays.fill(start, -1);
            Arrays.fill(end, -1);
            int need = nodeCount * width * width;
            if (memo.length < need) {
                memo = new byte[need];
            } else {
                Arrays.fill(memo, 0, need, (byte) 0);
            }
            Arrays.fill(concatMemo, null);
            repeatMemo.clear();
        }

        private int cell(int node, int from, int to) {
            return (node * width + (from - base)) * width + (to - base);
        }

        boolean exact(Expr expr, int from, int to) {
            if (from > to) {
                return false;
            }
            int cell = cell(nodeIndex.get(expr), from, to);
            byte cached = memo[cell];
            if (cached != 0) {
                return cached == YES;
            }
            boolean ok = exactUncached(expr, from, to);
            memo[cell] = ok ? YES : NO;
            return ok;
        }

        private boolean exactUncached(Expr expr, int from, int to) {
            return switch (expr) {
                case Empty() -> from == to;
                case Nothing() -> false;
                case Any() -> to == from + 1;
                case AnyString() -> true;
                case Ch(char c) -> to == from + 1 && input.charAt(from) == c;
                case Str(String s) -> regionEquals(s, from, to);
                case Cls cls -> to == from + 1 && cls.contains(input.charAt(from));
                case Concat c -> exactConcat(nodeIndex.get(c), c.parts(), 0, from, to);
                case Alt(Expr[] alts) -> {
                    for (Expr alt : alts) {
                        if (exact(alt, from, to)) {
                            yield true;
                        }
                    }
                    yield false;
                }
                case Repeat(Expr child, int min, int max) -> exactRepeat(child, min, max, from, to);
                case Capture(int index, Expr child) -> exact(child, from, to);
                case Intersect(Expr left, Expr right) -> exact(left, from, to) && exact(right, from, to);
                case Complement(Expr child) -> !exact(child, from, to);
                case Interval(int min, int max, int digits) -> exactInterval(min, max, digits, from, to);
            };
        }

        private boolean regionEquals(String s, int from, int to) {
            if (to - from != s.length()) {
                return false;
            }
            for (int i = 0; i < s.length(); i++) {
                if (input.charAt(from + i) != s.charAt(i)) {
                    return false;
                }
            }
            return true;
        }

        /** Does {@code parts[i..]} exactly cover {@code [from, to)}? Memoized per concat node and suffix. */
        private boolean exactConcat(int node, Expr[] parts, int i, int from, int to) {
            byte[] table = concatMemo[node];
            if (table == null) {
                table = new byte[parts.length * width * width];
                concatMemo[node] = table;
            }
            int cell = cell(i, from, to);
            byte cached = table[cell];
            if (cached != 0) {
                return cached == YES;
            }
            boolean ok = exactConcatUncached(node, parts, i, from, to);
            table[cell] = ok ? YES : NO;
            return ok;
        }

        private boolean exactConcatUncached(int node, Expr[] parts, int i, int from, int to) {
            if (i == parts.length) {
                return from == to;
            }
            if (i == parts.length - 1) {
                return exact(parts[i], from, to);
            }
            for (int mid = to; mid >= from; mid--) {
                if (exact(parts[i], from, mid) && exactConcat(node, parts, i + 1, mid, to)) {
                    return true;
                }
            }
            return false;
        }

        private boolean exactRepeat(Expr child, int min, int max, int from, int to) {
            RepeatKey key = new RepeatKey(child, min, max, from, to);
            Boolean cached = repeatMemo.get(key);
            if (cached != null) {
                return cached;
            }
            boolean ok = exactRepeatUncached(child, min, max, from, to);
            repeatMemo.put(key, ok);
            return ok;
        }

        private boolean exactRepeatUncached(Expr child, int min, int max, int from, int to) {
            int n = to - from;
            boolean[] reachable = new boolean[n + 1];
            reachable[0] = true;
            boolean[] ok = new boolean[n + 1];
            if (min == 0) {
                ok[0] = true;
            }
            int bound = max == ExprParser.UNBOUNDED ? n + 1 : Math.min(max, n + 1);
            for (int taken = 0; taken < bound; taken++) {
                boolean[] next = new boolean[n + 1];
                boolean progressed = false;
                boolean onlyEmpty = true;
                for (int i = 0; i <= n; i++) {
                    if (!reachable[i]) {
                        continue;
                    }
                    for (int j = i; j <= n; j++) {
                        if (exact(child, from + i, from + j)) {
                            next[j] = true;
                            progressed = true;
                            if (j > i) {
                                onlyEmpty = false;
                            }
                        }
                    }
                }
                if (!progressed) {
                    break;
                }
                reachable = next;
                int iters = taken + 1;
                if (iters >= min) {
                    for (int i = 0; i <= n; i++) {
                        if (reachable[i]) {
                            ok[i] = true;
                        }
                    }
                }
                if (onlyEmpty) {
                    break;
                }
            }
            return ok[n];
        }

        private boolean exactInterval(int min, int max, int digits, int from, int to) {
            int len = to - from;
            if (len == 0) {
                return false;
            }
            if (digits > 0 && len != digits) {
                return false;
            }
            int value = 0;
            for (int i = from; i < to; i++) {
                char c = input.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
                value = value * 10 + (c - '0');
            }
            return value >= min && value <= max;
        }

        private record RepeatKey(Expr child, int min, int max, int from, int to) {
        }

        void reconstruct(Expr expr, int from, int to) {
            switch (expr) {
                case Capture(int index, Expr child) -> {
                    start[index] = from;
                    end[index] = to;
                    reconstruct(child, from, to);
                }
                case Concat c -> reconstructConcat(nodeIndex.get(c), c.parts(), 0, from, to);
                case Alt(Expr[] alts) -> {
                    for (Expr alt : alts) {
                        if (exact(alt, from, to)) {
                            reconstruct(alt, from, to);
                            return;
                        }
                    }
                }
                case Repeat(Expr child, int min, int max) -> reconstructRepeat(child, min, max, from, to);
                case Intersect(Expr left, Expr right) -> reconstruct(left, from, to);
                default -> {
                }
            }
        }

        private void reconstructConcat(int node, Expr[] parts, int i, int from, int to) {
            if (i == parts.length) {
                return;
            }
            if (i == parts.length - 1) {
                reconstruct(parts[i], from, to);
                return;
            }
            for (int mid = to; mid >= from; mid--) {
                if (exact(parts[i], from, mid) && exactConcat(node, parts, i + 1, mid, to)) {
                    reconstruct(parts[i], from, mid);
                    reconstructConcat(node, parts, i + 1, mid, to);
                    return;
                }
            }
        }

        private void reconstructRepeat(Expr child, int min, int max, int from, int to) {
            int p = from;
            int taken = 0;
            while (taken < max && p < to) {
                boolean found = false;
                for (int mid = to; mid > p; mid--) {
                    int remainMin = Math.max(0, min - taken - 1);
                    int remainMax = max == ExprParser.UNBOUNDED ? ExprParser.UNBOUNDED : max - taken - 1;
                    if (exact(child, p, mid) && exactRepeat(child, remainMin, remainMax, mid, to)) {
                        reconstruct(child, p, mid);
                        p = mid;
                        taken++;
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    break;
                }
            }
        }
    }
}
