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

import java.util.BitSet;

import org.firej.RegexCompilationException;
import org.firej.dfa.Preprocessor;

/**
 * The preprocessor that does not preprocess.
 *
 * <p>It lifts the anchors and counts the groups, and hands the pattern on exactly
 * as it was written. Everything else the other front end does — expanding
 * {@code \d} into ranges, re-escaping those ranges into the automaton library's
 * syntax — exists only because that library cannot express a character class.
 * {@link RegexParser} can, so there is nothing to rewrite.
 *
 * <p>Anchors still have to come out. An automaton has no notion of position, so
 * {@code ^} and {@code $} cannot be part of the language it recognises; they
 * become flags on the template and the generated code enforces them.
 *
 * <p>The scan below is deliberately the <em>only</em> one in this package. It
 * replaces three hand-rolled string tests that each had their own idea of the
 * syntax: one counted backslashes but did not know about {@code \Q...\E}, so the
 * literal dollar in {@code \Qfoo$} was lifted into an anchor; another left a
 * character class at the first {@code ]}, so {@code []]} ended it one character
 * early. This one knows about escapes, quoted regions and classes, because
 * getting that wrong is how a pattern comes to mean something other than what it
 * says.
 */
public final class IdentityPreprocessor implements Preprocessor {

    private final Dialect dialect;

    public IdentityPreprocessor() {
        this(Dialect.EXTENDED);
    }

    public IdentityPreprocessor(Dialect dialect) {
        this.dialect = dialect;
    }

    @Override
    public Dialect dialect() {
        return dialect;
    }

    @Override
    public String name() {
        return "IDENTITY";
    }

    @Override
    public Processed process(String regex) {
        int n = regex.length();
        int begin = 0;
        boolean start = false;
        if (regex.startsWith("^")) {
            start = true;
            begin = 1;
        } else if (regex.startsWith("\\A")) {
            start = true;
            begin = 2;
        }

        BitSet nonCapturing = new BitSet();
        boolean topLevelAlternation = false;
        int groups = 0;
        int depth = 0;
        boolean inClass = false;
        int dollarAtEnd = -1;              // index of a '$' that is structurally last
        int trailingEscape = 0;            // 2 when the pattern ends with \z or \Z

        for (int i = begin; i < n; i++) {
            char c = regex.charAt(i);

            if (c == '\\') {
                if (i + 1 >= n) {
                    throw new RegexCompilationException("Trailing backslash in: " + regex);
                }
                char d = regex.charAt(i + 1);
                if (d == 'Q') {
                    int close = regex.indexOf("\\E", i + 2);
                    i = close < 0 ? n - 1 : close + 1;
                    continue;
                }
                if (!inClass && (d == 'z' || d == 'Z') && i + 2 == n) {
                    trailingEscape = 2;
                    break;
                }
                i++;                        // an escaped character is never structural
                continue;
            }

            if (inClass) {
                // A ']' straight after '[' or '[^' is a literal, not the end.
                if (c == ']') {
                    inClass = false;
                }
                continue;
            }

            switch (c) {
                case '[' -> {
                    inClass = true;
                    int j = i + 1;
                    if (j < n && regex.charAt(j) == '^') {
                        j++;
                    }
                    if (j < n && regex.charAt(j) == ']') {
                        j++;                // the literal ']' case
                    }
                    i = j - 1;
                }
                case '(' -> {
                    depth++;
                    if (regex.startsWith("(?:", i)) {
                        nonCapturing.set(groups++);
                        i += 2;
                    } else if (regex.startsWith("(?P<", i)
                            || (regex.startsWith("(?<", i) && i + 3 < n
                                    && regex.charAt(i + 3) != '=' && regex.charAt(i + 3) != '!')) {
                        int close = regex.indexOf('>', i);
                        if (close < 0) {
                            throw new RegexCompilationException("Unterminated group name in: " + regex);
                        }
                        groups++;
                        i = close;
                    } else if (i + 1 < n && regex.charAt(i + 1) == '?') {
                        // lookaround, inline flags, conditionals: RegexParser reports these
                        depth--;
                    } else {
                        groups++;
                    }
                }
                case ')' -> depth--;
                case '|' -> {
                    if (depth == 0) {
                        topLevelAlternation = true;
                    }
                }
                case '$' -> dollarAtEnd = i == n - 1 ? i : -1;
                default -> {
                }
            }
        }

        boolean end = trailingEscape > 0 || dollarAtEnd >= 0;
        String expression = regex.substring(begin, n - (trailingEscape > 0 ? 2 : dollarAtEnd >= 0 ? 1 : 0));

        // Either anchor binds to a single branch of a top-level alternation, which a
        // DFA cannot express: in a|b$ the $ applies to b alone.
        if ((start || end) && topLevelAlternation) {
            throw new RegexCompilationException(
                    "An anchor beside a top-level '|' binds to one branch only, which a DFA cannot express: " + regex);
        }
        return new Processed(expression, start, end, nonCapturing);
    }
}
