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
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.firej.RegexCompilationException;

/**
 * Translates the Perl / Java regular expression syntax that users write into
 * the Brics dialect the parser reads, in one left-to-right scan that tracks
 * escapes and character classes.
 *
 * <ul>
 *   <li>Shorthands {@code \d \D \s \S \w \W \p{...} \P{...}} become explicit
 *   character classes; a negated shorthand inside a class becomes its
 *   complement ranges, so {@code [a\D]} is a union as in Java.</li>
 *   <li>Escapes that name a character — {@code \t \n \r \f \a \e \xHH \x{H..}
 *   \0oo \cX}, the {@code u} escape with four hex digits, and {@code \b}
 *   inside a class — become that character. {@code \Q...\E} quotes.</li>
 *   <li>{@code # @ " < > ~ &}, which Brics treats as operators, are literals
 *   here as in every other dialect. {@code .} matches any character but
 *   newline, as in Perl. A lazy or possessive quantifier suffix is dropped:
 *   it does not change the language a DFA recognises.</li>
 *   <li>{@code (?:...)} and named groups become plain groups. Lookaround,
 *   inline flags, conditionals, word boundaries ({@code \b \B}),
 *   backreferences and {@code \G \k \h \v \R \X} throw
 *   {@link RegexCompilationException}: a DFA cannot express them, and a
 *   silent wrong answer is worse than none.</li>
 *   <li>A leading {@code ^} (or {@code \A}) and a trailing {@code $} (or
 *   {@code \z \Z}) become the flags of the result, enforced by the generated
 *   code. Anchors anywhere else, or beside a top-level {@code |} where they
 *   bind to one branch, throw.</li>
 * </ul>
 */
public final class BricsPreprocessor implements Preprocessor {
    private static final BricsPreprocessor INSTANCE = new BricsPreprocessor(Dialect.EXTENDED);
    private static final BricsPreprocessor POSIX_INSTANCE = new BricsPreprocessor(Dialect.POSIX);
    private static final int MAX_CHAR = 0xFFFF;
    /** Special to Brics outside a class, literal in Perl and Java. */
    private static final String BRICS_OPERATORS = "#@\"<>~&";


    /** Sorted, non-overlapping, inclusive ranges. */
    private record Ranges(int[][] spans) {
        Ranges complement() {
            List<int[]> out = new ArrayList<>();
            int next = 0;
            for (int[] span : spans) {
                if (span[0] > next) {
                    out.add(new int[] {next, span[0] - 1});
                }
                next = span[1] + 1;
            }
            if (next <= MAX_CHAR) {
                out.add(new int[] {next, MAX_CHAR});
            }
            return new Ranges(out.toArray(int[][]::new));
        }

        /** Class body in Brics syntax: every non-alphanumeric character escaped. */
        String render() {
            StringBuilder b = new StringBuilder();
            for (int[] span : spans) {
                appendLiteral(b, (char) span[0]);
                if (span[1] > span[0]) {
                    b.append('-');
                    appendLiteral(b, (char) span[1]);
                }
            }
            return b.toString();
        }

        static Ranges of(int... pairs) {
            int[][] spans = new int[pairs.length / 2][];
            for (int i = 0; i < spans.length; i++) {
                spans[i] = new int[] {pairs[2 * i], pairs[2 * i + 1]};
            }
            return new Ranges(spans);
        }
    }

    private final Map<Character, Ranges> shorthands = new HashMap<>();
    private final Map<String, Ranges> named = new HashMap<>();

    private final Dialect dialect;

