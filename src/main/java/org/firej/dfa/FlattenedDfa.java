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
package org.firej.dfa;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dense, generator-friendly snapshot of a {@link DFA}: states numbered {@code 0..n-1},
 * transitions sorted by range.
 *
 * <p>States are numbered in depth-first preorder from the initial state,
 * following transitions in range order, so that consecutive numbers follow
 * the paths a walk takes: the generated code lays its blocks out in that
 * order, a split walk's segments then hold whole runs of a path, and the
 * interpreter's tables stay local. Unreachable states, if any, come last.
 *
 * <p>{@code anchoredStart} means the pattern began with {@code ^}: a match may
 * only start at offset 0. {@code anchoredEnd} means it ended with {@code $}: the
 * match must consume the input to its end. The DFA is longest-match, so if the
 * longest match stops short of the end no accepted prefix reaches it, and the
 * generators simply turn such a result into {@code -1}.
 */
public record FlattenedDfa(String pattern, int startState, boolean[] accept, Range[][] transitions,
        boolean anchoredStart, boolean anchoredEnd) {

    public record Range(int min, int max, int dest) {
        public boolean isSingle() {
            return min == max;
        }
    }

    public int stateCount() {
        return accept.length;
    }

    public static FlattenedDfa from(String pattern, DFA dfa) {
        return from(pattern, dfa, false, false);
    }

    public static FlattenedDfa from(String pattern, DFA dfa, boolean anchoredStart, boolean anchoredEnd) {
        State initial = dfa.getInitialState();
        if (initial == null) {
            throw new IllegalArgumentException("DFA has no initial state");
        }

        List<State> all = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (State s : dfa.getStates()) {
            if (seen.add(s.getStateNumber())) {
                all.add(s);
            }
        }
        if (seen.add(initial.getStateNumber())) {
            all.addFirst(initial);
        }
        if (all.isEmpty()) {
            all.add(initial);
        }

        all.sort(Comparator.comparingInt(State::getStateNumber));

        Map<Integer, Integer> remap = new HashMap<>();
        for (int i = 0; i < all.size(); i++) {
            remap.put(all.get(i).getStateNumber(), i);
        }

        boolean[] accept = new boolean[all.size()];
        Range[][] transitions = new Range[all.size()][];
        for (int i = 0; i < all.size(); i++) {
            State s = all.get(i);
            accept[i] = s.isAccept();
            Transition[] ts = s.getTransitions();
            List<Range> ranges = new ArrayList<>(ts.length);
            for (Transition t : ts) {
                Integer dest = remap.get(t.getDest().getStateNumber());
                if (dest == null) {
                    continue;
                }
                ranges.add(new Range(t.getMinAsInt(), t.getMaxAsInt(), dest));
            }
            ranges.sort(Comparator.comparingInt(Range::min).thenComparingInt(Range::max));
            transitions[i] = ranges.toArray(Range[]::new);
        }

        Integer start = remap.get(initial.getStateNumber());
        if (start == null) {
            throw new IllegalArgumentException("Initial state is not in the DFA state set");
        }
        return renumber(pattern, start, accept, transitions, anchoredStart, anchoredEnd);
    }

    /** Renumbers the states in depth-first preorder from {@code start}. */
    private static FlattenedDfa renumber(String pattern, int start, boolean[] accept, Range[][] transitions,
            boolean anchoredStart, boolean anchoredEnd) {
        int n = accept.length;
        int[] order = new int[n];
        int[] rank = new int[n];
        Arrays.fill(rank, -1);
        int count = 0;
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            int s = stack.pop();
            if (rank[s] >= 0) {
                continue;
            }
            rank[s] = count;
            order[count++] = s;
            Range[] edges = transitions[s];
            for (int e = edges.length - 1; e >= 0; e--) {
                if (rank[edges[e].dest()] < 0) {
                    stack.push(edges[e].dest());
                }
            }
        }
        for (int s = 0; s < n; s++) {
            if (rank[s] < 0) {
                rank[s] = count;
                order[count++] = s;
            }
        }
        boolean[] newAccept = new boolean[n];
        Range[][] newTransitions = new Range[n][];
        for (int i = 0; i < n; i++) {
            int old = order[i];
            newAccept[i] = accept[old];
            Range[] edges = transitions[old];
            Range[] moved = new Range[edges.length];
            for (int e = 0; e < edges.length; e++) {
                moved[e] = new Range(edges[e].min(), edges[e].max(), rank[edges[e].dest()]);
            }
            newTransitions[i] = moved;
        }
        return new FlattenedDfa(pattern, rank[start], newAccept, newTransitions, anchoredStart, anchoredEnd);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FlattenedDfa other)) {
            return false;
        }
        return startState == other.startState
                && anchoredStart == other.anchoredStart
                && anchoredEnd == other.anchoredEnd
                && pattern.equals(other.pattern)
                && Arrays.equals(accept, other.accept)
                && Arrays.deepEquals(transitions, other.transitions);
    }

    @Override
    public int hashCode() {
        int result = pattern.hashCode();
        result = 31 * result + startState;
        result = 31 * result + Boolean.hashCode(anchoredStart);
        result = 31 * result + Boolean.hashCode(anchoredEnd);
        result = 31 * result + Arrays.hashCode(accept);
        result = 31 * result + Arrays.deepHashCode(transitions);
        return result;
    }
}
