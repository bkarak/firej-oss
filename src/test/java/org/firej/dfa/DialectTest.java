/*
 * Copyright 2026 Vassilios Karakoidas
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
package org.firej.dfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.firej.Firej;
import org.firej.Regex;
import org.firej.RegexCompilationException;
import org.firej.dfa.Preprocessor.Dialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The two front ends. Both compile to the same automaton and match with the same
 * semantics; they differ only in what a programmer may write.
 */
class DialectTest {

    private static boolean matches(Dialect d, String pattern, String input) {
        Regex r = Firej.builder().dialect(d).build().compile(pattern);
        r.setData(input);
        return r.exec(0) == input.length();
    }

    // --- what both dialects must accept -------------------------------------

    /**
     * POSIX named classes work in <em>both</em> dialects. Before the dialect
     * option existed, {@code [[:digit:]]} was copied through to Brics, which read
     * the letters: the pattern compiled and meant {@code [:dgit]}, the same silent
     * misreading java.util.regex has. A digit-matching pattern that quietly does
     * not match digits is the worst outcome available, so this is pinned first.
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void posixNamedClassesWorkInBothDialects(Dialect d) {
        assertTrue(matches(d, "[[:digit:]]+", "123"), d + " should match digits");
        assertTrue(matches(d, "[[:alpha:]]+", "abc"), d + " should match letters");
        assertTrue(matches(d, "[[:alnum:]]+", "a1b2"), d + " should match alphanumerics");
        assertTrue(matches(d, "[[:upper:]][[:lower:]]+", "Abc"), d + " should match Upper then lower");
        assertTrue(matches(d, "[[:xdigit:]]+", "1aF"), d + " should match hex digits");

        assertTrue(!matches(d, "[[:digit:]]+", "12a"), d + " must not match a letter as a digit");
        assertTrue(!matches(d, "[[:digit:]]+", "ddd"),
                d + " must not read [[:digit:]] as the letters d, i, g, t");
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void plainPosixEreWorksInBothDialects(Dialect d) {
        assertTrue(matches(d, "[0-9]{3}", "123"), d.toString());
        assertTrue(matches(d, "(ab|cd)+", "abcd"), d.toString());
        assertTrue(matches(d, "a*b", "aaab"), d.toString());
        assertTrue(matches(d, "[^0-9]+", "abc"), d.toString());
        assertTrue(matches(d, "x?y", "y"), d.toString());
    }

    // --- the conveniences: EXTENDED only ------------------------------------

    /**
     * The conveniences are a short, closed list — the shorthands, {@code \p{Name}},
     * the character escapes, {@code \Q...\E} and non-capturing groups. They are
     * not a step towards PCRE: lookaround, backreferences and word boundaries stay
     * rejected in both dialects, because a DFA cannot express them.
     */
    @ParameterizedTest
    @ValueSource(strings = {"\\d+", "\\w+", "\\s+", "\\p{Alpha}+", "(?:ab)+", "\\Qa.b\\E", "\\x41+"})
    void extendedAcceptsTheConveniences(String pattern) {
        Firej.builder().dialect(Dialect.EXTENDED).build().compile(pattern);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\d+", "\\w+", "\\s+", "\\p{Alpha}+", "(?:ab)+", "\\Qa.b\\E", "\\x41+"})
    void posixRejectsTheConveniences(String pattern) {
        RegexCompilationException e = assertThrows(RegexCompilationException.class,
                () -> Firej.builder().dialect(Dialect.POSIX).build().compile(pattern));
        assertTrue(e.getMessage().contains("not POSIX ERE"),
                "the refusal should say why and name the alternative: " + e.getMessage());
    }

    /** POSIX leaves a backslash before an ordinary character undefined; refuse it. */
    @Test
    void posixRefusesUndefinedEscapes() {
        assertThrows(RegexCompilationException.class,
                () -> Firej.builder().dialect(Dialect.POSIX).build().compile("\\q"));
    }

    /** Escaping punctuation is the portable case and stays legal in both. */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void punctuationEscapesAreLegalInBoth(Dialect d) {
        assertTrue(matches(d, "a\\.b", "a.b"), d.toString());
        assertTrue(!matches(d, "a\\.b", "axb"), d.toString());
    }

    // --- neither dialect is PCRE --------------------------------------------

    /** What needs an NFA is refused whichever front end is chosen. */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void neitherDialectIsPcre(Dialect d) {
        for (String pattern : new String[] {"a\\bc", "(?=x)y", "(?!x)y", "(a)\\1", "\\Kx"}) {
            assertThrows(RegexCompilationException.class,
                    () -> Firej.builder().dialect(d).build().compile(pattern),
                    d + " should refuse " + pattern);
        }
    }

    // --- the two front ends agree on the language ---------------------------

    /** The same language, written both ways, accepts the same strings. */
    @Test
    void theTwoSpellingsAgree() {
        record Pair(String extended, String posix, String[] yes, String[] no) {
        }
        Pair[] pairs = {
            new Pair("\\d+", "[[:digit:]]+", new String[] {"1", "123"}, new String[] {"", "12a", "abc"}),
            new Pair("\\w+", "[[:alnum:]_]+", new String[] {"a_1", "Z"}, new String[] {"a b", "-"}),
            new Pair("\\d{2,4}", "[[:digit:]]{2,4}", new String[] {"12", "1234"}, new String[] {"1", "12345"}),
        };
        for (Pair p : pairs) {
            for (String in : p.yes()) {
                assertEquals(true, matches(Dialect.EXTENDED, p.extended(), in), p.extended() + " on " + in);
                assertEquals(true, matches(Dialect.POSIX, p.posix(), in), p.posix() + " on " + in);
            }
            for (String in : p.no()) {
                assertEquals(false, matches(Dialect.EXTENDED, p.extended(), in), p.extended() + " on " + in);
                assertEquals(false, matches(Dialect.POSIX, p.posix(), in), p.posix() + " on " + in);
            }
        }
    }

    /** EXTENDED is the default, so existing callers are unaffected. */
    @Test
    void extendedIsTheDefault() {
        assertEquals(Dialect.EXTENDED, BricsPreprocessor.getInstance().dialect());
        Firej.builder().build().compile("\\d+");
    }
}