    private BricsPreprocessor(Dialect dialect) {
        this.dialect = dialect;
        Ranges digit = Ranges.of('0', '9');
        Ranges space = Ranges.of('\t', '\r', ' ', ' ');
        Ranges word = Ranges.of('0', '9', 'A', 'Z', '_', '_', 'a', 'z');
        shorthands.put('d', digit);
        shorthands.put('D', digit.complement());
        shorthands.put('s', space);
        shorthands.put('S', space.complement());
        shorthands.put('w', word);
        shorthands.put('W', word.complement());

        named.put("Lower", Ranges.of('a', 'z'));
        named.put("Upper", Ranges.of('A', 'Z'));
        named.put("ASCII", Ranges.of(0x00, 0x7F));
        named.put("Alpha", Ranges.of('A', 'Z', 'a', 'z'));
        named.put("Digit", digit);
        named.put("Alnum", Ranges.of('0', '9', 'A', 'Z', 'a', 'z'));
        named.put("Punct", Ranges.of(0x21, 0x2F, 0x3A, 0x40, 0x5B, 0x60, 0x7B, 0x7E));
        named.put("Graph", Ranges.of(0x21, 0x7E));
        named.put("Print", Ranges.of(0x20, 0x7E));
        named.put("Blank", Ranges.of('\t', '\t', ' ', ' '));
        named.put("Cntrl", Ranges.of(0x00, 0x1F, 0x7F, 0x7F));
        named.put("XDigit", Ranges.of('0', '9', 'A', 'F', 'a', 'f'));
        named.put("Space", space);
    }

    public static BricsPreprocessor getInstance() {
        return INSTANCE;
    }

    /** The preprocessor for one dialect. */
    public static BricsPreprocessor getInstance(Dialect dialect) {
        return dialect == Dialect.POSIX ? POSIX_INSTANCE : INSTANCE;
    }

    @Override
    public Dialect dialect() {
        return dialect;
    }

    @Override
    public String name() {
        return "BRICS";
    }

    private RegexCompilationException notPosix(String what, String instead, String expr) {
        return new RegexCompilationException(
                what + " is not POSIX ERE (use " + instead + ") in: " + expr
                        + " -- compiled with Dialect.POSIX; Dialect.EXTENDED accepts it");
    }

    /** The rewritten expression alone; anchors are dropped. */
    public String processExpression(String expr) {
        return process(expr).expression();
    }

