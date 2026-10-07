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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.firej.RegexCompilationException;
import org.firej.dfa.Preprocessor.Dialect;

/**
 * A recursive-descent parser from pattern text to {@link Ast}, reading the
 * dialect directly.
 *
 * <p>Grammar, loosest binding first:
 *
 * <pre>
 * alternation := concat ('|' concat)*
 * concat      := repeat*
 * repeat      := atom ('*' | '+' | '?' | '{n,m}')* ('?' | '+')?
 * atom        := '(' alternation ')' | '[' class ']' | '.' | escape | literal
 * </pre>
 *
 * <p>A character class is parsed into a {@link CharSet} here, which is the point
 * of this backend: {@code \d}, {@code [[:digit:]]}, {@code [a-f0-9]} and
 * {@code [^\s]} all become a set, and no rewriting of the pattern text happens
 * anywhere. Anchors are handled by the caller and are rejected here if they
 * appear where the caller could not lift them.
 */
final class RegexParser {

    private final String src;
    private final Dialect dialect;
    private int pos;
    private int groups;

    private static final Map<Character, CharSet> SHORTHANDS = new HashMap<>();
    private static final Map<String, CharSet> NAMED = new HashMap<>();

    static {
        CharSet digit = CharSet.range('0', '9');
        CharSet space = CharSet.ranges('\t', '\r', ' ', ' ');
        CharSet word = CharSet.ranges('0', '9', 'A', 'Z', '_', '_', 'a', 'z');
        SHORTHANDS.put('d', digit);
        SHORTHANDS.put('D', digit.complement());
        SHORTHANDS.put('s', space);
        SHORTHANDS.put('S', space.complement());
        SHORTHANDS.put('w', word);
        SHORTHANDS.put('W', word.complement());

        NAMED.put("lower", CharSet.range('a', 'z'));
        NAMED.put("upper", CharSet.range('A', 'Z'));
        NAMED.put("ascii", CharSet.range(0x00, 0x7F));
        NAMED.put("alpha", CharSet.ranges('A', 'Z', 'a', 'z'));
        NAMED.put("digit", digit);
        NAMED.put("alnum", CharSet.ranges('0', '9', 'A', 'Z', 'a', 'z'));
        NAMED.put("punct", CharSet.ranges(0x21, 0x2F, 0x3A, 0x40, 0x5B, 0x60, 0x7B, 0x7E));
        NAMED.put("graph", CharSet.range(0x21, 0x7E));
        NAMED.put("print", CharSet.range(0x20, 0x7E));
        NAMED.put("blank", CharSet.ranges('\t', '\t', ' ', ' '));
        NAMED.put("cntrl", CharSet.ranges(0x00, 0x1F, 0x7F, 0x7F));
        NAMED.put("xdigit", CharSet.ranges('0', '9', 'A', 'F', 'a', 'f'));
        NAMED.put("space", space);
        NAMED.put("word", word);
    }

    RegexParser(String src, Dialect dialect) {
        this.src = src;
        this.dialect = dialect;
    }

    /** The syntax tree. */
    Ast parse() {
        Ast a = alternation();
        if (pos < src.length()) {
            throw err("Unexpected '" + src.charAt(pos) + "' at offset " + pos);
        }
        return a;
    }


    private RegexCompilationException err(String msg) {
        return new RegexCompilationException(msg + " in: " + src);
    }

    private RegexCompilationException notPosix(String what, String instead) {
        return new RegexCompilationException(what + " is not POSIX ERE (use " + instead + ") in: " + src
                + " -- compiled with Dialect.POSIX; Dialect.EXTENDED accepts it");
    }

    private boolean eof() {
        return pos >= src.length();
    }

    private char peek() {
        return src.charAt(pos);
    }

    // --- grammar ------------------------------------------------------------

    private Ast alternation() {
        List<Ast> parts = new ArrayList<>();
        parts.add(concat());
        while (!eof() && peek() == '|') {
            pos++;
            parts.add(concat());
        }
        return parts.size() == 1 ? parts.get(0) : new Ast.Alt(parts);
    }

    private Ast concat() {
        List<Ast> parts = new ArrayList<>();
        while (!eof() && peek() != '|' && peek() != ')') {
            parts.add(repeat());
        }
        return switch (parts.size()) {
            case 0 -> new Ast.Empty();
            case 1 -> parts.get(0);
            default -> new Ast.Concat(parts);
        };
    }

