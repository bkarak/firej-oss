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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.firej.RegexCompilationException;
import org.firej.dfa.Preprocessor.Dialect;

/**
 * {@link Ast} to a minimal {@code DFA}: Thompson construction, subset
 * construction and partition refinement, all over character <em>ranges</em>.
 *
 * <p>Nothing here ever enumerates a character. An edge carries a
 * {@link CharSet}, the determiniser splits the alphabet at the boundaries the
 * automaton actually uses — the same idea as the class map the code generator
 * emits — and a class of thousands of code points costs exactly what a single
 * character costs.
 */
final class DfaCompiler {

    /** A bound on states, so a pathological pattern fails rather than hangs. */
    private static final int MAX_STATES = 100_000;
    /** A bound on the unrolling of {@code {n,m}}, for the same reason. */
    private static final int MAX_REPEAT = 10_000;

    private DfaCompiler() {
    }

    // --- the NFA ------------------------------------------------------------

    /** An edge on a set of characters, or an epsilon edge when {@code set} is null. */
    private record Edge(CharSet set, int to) {
    }

    private static final class Nfa {
        final List<List<Edge>> out = new ArrayList<>();

        int newState() {
            if (out.size() >= MAX_STATES) {
                throw new RegexCompilationException("Automaton too large: over " + MAX_STATES + " NFA states");
            }
            out.add(new ArrayList<>());
            return out.size() - 1;
        }

        void edge(int from, CharSet on, int to) {
            out.get(from).add(new Edge(on, to));
        }

        void epsilon(int from, int to) {
            out.get(from).add(new Edge(null, to));
        }
    }

    /** A sub-automaton with one entry and one exit. */
    private record Frag(int start, int end) {
    }

    private static Frag build(Nfa n, Ast node) {
        return switch (node) {
            case Ast.Empty ignored -> {
                int s = n.newState();
                int e = n.newState();
                n.epsilon(s, e);
                yield new Frag(s, e);
            }
            case Ast.Chars c -> {
                int s = n.newState();
                int e = n.newState();
                if (!c.set().isEmpty()) {
                    n.edge(s, c.set(), e);
                }
                yield new Frag(s, e);
            }
            case Ast.Group g -> build(n, g.node());
            case Ast.Concat cat -> {
                Frag first = null;
                int last = -1;
                for (Ast part : cat.parts()) {
                    Frag f = build(n, part);
                    if (first == null) {
                        first = f;
                    } else {
                        n.epsilon(last, f.start());
                    }
                    last = f.end();
                }
                yield first == null ? build(n, new Ast.Empty()) : new Frag(first.start(), last);
            }
            case Ast.Alt alt -> {
                int s = n.newState();
                int e = n.newState();
                for (Ast part : alt.parts()) {
                    Frag f = build(n, part);
                    n.epsilon(s, f.start());
                    n.epsilon(f.end(), e);
                }
                yield new Frag(s, e);
            }
            case Ast.Repeat r -> buildRepeat(n, r);
        };
    }

    private static Frag buildRepeat(Nfa n, Ast.Repeat r) {
        int min = r.min();
        int max = r.max();
        // Both bounds are unrolled, so both must be checked: {n,} has no max but
        // still builds n copies, and a{30000,} would grind rather than fail.
        if (min > MAX_REPEAT || (max != Ast.Repeat.UNBOUNDED && max > MAX_REPEAT)) {
            throw new RegexCompilationException("Repetition {" + min + ","
                    + (max == Ast.Repeat.UNBOUNDED ? "" : String.valueOf(max)) + "} is too large to unroll");
        }
        int s = n.newState();
        int e = n.newState();
        int cursor = s;

        for (int i = 0; i < min; i++) {
            Frag f = build(n, r.node());
            n.epsilon(cursor, f.start());
            cursor = f.end();
        }
        if (max == Ast.Repeat.UNBOUNDED) {
            Frag f = build(n, r.node());
            n.epsilon(cursor, f.start());
            n.epsilon(f.end(), f.start());     // the star
            n.epsilon(f.end(), e);
            n.epsilon(cursor, e);              // zero more
        } else {
            for (int i = min; i < max; i++) {
                Frag f = build(n, r.node());
                n.epsilon(cursor, f.start());
                n.epsilon(cursor, e);          // stop here
                cursor = f.end();
            }
            n.epsilon(cursor, e);
        }
        return new Frag(s, e);
    }