    @Override
    public Processed process(String expr) {
        int n = expr.length();
        StringBuilder out = new StringBuilder(n + 16);
        boolean inClass = false;
        boolean anchoredStart = false;
        boolean anchoredEnd = false;
        boolean topLevelAlternation = false;
        BitSet nonCapturing = new BitSet();
        int groups = 0;
        int depth = 0;
        int rangeEnd = -1;   // index of the character that closes the last class range
        int i = 0;
        if (expr.startsWith("^")) {
            anchoredStart = true;
            i = 1;
        } else if (expr.startsWith("\\A")) {
            anchoredStart = true;
            i = 2;
        }
        while (i < n) {
            char c = expr.charAt(i);
            if (c == '\\') {
                if (i + 1 >= n) {
                    throw new RegexCompilationException("Trailing backslash in: " + expr);
                }
                if (dialect == Dialect.POSIX) {
                    char nd = expr.charAt(i + 1);
                    if (shorthands.containsKey(nd)) {
                        throw notPosix("\\" + nd, "[[:digit:]], [[:alpha:]] and the other named classes", expr);
                    }
                    if (nd == 'p' || nd == 'P') {
                        throw notPosix("\\" + nd + "{...}", "[[:name:]]", expr);
                    }
                    if (nd == 'Q' || nd == 'E') {
                        throw notPosix("\\" + nd, "a backslash before each character", expr);
                    }
                    if (Character.isLetterOrDigit(nd)) {
                        throw notPosix("\\" + nd,
                                "the character itself; POSIX leaves a backslash before an ordinary character undefined",
                                expr);
                    }
                }
                Expansion e = expansionAt(expr, i);
                if (e != null) {
                    String body = e.ranges().render();
                    out.append(inClass ? body : "[" + body + "]");
                    i += e.length();
                    continue;
                }
                char d = expr.charAt(i + 1);
                if (!inClass && (d == 'z' || d == 'Z') && i + 2 == n) {
                    anchoredEnd = true;
                    i += 2;
                    continue;
                }
                if (!inClass && (d == 'A' || d == 'z' || d == 'Z')) {
                    throw anchor(expr, i);
                }
                if (d == 'b' && inClass) {
                    appendLiteral(out, '\b');
                    i += 2;
                    continue;
                }
                if ("bBGkhvRXK".indexOf(d) >= 0 || (d >= '1' && d <= '9')) {
                    throw unsupported("\\" + d, "word boundaries, backreferences and the like need an NFA", expr);
                }
                if (d == 'Q') {
                    int end = expr.indexOf("\\E", i + 2);
                    String quoted = end < 0 ? expr.substring(i + 2) : expr.substring(i + 2, end);
                    for (int k = 0; k < quoted.length(); k++) {
                        appendLiteral(out, quoted.charAt(k));
                    }
                    i = end < 0 ? n : end + 2;
                    continue;
                }
                if (d == 'E') {
                    i += 2;
                    continue;
                }
                int[] consumed = new int[1];
                int code = escapeCode(expr, i, consumed);
                if (code >= 0) {
                    appendLiteral(out, (char) code);
                    i += consumed[0];
                    continue;
                }
                out.append('\\').append(d);
                i += 2;
                continue;
            }
            if (inClass) {
                if (c == '[' && i + 1 < n && expr.charAt(i + 1) == ':') {
                    // Only a well-formed [:name:] is a POSIX class. "[:alpha]" -- no
                    // closing colon -- is six literal characters in Java and in PCRE,
                    // and the corpus contains one; treating it as a malformed class
                    // and throwing would reject a pattern every other engine accepts.
                    int close = expr.indexOf(":]", i + 2);
                    String name = close < 0 ? null : expr.substring(i + 2, close);
                    Ranges ranges = name != null && name.chars().allMatch(Character::isLetter)
                            ? posixNamed(name)
                            : null;
                    if (ranges != null) {
                        out.append(ranges.render());
                        i = close + 2;
                        continue;
                    }
                    // fall through: '[' is a literal inside a class
                }
                if (c == ']') {
                    inClass = false;
                } else if (i != rangeEnd && i + 2 < n && expr.charAt(i + 1) == '-') {
                    // In [A-Z-0] the '-' after a range is a literal, as in Java.
                    char hi = expr.charAt(i + 2);
                    if (hi != ']' && hi != '\\' && hi != '[') {
                        if (hi < c) {
                            throw new RegexCompilationException(
                                    "Character range " + c + "-" + hi + " is out of order in: " + expr);
                        }
                        rangeEnd = i + 2;
                    }
                }
                out.append(c);
                i++;
                continue;
            }
            switch (c) {
                case '[' -> {
                    inClass = true;
                    out.append('[');
                    i++;
                    if (i < n && expr.charAt(i) == '^') {
                        out.append('^');
                        i++;
                    }
                    if (i < n && expr.charAt(i) == ']') {
                        out.append("\\]");
                        i++;
                    }
                    continue;
                }
                case '(' -> {
                    depth++;
                    if (i + 1 < n && expr.charAt(i + 1) == '?') {
                        if (dialect == Dialect.POSIX) {
                            throw notPosix("(?...", "a plain group", expr);
                        }
                        if (expr.startsWith("(?:", i)) {
                            nonCapturing.set(groups++);
                            out.append('(');
                            i += 3;
                            continue;
                        }
                        boolean named = expr.startsWith("(?P<", i)
                                || (expr.startsWith("(?<", i) && i + 3 < n && expr.charAt(i + 3) != '=' && expr.charAt(i + 3) != '!');
                        if (named) {
                            int close = expr.indexOf('>', i);
                            if (close < 0) {
                                throw new RegexCompilationException("Unterminated group name in: " + expr);
                            }
                            groups++;
                            out.append('(');
                            i = close + 1;
                            continue;
                        }
                        if (expr.startsWith("(?#", i)) {
                            int close = expr.indexOf(')', i);
                            depth--;
                            i = close < 0 ? n : close + 1;
                            continue;
                        }
                        throw unsupported("(?", "lookaround, inline flags and conditionals need an NFA", expr);
                    }
                    groups++;
                    out.append('(');
                    i++;
                    continue;
                }
                case ')' -> depth--;
                case '|' -> {
                    if (depth == 0) {
                        topLevelAlternation = true;
                    }
                }
                case '^' -> throw anchor(expr, i);
                case '$' -> {
                    if (i == n - 1) {
                        anchoredEnd = true;
                        i++;
                        continue;
                    }
                    throw anchor(expr, i);
                }
                case '.' -> {
                    out.append("[^\\\n]");
                    i++;
                    continue;
                }
                case '*', '+', '?' -> {
                    requireOperand(out, c, expr);
                    out.append(c);
                    i++;
                    if (i < n && (expr.charAt(i) == '?' || expr.charAt(i) == '+')) {
                        i++; // lazy or possessive: same language for a DFA
                    }
                    continue;
                }
                case '{' -> {
                    java.util.regex.Matcher bounds = REPEAT.matcher(expr).region(i, n);
                    if (bounds.lookingAt() && !hasOperand(out)) {
                        // Nothing to repeat: Java and the native engine read the
                        // braces as text, and so does this.
                        for (int k = 0; k < bounds.group().length(); k++) {
                            appendLiteral(out, bounds.group().charAt(k));
                        }
                        i = bounds.end();
                        continue;
                    }
                    if (bounds.lookingAt()) {
                        int min = Integer.parseInt(bounds.group(1));
                        if (bounds.group(2) != null && !bounds.group(2).isEmpty()
                                && Integer.parseInt(bounds.group(2)) < min) {
                            throw new RegexCompilationException(
                                    "Repetition " + bounds.group() + " has its bounds out of order in: " + expr);
                        }
                    }
                }
                case '}' -> {
                    out.append(c);
                    i++;
                    if (i < n && (expr.charAt(i) == '?' || expr.charAt(i) == '+')) {
                        i++; // lazy or possessive: same language for a DFA
                    }
                    continue;
                }
                default -> {
                    if (BRICS_OPERATORS.indexOf(c) >= 0) {
                        out.append('\\');
                    }
                }
            }
            out.append(c);
            i++;
        }
        if ((anchoredStart || anchoredEnd) && topLevelAlternation) {
            throw new RegexCompilationException(
                    "An anchor next to a top-level '|' binds to one branch only, which FIRE/J cannot express: " + expr);
        }
        return new Processed(out.toString(), anchoredStart, anchoredEnd, nonCapturing);
    }

