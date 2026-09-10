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
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.firej.tools;

/**
 * Ehrenfeucht and Zeiger's two complexity measures of a regular expression
 * (STOC 1974), the ones the paper plots its Figures 2–5 against:
 *
 * <ul>
 *   <li><b>Size</b> — the number of alphabetical symbols in the expression: a
 *   literal, a character class, {@code .} or a class escape counts one;
 *   operators, groups, anchors and quantifiers count nothing.</li>
 *   <li><b>Length</b> — the length of the longest non-repeating path:
 *   concatenation adds, alternation takes the longest branch, {@code * + ?}
 *   contribute their operand once, and a counted repetition {@code {n,m}}
 *   contributes {@code m} (or {@code n} when unbounded) copies.</li>
 * </ul>
 *
 * <p>The parser covers the Perl/Java syntax the corpora use; anything it does
 * not understand is counted as a single symbol, so the numbers are always
 * defined. They describe the expression as written, not the automaton — the
 * DFA's own measures are in {@link SizeBenchmark.Shape}.
 */
public final class ExpressionMetrics {
    public record Complexity(int size, int length) {
    }

    private final String src;
    private int pos;

    private ExpressionMetrics(String src) {
        this.src = src;
    }

    public static Complexity of(String pattern) {
        ExpressionMetrics m = new ExpressionMetrics(pattern);
        try {
            return m.alternation();
        } catch (RuntimeException e) {
            return new Complexity(Math.max(1, pattern.length() / 2), Math.max(1, pattern.length() / 2));
        }
    }

    private Complexity alternation() {
        Complexity best = concatenation();
        int size = best.size();
        int length = best.length();
        while (peek('|')) {
            pos++;
            Complexity c = concatenation();
            size += c.size();
            length = Math.max(length, c.length());
        }
        return new Complexity(size, length);
    }

    private Complexity concatenation() {
        int size = 0;
        int length = 0;
        while (pos < src.length() && !peek('|') && !peek(')')) {
            Complexity atom = atom();
            int reps = quantifier();
            size += atom.size();
            length += atom.length() * reps;
        }
        return new Complexity(size, length);
    }

    /** Number of copies of the atom on the longest non-repeating path. */
    private int quantifier() {
        int reps = 1;
        if (pos >= src.length()) {
            return reps;
        }
        char c = src.charAt(pos);
        if (c == '*' || c == '+' || c == '?') {
            pos++;
        } else if (c == '{') {
            int close = src.indexOf('}', pos);
            String body = close < 0 ? "" : src.substring(pos + 1, close);
            if (close > 0 && body.matches("\\d+(,\\d*)?")) {
                pos = close + 1;
                int comma = body.indexOf(',');
                if (comma < 0) {
                    reps = Integer.parseInt(body);
                } else if (comma == body.length() - 1) {
                    reps = Math.max(1, Integer.parseInt(body.substring(0, comma)));
                } else {
                    reps = Integer.parseInt(body.substring(comma + 1));
                }
            } else {
                return reps; // a literal brace, consumed by the caller as an atom next time round
            }
        } else {
            return reps;
        }
        // lazy / possessive suffix
        if (pos < src.length() && (src.charAt(pos) == '?' || src.charAt(pos) == '+')) {
            pos++;
        }
        return reps;
    }

    private Complexity atom() {
        char c = src.charAt(pos);
        switch (c) {
            case '(' -> {
                pos++;
                if (peek('?')) {
                    pos++;
                    // "(?i)" contributes nothing; "(?i:", "(?:", "(?=", "(?!", "(?<=", "(?<!", "(?<name>", "(?P<name>"
                    int flagsEnd = pos;
                    while (flagsEnd < src.length() && "imsxuUdJ-".indexOf(src.charAt(flagsEnd)) >= 0) {
                        flagsEnd++;
                    }
                    if (flagsEnd < src.length() && src.charAt(flagsEnd) == ')') {
                        pos = flagsEnd + 1;
                        return new Complexity(0, 0);
                    }
                    if (flagsEnd < src.length() && src.charAt(flagsEnd) == ':') {
                        pos = flagsEnd + 1;
                    } else if (peek('<') && !peekAt(1, '=') && !peekAt(1, '!')) {
                        pos = src.indexOf('>', pos) + 1;
                    } else if (peek('P') && peekAt(1, '<')) {
                        pos = src.indexOf('>', pos) + 1;
                    } else if (peek('=') || peek('!')) {
                        pos++;
                    } else if (peek('<')) {
                        pos += 2;
                    } else if (peek('#')) {
                        pos = src.indexOf(')', pos) + 1; // comment
                        return new Complexity(0, 0);
                    }
                }
                Complexity inner = alternation();
                if (!peek(')')) {
                    throw new IllegalStateException("unbalanced (");
                }
                pos++;
                return inner;
            }
            case '[' -> {
                pos++;
                if (peek('^')) {
                    pos++;
                }
                if (peek(']')) {
                    pos++;
                }
                while (pos < src.length() && src.charAt(pos) != ']') {
                    if (src.charAt(pos) == '\\') {
                        pos++;
                    } else if (src.charAt(pos) == '[' && peekAt(1, ':')) {
                        int end = src.indexOf(":]", pos);
                        pos = end < 0 ? pos : end + 1;
                    }
                    pos++;
                }
                pos++;
                return new Complexity(1, 1);
            }
            case '\\' -> {
                pos++;
                if (pos >= src.length()) {
                    return new Complexity(1, 1);
                }
                char e = src.charAt(pos++);
                if ("bBAzZG".indexOf(e) >= 0) {
                    return new Complexity(0, 0);
                }
                if (e == 'Q') {
                    int end = src.indexOf("\\E", pos);
                    String quoted = end < 0 ? src.substring(pos) : src.substring(pos, end);
                    pos = end < 0 ? src.length() : end + 2;
                    return new Complexity(quoted.length(), quoted.length());
                }
                if ((e == 'p' || e == 'P' || e == 'x' || e == 'N') && peek('{')) {
                    pos = src.indexOf('}', pos) + 1;
                } else if (e == 'x') {
                    pos = Math.min(src.length(), pos + 2);
                } else if (e == 'u') {
                    pos = Math.min(src.length(), pos + 4);
                }
                return new Complexity(1, 1);
            }
            case '^', '$' -> {
                pos++;
                return new Complexity(0, 0);
            }
            default -> {
                pos++;
                return new Complexity(1, 1);
            }
        }
    }

    private boolean peek(char c) {
        return pos < src.length() && src.charAt(pos) == c;
    }

    private boolean peekAt(int offset, char c) {
        return pos + offset < src.length() && src.charAt(pos + offset) == c;
    }
}