    // --- determinisation ----------------------------------------------------

    /**
     * The epsilon closure of one NFA state, memoised.
     *
     * <p>It used to be recomputed for every DFA <em>transition</em> — the call sat
     * before the dedup, so a closure was walked once per edge of the subset
     * construction rather than once per state — with a boxing deque each time.
     * Each state's closure is now computed at most once and reused.
     */
    private static BitSet closureOf(Nfa n, int state, BitSet[] cache) {
        BitSet cached = cache[state];
        if (cached != null) {
            return cached;
        }
        BitSet r = new BitSet();
        r.set(state);
        int[] stack = new int[16];
        int top = 0;
        stack[top++] = state;
        while (top > 0) {
            int s = stack[--top];
            for (Edge e : n.out.get(s)) {
                if (e.set() == null && !r.get(e.to())) {
                    r.set(e.to());
                    if (top == stack.length) {
                        stack = Arrays.copyOf(stack, top * 2);
                    }
                    stack[top++] = e.to();
                }
            }
        }
        cache[state] = r;
        return r;
    }

    /** Close a set of states under epsilon, through the per-state cache. */
    private static void close(Nfa n, BitSet set, BitSet[] cache) {
        BitSet seed = (BitSet) set.clone();
        for (int i = seed.nextSetBit(0); i >= 0; i = seed.nextSetBit(i + 1)) {
            set.or(closureOf(n, i, cache));
        }
    }

    /**
     * The cut points of every set on an edge leaving this state group, as a sorted
     * array with no duplicates. Splitting the alphabet only where the automaton
     * distinguishes it keeps the transition count proportional to the pattern, not
     * to the character space.
     */
    private static int[] cutsOf(List<Edge> edges) {
        int n = 0;
        for (Edge e : edges) {
            n += 2 * e.set().rangeCount();
        }
        int[] raw = new int[n];
        int i = 0;
        for (Edge e : edges) {
            CharSet s = e.set();
            for (int k = 0; k < s.rangeCount(); k++) {
                raw[i++] = s.lo(k);
                raw[i++] = s.hi(k) + 1 <= CharSet.MAX ? s.hi(k) + 1 : s.lo(k);
            }
        }
        Arrays.sort(raw);
        int m = 0;
        for (int k = 0; k < raw.length; k++) {
            if (k == 0 || raw[k] != raw[k - 1]) {
                raw[m++] = raw[k];
            }
        }
        return Arrays.copyOf(raw, m);
    }

    /** The index of the first cut point that is not below {@code v}. */
    private static int lowerBound(int[] cut, int len, int v) {
        int lo = 0;
        int hi = len;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cut[mid] < v) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** A deterministic automaton over ranges: {@code trans[state]} is sorted by {@code lo}. */
    record Det(int stateCount, int initial, boolean[] accept, List<List<int[]>> trans) {
        // each int[] is {lo, hi, destination}
    }