    /** Brics reads {@code \c} as the character {@code c}, whatever it is. */
    private static void appendLiteral(StringBuilder b, char c) {
        boolean plain = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
        if (!plain) {
            b.append('\\');
        }
        b.append(c);
    }

    /**
     * The character an escape at {@code i} names, with {@code consumed[0]} set
     * to the escape's length, or {@code -1} if it is not a character escape.
     */
    private static int escapeCode(String expr, int i, int[] consumed) {
        char d = expr.charAt(i + 1);
        consumed[0] = 2;
        switch (d) {
            case 't':
                return '\t';
            case 'n':
                return '\n';
            case 'r':
                return '\r';
            case 'f':
                return '\f';
            case 'a':
                return 0x07;
            case 'e':
                return 0x1B;
            case 'c':
                if (i + 2 < expr.length()) {
                    consumed[0] = 3;
                    return expr.charAt(i + 2) ^ 0x40;
                }
                return -1;
            case 'x': {
                if (i + 2 < expr.length() && expr.charAt(i + 2) == '{') {
                    int close = expr.indexOf('}', i + 3);
                    if (close < 0) {
                        throw new RegexCompilationException("Unterminated \\x{...} in: " + expr);
                    }
                    consumed[0] = close - i + 1;
                    return parseCode(expr.substring(i + 3, close), 16, expr);
                }
                if (i + 3 < expr.length()) {
                    consumed[0] = 4;
                    return parseCode(expr.substring(i + 2, i + 4), 16, expr);
                }
                throw new RegexCompilationException("Truncated \\x escape in: " + expr);
            }
            case 'u': {
                if (i + 2 < expr.length() && expr.charAt(i + 2) == '{') {
                    int close = expr.indexOf('}', i + 3);
                    if (close < 0) {
                        throw new RegexCompilationException("Unterminated \\u{...} in: " + expr);
                    }
                    consumed[0] = close - i + 1;
                    return parseCode(expr.substring(i + 3, close), 16, expr);
                }
                if (i + 5 < expr.length()) {
                    consumed[0] = 6;
                    return parseCode(expr.substring(i + 2, i + 6), 16, expr);
                }
                throw new RegexCompilationException("Truncated \\u escape in: " + expr);
            }
            case '0': {
                int end = i + 2;
                while (end < expr.length() && end < i + 5 && expr.charAt(end) >= '0' && expr.charAt(end) <= '7') {
                    end++;
                }
                consumed[0] = end - i;
                return end == i + 2 ? 0 : parseCode(expr.substring(i + 2, end), 8, expr);
            }
            default:
                return -1;
        }
    }

