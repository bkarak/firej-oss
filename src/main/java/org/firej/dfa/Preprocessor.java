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

import java.util.BitSet;

/**
 * The first stage of the pipeline: what a pattern is turned into before an
 * automaton is built from it.
 *
 * <p>A configured engine is three independent choices — <em>preprocessor, DFA
 * engine, code generator</em> — and this is the first. Two exist:
 *
 * <ul>
 * <li>{@link BricsPreprocessor} rewrites the pattern into the dialect the
 *     dk.brics.automaton parser accepts, because that parser cannot express a
 *     character class: {@code \d} becomes explicit ranges and those ranges are
 *     re-escaped into its syntax. It is required by the {@code AUTOMATON} engine
 *     and pointless without it.</li>
 * <li>{@link org.firej.dfa.fire.IdentityPreprocessor} rewrites nothing. It lifts
 *     the anchors, which are flags on the template rather than part of the
 *     language, and passes the pattern through untouched. It is what the native
 *     engine wants, because that engine reads the dialect itself.</li>
 * </ul>
 *
 * <p>The rewriting stage is where every dialect defect found on 2026-09-06 lived
 * — a {@code \t} that became the letter t, a {@code \b} that became the letter b
 * — which is the case for being able to leave it out.
 */
public interface Preprocessor {

    /**
     * Which spelling of a regular expression the front end accepts.
     *
     * <p>Both dialects compile to the same automaton and match with the same
     * leftmost-longest semantics; they differ only in what a programmer is
     * allowed to write.
     */
    enum Dialect {
        /**
         * POSIX ERE with a short list of conveniences: the shorthands
         * {@code \d \w \s} and their negations, {@code \p{Name}}, the escapes
         * that name a character ({@code \t \n \xHH} and the rest),
         * {@code \Q...\E}, and non-capturing and named groups. This is the
         * historical FIRE/J dialect. It is deliberately <em>not</em> PCRE: there
         * is no lookaround, there are no backreferences, no word boundaries and
         * no inline flags, and adding them would need an NFA.
         */
        EXTENDED,
        /**
         * POSIX ERE and nothing else. {@code [[:digit:]]} is the way to say
         * "a digit"; {@code \d} is rejected rather than quietly taken as the
         * letter d, which is what a POSIX library does with it. Use this to
         * measure the engine against other POSIX engines on equal terms.
         */
        POSIX
    }

    /**
     * The expression to build an automaton from, the anchors that were lifted out
     * of it, and which of its groups — counted by the order of their {@code (} —
     * do not capture.
     *
     * <p>An automaton has no notion of position, so an anchor cannot be part of
     * the language it recognises; the generated code enforces the two flags
     * instead.
     */
    record Processed(String expression, boolean anchoredStart, boolean anchoredEnd, BitSet nonCapturing) {
    }

    /** Lift the anchors, and rewrite the expression if this stage rewrites at all. */
    Processed process(String regex);

    /** The dialect this front end accepts. */
    Dialect dialect();

    /** A short name, for diagnostics. */
    String name();
}