    private static Det determinise(Nfa n, Frag frag) {
        Map<BitSet, Integer> ids = new HashMap<>();
        List<BitSet> sets = new ArrayList<>();
        List<List<int[]>> trans = new ArrayList<>();
        Deque<Integer> work = new ArrayDeque<>();
        BitSet[] closures = new BitSet[n.out.size()];

        BitSet start = new BitSet();
        start.set(frag.start());
        close(n, start, closures);
        ids.put(start, 0);
        sets.add(start);
        trans.add(new ArrayList<>());
        work.add(0);

        List<Edge> edges = new ArrayList<>();
        while (!work.isEmpty()) {
            int id = work.poll();
            BitSet from = sets.get(id);

            edges.clear();
            for (int s = from.nextSetBit(0); s >= 0; s = from.nextSetBit(s + 1)) {
                for (Edge e : n.out.get(s)) {
                    if (e.set() != null && !e.set().isEmpty()) {
                        edges.add(e);
                    }
                }
            }
            if (edges.isEmpty()) {
                continue;
            }
            int[] cut = cutsOf(edges);

            // Scatter each edge into the cut slots its ranges cover, rather than
            // asking every edge about every slot. The old form tested
            // set.contains(lo) for every (slot x edge) pair, which is quadratic in
            // the edges of the closure; this is linear in the slots actually
            // covered, which is the size of the answer.
            BitSet[] slot = new BitSet[cut.length];
            for (Edge e : edges) {
                CharSet cs = e.set();
                for (int k = 0; k < cs.rangeCount(); k++) {
                    int lo = cs.lo(k);
                    int hi = cs.hi(k);
                    for (int a = lowerBound(cut, cut.length, lo); a < cut.length && cut[a] <= hi; a++) {
                        if (slot[a] == null) {
                            slot[a] = new BitSet();
                        }
                        slot[a].set(e.to());
                    }
                }
            }

            for (int a = 0; a < cut.length; a++) {
                if (slot[a] == null) {
                    continue;
                }
                int lo = cut[a];
                int hi = (a + 1 < cut.length ? cut[a + 1] : CharSet.MAX + 1) - 1;
                if (lo > hi) {
                    continue;
                }
                BitSet to = slot[a];
                close(n, to, closures);
                Integer dest = ids.get(to);
                if (dest == null) {
                    if (sets.size() >= MAX_STATES) {
                        throw new RegexCompilationException(
                                "Automaton too large: over " + MAX_STATES + " DFA states");
                    }
                    dest = sets.size();
                    ids.put(to, dest);
                    sets.add(to);
                    trans.add(new ArrayList<>());
                    work.add(dest);
                }
                trans.get(id).add(new int[] {lo, hi, dest});
            }
        }

        boolean[] accept = new boolean[sets.size()];
        for (int i = 0; i < sets.size(); i++) {
            accept[i] = sets.get(i).get(frag.end());
        }
        for (List<int[]> t : trans) {
            t.sort((a, b) -> Integer.compare(a[0], b[0]));
        }
        return new Det(sets.size(), 0, accept, trans);
    }

    // --- minimisation -------------------------------------------------------

    /**
     * The cut points of the whole transition relation: both endpoints of every
     * range, so that each interval between consecutive points is treated the same
     * way by every state. Symbol {@code a} stands for the interval starting at
     * {@code probes[a]}.
     */
    private static int[] cutPoints(Det d) {
        int n = 0;
        for (List<int[]> t : d.trans()) {
            n += 2 * t.size();
        }
        int[] raw = new int[n];
        int i = 0;
        for (List<int[]> t : d.trans()) {
            for (int[] e : t) {
                raw[i++] = e[0];
                raw[i++] = e[1] + 1 <= CharSet.MAX ? e[1] + 1 : e[0];
            }
        }
        Arrays.sort(raw);
        int m = 0;
        for (int k = 0; k < raw.length; k++) {
            if (k == 0 || raw[k] != raw[k - 1]) {
                raw[m++] = raw[k];
            }
        }
        return Arrays.copyOf(raw, m);
    }

    /** Beyond this many (state, symbol) pairs the dense table is not worth building. */
    private static final long MAX_TABLE = 32_000_000L;