    private static int parseCode(String digits, int radix, String expr) {
        try {
            int code = Integer.parseInt(digits, radix);
            if (code < 0 || code > MAX_CHAR) {
                throw new RegexCompilationException(
                        "Character U+" + Integer.toHexString(code) + " is outside the BMP; FIRE/J matches UTF-16 chars (in: " + expr + ")");
            }
            return code;
        } catch (NumberFormatException e) {
            throw new RegexCompilationException("Bad character escape in: " + expr, e);
        }
    }

    /** {@code {n}}, {@code {n,}} or {@code {n,m}}: group 1 is n, group 2 m (empty if open). */
    private static final java.util.regex.Pattern REPEAT = java.util.regex.Pattern.compile("\\{(\\d+)(?:,(\\d*))?\\}");

    /**
     * A quantifier needs something before it to repeat. Brics reads one at the
     * start of the expression, of a group or of a branch as a literal, so
     * {@code *a} would silently match the text "*a"; Java and the native engine
     * reject it, and so does this. (A {@code {n}} there is the exception: all
     * three read it as text.)
     */
    private static void requireOperand(StringBuilder out, char quantifier, String expr) {
        if (!hasOperand(out)) {
            throw new RegexCompilationException("Quantifier '" + quantifier + "' has nothing to repeat in: " + expr);
        }
    }

    /** Whether the rewritten expression so far ends in something a quantifier can repeat. */
    private static boolean hasOperand(StringBuilder out) {
        int last = out.length() - 1;
        boolean operand = last >= 0;
        if (operand && (out.charAt(last) == '(' || out.charAt(last) == '|')) {
            int backslashes = 0;
            for (int k = last - 1; k >= 0 && out.charAt(k) == '\\'; k--) {
                backslashes++;
            }
            operand = backslashes % 2 == 1;
        }
        return operand;
    }

    private static RegexCompilationException anchor(String expr, int at) {
        return new RegexCompilationException(
                "Anchors are only supported as the first '^' or last '$' of the pattern (position "
                        + at + " in: " + expr + ")");
    }

    private static RegexCompilationException unsupported(String what, String why, String expr) {
        return new RegexCompilationException("Unsupported " + what + ": " + why + " (in: " + expr + ")");
    }

    private record Expansion(Ranges ranges, int length) {
    }

    /** The shorthand starting at the backslash at {@code i}, or {@code null}. */
    /** A POSIX [:name:] class; the names are the lowercase forms of the \p{} table. */
    private Ranges posixNamed(String name) {
        if (name.isEmpty()) {
            return null;
        }
        String key = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        Ranges r = named.get(key);
        if (r == null && name.equalsIgnoreCase("xdigit")) {
            r = named.get("XDigit");
        }
        if (r == null && name.equalsIgnoreCase("ascii")) {
            r = named.get("ASCII");
        }
        return r;
    }

    private Expansion expansionAt(String expr, int i) {
        char d = expr.charAt(i + 1);
        Ranges simple = shorthands.get(d);
        if (simple != null) {
            return new Expansion(simple, 2);
        }
        if (d == 'p' || d == 'P') {
            if (i + 2 >= expr.length() || expr.charAt(i + 2) != '{') {
                throw new RegexCompilationException("Expected '{' after \\" + d + " in: " + expr);
            }
            int close = expr.indexOf('}', i + 3);
            if (close < 0) {
                throw new RegexCompilationException("Unterminated \\" + d + "{...} in: " + expr);
            }
            String name = expr.substring(i + 3, close);
            Ranges ranges = named.get(name);
            if (ranges == null) {
                throw new RegexCompilationException("Unsupported character class \\" + d + "{" + name + "} in: " + expr);
            }
            return new Expansion(d == 'P' ? ranges.complement() : ranges, close - i + 1);
        }
        return null;
    }
}