    private Ast repeat() {
        Ast a = atom();
        while (!eof()) {
            char c = peek();
            int min;
            int max;
            if (c == '*') {
                min = 0;
                max = Ast.Repeat.UNBOUNDED;
                pos++;
            } else if (c == '+') {
                min = 1;
                max = Ast.Repeat.UNBOUNDED;
                pos++;
            } else if (c == '?') {
                min = 0;
                max = 1;
                pos++;
            } else if (c == '{' && boundedAhead()) {
                pos++;
                int lo = number();
                int hi;
                if (!eof() && peek() == ',') {
                    pos++;
                    hi = (!eof() && peek() == '}') ? Ast.Repeat.UNBOUNDED : number();
                } else {
                    hi = lo;
                }
                if (eof() || peek() != '}') {
                    throw err("Unterminated {n,m}");
                }
                pos++;
                if (hi != Ast.Repeat.UNBOUNDED && hi < lo) {
                    throw err("Repetition {" + lo + "," + hi + "} counts down");
                }
                min = lo;
                max = hi;
            } else {
                break;
            }
            // A lazy or possessive suffix picks which match a backtracker reports;
            // this engine reports the longest, so the suffix cannot change the
            // language and is dropped.
            if (!eof() && (peek() == '?' || peek() == '+')) {
                pos++;
            }
            a = new Ast.Repeat(a, min, max);
        }
        return a;
    }

    /** True when '{' really opens a bound, rather than being a literal brace. */
    private boolean boundedAhead() {
        int i = pos + 1;
        if (i >= src.length() || !Character.isDigit(src.charAt(i))) {
            return false;
        }
        while (i < src.length() && Character.isDigit(src.charAt(i))) {
            i++;
        }
        if (i < src.length() && src.charAt(i) == ',') {
            i++;
            while (i < src.length() && Character.isDigit(src.charAt(i))) {
                i++;
            }
        }
        return i < src.length() && src.charAt(i) == '}';
    }

    private int number() {
        int start = pos;
        while (!eof() && Character.isDigit(peek())) {
            pos++;
        }
        if (start == pos) {
            throw err("Expected a number in {n,m}");
        }
        try {
            return Integer.parseInt(src.substring(start, pos));
        } catch (NumberFormatException e) {
            throw err("Repetition count is too large");
        }
    }

    private Ast atom() {
        char c = peek();
        switch (c) {
            case '(' -> {
                pos++;
                if (!eof() && peek() == '?') {
                    return groupExtension();
                }
                groups++;
                Ast inner = alternation();
                expect(')');
                return new Ast.Group(inner);
            }
            case '[' -> {
                pos++;
                return new Ast.Chars(classBody());
            }
            case '.' -> {
                pos++;
                // '.' is every character but a newline, as Perl, PCRE, Go and Rust
                // read it. A POSIX library lets it match a newline; matching the
                // majority is the less surprising choice and is documented.
                return new Ast.Chars(CharSet.of('\n').complement());
            }
            case '\\' -> {
                return escapeAtom();
            }
            case '^', '$' -> throw err("Anchor '" + c + "' is only supported at the ends of a pattern");
            case '*', '+', '?' -> throw err("Quantifier '" + c + "' with nothing to repeat");
            case ')' -> throw err("Unbalanced ')'");
            default -> {
                pos++;
                return new Ast.Chars(CharSet.of(c));
            }
        }
    }

    private Ast groupExtension() {
        if (dialect == Dialect.POSIX) {
            throw notPosix("(?...", "a plain group");
        }
        if (src.startsWith("?:", pos)) {
            pos += 2;
            Ast inner = alternation();
            expect(')');
            return new Ast.Group(inner);
        }
        if (src.startsWith("?#", pos)) {
            int close = src.indexOf(')', pos);
            if (close < 0) {
                throw err("Unterminated (?# comment");
            }
            pos = close + 1;
            return new Ast.Empty();
        }
        boolean named = src.startsWith("?P<", pos)
                || (src.startsWith("?<", pos) && pos + 2 < src.length()
                        && src.charAt(pos + 2) != '=' && src.charAt(pos + 2) != '!');
        if (named) {
            int close = src.indexOf('>', pos);
            if (close < 0) {
                throw err("Unterminated group name");
            }
            pos = close + 1;
            groups++;
            Ast inner = alternation();
            expect(')');
            return new Ast.Group(inner);
        }
        throw err("Lookaround, inline flags and conditionals need an NFA");
    }

