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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.firej.dfa.FlattenedDfa.Range;

/**
 * Renders a {@link FlattenedDfa} in the Graphviz DOT language, for
 * {@code dot -Tsvg} and friends.
 *
 * <p>States are named by their number; accepting states are double circles.
 * Edges to the same destination are merged into one, labelled with every
 * range on it. Printable ASCII characters are written as themselves, anything
 * else as {@code U+XXXX}. A leading {@code ^} labels the start arrow, a
 * trailing {@code $} the accepting states.
 */
final class DotRenderer {
    private DotRenderer() {
    }

    static String render(FlattenedDfa dfa) {
        StringBuilder out = new StringBuilder();
        out.append("digraph dfa {\n");
        out.append("\trankdir=LR;\n");
        if (!dfa.pattern().isEmpty()) {
            out.append("\tlabel=").append(quote(dfa.pattern())).append(";\n");
            out.append("\tlabelloc=t;\n");
        }
        out.append("\tnode [shape=circle];\n");
        out.append("\tstart [shape=point];\n");
        out.append("\tstart -> ").append(dfa.startState());
        if (dfa.anchoredStart()) {
            out.append(" [label=\"^\"]");
        }
        out.append(";\n");
        for (int s = 0; s < dfa.stateCount(); s++) {
            if (dfa.accept()[s]) {
                out.append('\t').append(s).append(" [shape=doublecircle");
                if (dfa.anchoredEnd()) {
                    out.append(",xlabel=\"$\"");
                }
                out.append("];\n");
            }
        }
        for (int s = 0; s < dfa.stateCount(); s++) {
            Map<Integer, List<String>> byDest = new LinkedHashMap<>();
            for (Range r : dfa.transitions()[s]) {
                byDest.computeIfAbsent(r.dest(), d -> new ArrayList<>()).add(range(r));
            }
            for (Map.Entry<Integer, List<String>> e : byDest.entrySet()) {
                out.append('\t').append(s).append(" -> ").append(e.getKey())
                        .append(" [label=").append(quote(String.join(", ", e.getValue()))).append("];\n");
            }
        }
        out.append("}\n");
        return out.toString();
    }

    private static String range(Range r) {
        return r.isSingle() ? character(r.min()) : character(r.min()) + "-" + character(r.max());
    }

    private static String character(int c) {
        return c > 0x20 && c < 0x7f ? String.valueOf((char) c) : "U+%04X".formatted(c);
    }

    /** A DOT string literal: only {@code "} and {@code \\} need escaping. */
    private static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                b.append('\\');
            }
            if (c < 0x20) {
                b.append("U+%04X".formatted((int) c));
            } else {
                b.append(c);
            }
        }
        return b.append('"').toString();
    }
}