    /**
     * Hopcroft's partition refinement.
     *
     * <p>The previous implementation was Moore's: refine every state against every
     * symbol, repeat until the partition stops changing. That is {@code O(S²·P·R)}
     * with a boxed signature per state per round, and the number of rounds is
     * {@code Θ(S)} for a chain — which is exactly the shape a bounded repetition
     * produces. One corpus pattern, a 25-range class under <code>{6,3000}</code>,
     * took 2.65 s against the Brics library's 314 ms.
     *
     * <p>Hopcroft is {@code O(T log S)}: it keeps a worklist of (block, symbol)
     * splitters and, for each, touches only the states that actually reach that
     * block on that symbol, through an inverse transition index. A block is split
     * at most {@code log S} times, and the smaller half is always the one queued.
     */
    private static Det minimise(Det d) {
        int s = d.stateCount();
        int[] probes = cutPoints(d);
        int p = probes.length;
        if (p == 0 || (long) (s + 1) * p > MAX_TABLE) {
            // Nothing to distinguish states by, or too large to index densely. The
            // automaton is already correct, only not minimal.
            return d;
        }

        // A sink makes the automaton complete, which Hopcroft needs. It is dropped
        // again when the result is rebuilt, unless the initial state reaches it.
        int sink = s;
        int m = s + 1;
        int[] delta = new int[m * p];
        Arrays.fill(delta, sink);
        for (int st = 0; st < s; st++) {
            List<int[]> ts = d.trans().get(st);
            int ti = 0;
            for (int a = 0; a < p; a++) {
                int lo = probes[a];
                while (ti < ts.size() && ts.get(ti)[1] < lo) {
                    ti++;
                }
                if (ti < ts.size() && ts.get(ti)[0] <= lo) {
                    delta[st * p + a] = ts.get(ti)[2];
                }
            }
        }

        // Inverse transitions, as one CSR array per symbol.
        int[][] invStart = new int[p][];
        int[][] invTo = new int[p][];
        for (int a = 0; a < p; a++) {
            int[] count = new int[m + 1];
            for (int st = 0; st < m; st++) {
                count[delta[st * p + a] + 1]++;
            }
            for (int k = 0; k < m; k++) {
                count[k + 1] += count[k];
            }
            int[] to = new int[m];
            int[] cursor = Arrays.copyOf(count, m + 1);
            for (int st = 0; st < m; st++) {
                to[cursor[delta[st * p + a]]++] = st;
            }
            invStart[a] = count;
            invTo[a] = to;
        }

        // Partition: block 0 rejecting (the sink joins it), block 1 accepting.
        int[] blockOf = new int[m];
        for (int st = 0; st < s; st++) {
            blockOf[st] = d.accept()[st] ? 1 : 0;
        }
        blockOf[sink] = 0;
        List<List<Integer>> members = new ArrayList<>();
        members.add(new ArrayList<>());
        members.add(new ArrayList<>());
        for (int st = 0; st < m; st++) {
            members.get(blockOf[st]).add(st);
        }
        if (members.get(1).isEmpty() || members.get(0).isEmpty()) {
            // One block only; nothing can ever split it.
            List<Integer> all = members.get(0).isEmpty() ? members.get(1) : members.get(0);
            members.clear();
            members.add(all);
            for (int st = 0; st < m; st++) {
                blockOf[st] = 0;
            }
        }

        // The worklist holds (block, symbol) splitters. Membership is tracked
        // because the "enqueue only the smaller half" rule -- the one that gives
        // Hopcroft its log factor -- is valid only when the block being split is
        // not already queued. When it is, the new half must be queued too;
        // enqueueing the smaller half unconditionally under-refines, and the
        // partition then merges states that are not equivalent (it turned
        // [0-9]{2}-?[0-9]{2} into a self-loop on the initial state).
        Deque<long[]> work = new ArrayDeque<>();
        boolean[] queued = new boolean[Math.max(2, members.size()) * p];
        for (int b = 0; b < members.size(); b++) {
            for (int a = 0; a < p; a++) {
                work.add(new long[] {b, a});
                queued[b * p + a] = true;
            }
        }

        int[] touchedCount = new int[members.size()];
        boolean[] reaches = new boolean[m];
        while (!work.isEmpty()) {
            long[] item = work.poll();
            int splitter = (int) item[0];
            int symbol = (int) item[1];
            queued[splitter * p + symbol] = false;

            // States that reach the splitter block on this symbol.
            List<Integer> reaching = new ArrayList<>();
            int[] invS = invStart[symbol];
            int[] invT = invTo[symbol];
            for (int t : members.get(splitter)) {
                for (int k = invS[t]; k < invS[t + 1]; k++) {
                    int from = invT[k];
                    if (!reaches[from]) {
                        reaches[from] = true;
                        reaching.add(from);
                    }
                }
            }
            if (reaching.isEmpty()) {
                continue;
            }

            if (touchedCount.length < members.size()) {
                touchedCount = Arrays.copyOf(touchedCount, members.size());
            }
            List<Integer> touchedBlocks = new ArrayList<>();
            for (int st : reaching) {
                int b = blockOf[st];
                if (touchedCount[b] == 0) {
                    touchedBlocks.add(b);
                }
                touchedCount[b]++;
            }

            for (int b : touchedBlocks) {
                int inCount = touchedCount[b];
                touchedCount[b] = 0;
                List<Integer> block = members.get(b);
                if (inCount == block.size()) {
                    continue;                        // the whole block reaches; no split
                }
                List<Integer> keep = new ArrayList<>(block.size() - inCount);
                List<Integer> move = new ArrayList<>(inCount);
                for (int st : block) {
                    if (reaches[st]) {
                        move.add(st);
                    } else {
                        keep.add(st);
                    }
                }
                int nb = members.size();
                members.set(b, keep);
                members.add(move);
                for (int st : move) {
                    blockOf[st] = nb;
                }
                if (touchedCount.length < members.size()) {
                    touchedCount = Arrays.copyOf(touchedCount, members.size());
                }
                if (queued.length < members.size() * p) {
                    queued = Arrays.copyOf(queued, Math.max(queued.length * 2, members.size() * p));
                }
                int smaller = keep.size() <= move.size() ? b : nb;
                for (int a = 0; a < p; a++) {
                    int target = queued[b * p + a] ? nb : smaller;
                    if (!queued[target * p + a]) {
                        queued[target * p + a] = true;
                        work.add(new long[] {target, a});
                    }
                }
            }
            for (int st : reaching) {
                reaches[st] = false;
            }
        }

        return rebuild(d, blockOf, members.size(), sink);
    }