    private void expect(char c) {
        if (eof() || peek() != c) {
            throw err("Expected '" + c + "'");
        }
        pos++;
    }

    // --- character classes ---------------------------------------------------

    /** Everything between '[' and its ']', as a set. */
    private CharSet classBody() {
        boolean negated = false;
        if (!eof() && peek() == '^') {
            negated = true;
            pos++;
        }
        CharSet set = CharSet.EMPTY;
        boolean first = true;
        while (true) {
            if (eof()) {
                throw err("Unterminated character class");
            }
            char c = peek();
            if (c == ']' && !first) {
                pos++;
                break;
            }
            first = false;

            // A shorthand inside a class is a member of the union, not an endpoint:
            // [\s.-] is space or dot or dash. Only a range endpoint is forbidden,
            // which classChar() enforces.
            if (c == '\\' && pos + 1 < src.length() && SHORTHANDS.containsKey(src.charAt(pos + 1))) {
                char d = src.charAt(pos + 1);
                if (dialect == Dialect.POSIX) {
                    throw notPosix("\\" + d, "[[:digit:]] and the other named classes");
                }
                set = set.union(SHORTHANDS.get(d));
                pos += 2;
                continue;
            }
            if (c == '\\' && pos + 1 < src.length()
                    && (src.charAt(pos + 1) == 'p' || src.charAt(pos + 1) == 'P')) {
                Ast inner = escapeAtom();
                set = set.union(((Ast.Chars) inner).set());
                continue;
            }
            if (c == '[' && pos + 1 < src.length() && src.charAt(pos + 1) == ':') {
                CharSet named = posixClass();
                if (named != null) {
                    set = set.union(named);
                    continue;
                }
                // not a well-formed [:name:]; '[' is a literal here, as it is in
                // every other engine that is not Java
            }

            int lo = classChar();
            // A '-' is a range only between two characters and never before ']'.
            if (!eof() && peek() == '-' && pos + 1 < src.length() && src.charAt(pos + 1) != ']') {
                pos++;
                int hi = classChar();
                if (lo > hi) {
                    throw err("Inverted range in character class");
                }
                set = set.union(CharSet.range(lo, hi));
            } else {
                set = set.union(CharSet.range(lo, lo));
            }
        }
        return negated ? set.complement() : set;
    }

    /** {@code [:name:]} at the cursor, or null if it is not one. */
    private CharSet posixClass() {
        int close = src.indexOf(":]", pos + 2);
        if (close < 0) {
            return null;
        }
        String name = src.substring(pos + 2, close);
        if (name.isEmpty() || !name.chars().allMatch(Character::isLetter)) {
            return null;
        }
        CharSet set = NAMED.get(name.toLowerCase(java.util.Locale.ROOT));
        if (set == null) {
            throw err("Unknown POSIX class [:" + name + ":]");
        }
        pos = close + 2;
        return set;
    }

    /**
     * One character inside a class. A shorthand is not a single character, so it
     * is handled by the caller through {@link #classChar()} returning only after
     * the shorthand case has been ruled out; a shorthand met here is an endpoint
     * of a range, which no dialect allows.
     */
    private int classChar() {
        char c = peek();
        if (c != '\\') {
            pos++;
            return c;
        }
        if (pos + 1 >= src.length()) {
            throw err("Trailing backslash");
        }
        char d = src.charAt(pos + 1);
        if (SHORTHANDS.containsKey(d)) {
            throw err("Shorthand \\" + d + " cannot be an endpoint of a range");
        }
        // Inside a class \b is the backspace character, not a word boundary --
        // there are no positions inside a class. Java, PCRE and the Brics front
        // end all read it that way.
        if (d == 'b') {
            pos += 2;
            return '\b';
        }
        return escapeChar();
    }

