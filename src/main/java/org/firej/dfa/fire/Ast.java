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

import java.util.List;

/**
 * The syntax tree of a regular expression, over character <em>sets</em> rather
 * than characters.
 *
 * <p>There is no node for a shorthand, a named class or a negated class:
 * {@code \d}, {@code [[:digit:]]} and {@code [^a-z]} all arrive as a
 * {@link Chars} holding the set they denote. The dialect is decided in the
 * parser and does not survive into the tree, so everything downstream — the
 * automaton construction, the determiniser, the minimiser — sees one shape.
 */
public sealed interface Ast {

    /** Matches the empty string. */
    record Empty() implements Ast {
    }

    /** Matches one character drawn from a set. */
    record Chars(CharSet set) implements Ast {
    }

    /** One after another. */
    record Concat(List<Ast> parts) implements Ast {
    }

    /** One of several. */
    record Alt(List<Ast> parts) implements Ast {
    }

    /**
     * {@code node} repeated between {@code min} and {@code max} times, where
     * {@code max == UNBOUNDED} means no upper limit. A lazy or possessive suffix
     * in the source is dropped by the parser: it selects which match a
     * backtracking engine reports, and this engine reports the longest one
     * regardless, so it cannot change the language a {@code DFA} accepts.
     */
    record Repeat(Ast node, int min, int max) implements Ast {
        public static final int UNBOUNDED = -1;
    }

    /**
     * A parenthesised group. Kept as a node so the tree mirrors the source, which
     * matters only for diagnostics: the automaton has no notion of a group, and
     * capturing is recovered separately, after the span is fixed.
     */
    record Group(Ast node) implements Ast {
    }
}