    /** One state per block, numbered so the initial state stays 0. */
    private static Det rebuild(Det d, int[] blockOf, int blocks, int sink) {
        int s = d.stateCount();
        int sinkBlock = blockOf[sink];
        int[] remap = new int[blocks];
        Arrays.fill(remap, -1);
        int assigned = 0;
        remap[blockOf[d.initial()]] = assigned++;
        for (int st = 0; st < s; st++) {
            int b = blockOf[st];
            if (remap[b] < 0 && b != sinkBlock) {
                remap[b] = assigned++;
            }
        }

        boolean[] accept = new boolean[assigned];
        List<List<int[]>> trans = new ArrayList<>();
        for (int i = 0; i < assigned; i++) {
            trans.add(new ArrayList<>());
        }
        boolean[] filled = new boolean[assigned];
        for (int st = 0; st < s; st++) {
            int b = remap[blockOf[st]];
            if (b < 0) {
                continue;                           // a dead state merged with the sink
            }
            accept[b] |= d.accept()[st];
            if (filled[b]) {
                continue;
            }
            filled[b] = true;
            for (int[] e : d.trans().get(st)) {
                int dest = remap[blockOf[e[2]]];
                if (dest >= 0) {
                    trans.get(b).add(new int[] {e[0], e[1], dest});
                }
            }
        }
        for (List<int[]> t : trans) {
            t.sort((a, b) -> Integer.compare(a[0], b[0]));
        }
        return new Det(assigned, 0, accept, mergeAll(trans));
    }

    /** Ranges that touch and lead to the same state become one transition. */
    private static List<List<int[]>> mergeAll(List<List<int[]>> trans) {
        List<List<int[]>> out = new ArrayList<>(trans.size());
        for (List<int[]> t : trans) {
            List<int[]> merged = new ArrayList<>(t.size());
            for (int[] e : t) {
                if (!merged.isEmpty()) {
                    int[] last = merged.get(merged.size() - 1);
                    if (last[2] == e[2] && last[1] + 1 >= e[0]) {
                        last[1] = Math.max(last[1], e[1]);
                        continue;
                    }
                }
                merged.add(new int[] {e[0], e[1], e[2]});
            }
            out.add(merged);
        }
        return out;
    }

    // --- the whole pipeline --------------------------------------------------

    /** Parse, build, determinise and minimise. */
    static DFAFire compile(String pattern, Dialect dialect) {
        Ast ast = new RegexParser(pattern, dialect).parse();
        Nfa nfa = new Nfa();
        Frag frag = build(nfa, ast);
        Det det = minimise(determinise(nfa, frag));

        return new DFAFire(det.accept(), det.trans());
    }
}