    /** An escape outside a class: a set (for a shorthand) or a single character. */
    private Ast escapeAtom() {
        if (pos + 1 >= src.length()) {
            throw err("Trailing backslash");
        }
        char d = src.charAt(pos + 1);
        CharSet shorthand = SHORTHANDS.get(d);
        if (shorthand != null) {
            if (dialect == Dialect.POSIX) {
                throw notPosix("\\" + d, "[[:digit:]] and the other named classes");
            }
            pos += 2;
            return new Ast.Chars(shorthand);
        }
        if (d == 'p' || d == 'P') {
            if (dialect == Dialect.POSIX) {
                throw notPosix("\\" + d + "{...}", "[[:name:]]");
            }
            pos += 2;
            if (eof() || peek() != '{') {
                throw err("Expected '{' after \\" + d);
            }
            int close = src.indexOf('}', pos);
            if (close < 0) {
                throw err("Unterminated \\" + d + "{...}");
            }
            String name = src.substring(pos + 1, close);
            CharSet set = NAMED.get(name.toLowerCase(java.util.Locale.ROOT));
            if (set == null) {
                throw err("Unsupported character class \\" + d + "{" + name + "}");
            }
            pos = close + 1;
            return new Ast.Chars(d == 'P' ? set.complement() : set);
        }
        if (d == 'Q') {
            if (dialect == Dialect.POSIX) {
                throw notPosix("\\Q", "a backslash before each character");
            }
            int end = src.indexOf("\\E", pos + 2);
            String quoted = end < 0 ? src.substring(pos + 2) : src.substring(pos + 2, end);
            pos = end < 0 ? src.length() : end + 2;
            List<Ast> parts = new ArrayList<>();
            for (int i = 0; i < quoted.length(); i++) {
                parts.add(new Ast.Chars(CharSet.of(quoted.charAt(i))));
            }
            return parts.isEmpty() ? new Ast.Empty() : new Ast.Concat(parts);
        }
        if (d == 'E') {
            pos += 2;
            return new Ast.Empty();
        }
        return new Ast.Chars(CharSet.of((char) escapeChar()));
    }

    /**
     * A backslash escape that denotes one character, consuming it. Anything a
     * {@code DFA} cannot express throws here rather than being taken as a literal,
     * which is the mistake the 2007 engine made with {@code \b}.
     */
    private int escapeChar() {
        char d = src.charAt(pos + 1);
        if ("bBGkhvRXKAzZ".indexOf(d) >= 0 || (d >= '1' && d <= '9')) {
            throw err("\\" + d + ": word boundaries, backreferences, anchors and the like need an NFA");
        }
        pos += 2;
        switch (d) {
            case 't' -> {
                return posixRejectEscape(d, '\t');
            }
            case 'n' -> {
                return posixRejectEscape(d, '\n');
            }
            case 'r' -> {
                return posixRejectEscape(d, '\r');
            }
            case 'f' -> {
                return posixRejectEscape(d, '\f');
            }
            case 'a' -> {
                return posixRejectEscape(d, (char) 0x07);
            }
            case 'e' -> {
                return posixRejectEscape(d, (char) 0x1B);
            }
            case '0' -> {
                int v = 0;
                int digits = 0;
                while (!eof() && digits < 3 && peek() >= '0' && peek() <= '7') {
                    v = v * 8 + (src.charAt(pos++) - '0');
                    digits++;
                }
                if (digits == 0) {
                    throw err("Malformed \\0 escape");
                }
                return posixRejectEscape('0', (char) v);
            }
            case 'x', 'u' -> {
                return posixRejectEscape(d, (char) hex(d));
            }
            case 'c' -> {
                if (eof()) {
                    throw err("Malformed \\c escape");
                }
                char v = (char) (Character.toUpperCase(src.charAt(pos++)) ^ 64);
                return posixRejectEscape('c', v);
            }
            default -> {
                if (Character.isLetterOrDigit(d) && dialect == Dialect.POSIX) {
                    throw notPosix("\\" + d,
                            "the character itself; POSIX leaves a backslash before an ordinary character undefined");
                }
                return d;
            }
        }
    }

    private int posixRejectEscape(char kind, char value) {
        if (dialect == Dialect.POSIX) {
            throw notPosix("\\" + kind, "the character itself");
        }
        return value;
    }

    private int hex(char kind) {
        if (kind == 'x' && !eof() && peek() == '{') {
            int close = src.indexOf('}', pos);
            if (close < 0) {
                throw err("Unterminated \\x{...}");
            }
            int v = parseHex(src.substring(pos + 1, close));
            pos = close + 1;
            return v;
        }
        int want = kind == 'x' ? 2 : 4;
        if (pos + want > src.length()) {
            throw err("Malformed \\" + kind + " escape");
        }
        int v = parseHex(src.substring(pos, pos + want));
        pos += want;
        return v;
    }

    private int parseHex(String s) {
        try {
            int v = Integer.parseInt(s, 16);
            if (v > CharSet.MAX) {
                throw err("Code point above the Basic Multilingual Plane");
            }
            return v;
        } catch (NumberFormatException e) {
            throw err("Malformed hexadecimal escape");
        }
    }
}
