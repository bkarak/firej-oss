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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;

import org.firej.Firej;
import org.firej.Regex;
import org.firej.RegexCompilationException;
import org.firej.cache.CacheKind;
import org.firej.dfa.Preprocessor.Dialect;
import org.firej.parser.Pipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The native parser: pattern text straight to a minimal DFA, with no preprocessor
 * rewriting and no third-party automaton.
 */
class NativeEngineTest {

    private static final Firej FIRE =
            Firej.builder().pipeline(Pipeline.NATIVE).cache(CacheKind.DISABLED).build();
    private static final Firej AUTO =
            Firej.builder().pipeline(Pipeline.CLASSIC).cache(CacheKind.DISABLED).build();

    private static int end(Firej engine, String pattern, String input) {
        Regex r = engine.compile(pattern);
        r.setData(input);
        return r.exec(0);
    }

    /** The longest prefix of {@code input} that {@code pattern} matches, or -1. */
    private static int javaLongestPrefix(String pattern, String input) {
        Pattern p = Pattern.compile(pattern);
        for (int len = input.length(); len >= 0; len--) {
            if (p.matcher(input.substring(0, len)).matches()) {
                return len;
            }
        }
        return -1;
    }

    private record Case(String pattern, String input, int expected) {
    }

    /**
     * The engine returns the end of the longest match from offset zero. Data is
     * written out rather than given as CSV: these patterns contain '|', which
     * collides with any delimiter worth using.
     */
    @Test
    void matchesTheLongestPrefix() {
        Case[] cases = {
            new Case("[0-9]+", "12345", 5),
            new Case("[0-9]+", "12a45", 2),
            new Case("a*b", "aaab", 4),
            new Case("(ab|cd)+", "abcd", 4),
            new Case("x{0,3}", "xxxxx", 3),
            new Case("x{2,3}", "x", -1),
            new Case("x{3}", "xxx", 3),
            new Case("[^0-9]+", "abc", 3),
            new Case("\\d{2,4}", "123456", 4),
            new Case("[a-f0-9]+", "9af", 3),
            new Case("\\w+", "a_1", 3),
            new Case("[[:digit:]]+", "42", 2),
            new Case("[\\s.-]+", " .-", 3),
            new Case("(a|b|c)*", "abcabc", 6),
        };
        for (Case c : cases) {
            assertEquals(c.expected(), end(FIRE, c.pattern(), c.input()),
                    c.pattern() + " on \"" + c.input() + "\"");
            // java.util.regex misreads [[:class:]], so it cannot be the oracle there
            if (!c.pattern().contains("[:")) {
                assertEquals(javaLongestPrefix(c.pattern(), c.input()), end(FIRE, c.pattern(), c.input()),
                        "should agree with java.util.regex: " + c.pattern() + " on \"" + c.input() + "\"");
            }
        }
    }

    /**
     * An alternation with an empty branch. The automaton backend gets this wrong —
     * it reports no match — which is the divergence the corpus disagreement list
     * had been showing all along. The native engine agrees with java.util.regex.
     */
    @Test
    void emptyAlternationBranches() {
        Case[] cases = {
            new Case("(|-?[0-9]+)", "1234", 4),
            new Case("(|a)b", "b", 1),
            new Case("(| )x", "x", 1),
        };
        for (Case c : cases) {
            assertEquals(c.expected(), end(FIRE, c.pattern(), c.input()), c.pattern() + " on " + c.input());
            assertEquals(javaLongestPrefix(c.pattern(), c.input()), end(FIRE, c.pattern(), c.input()),
                    "java.util.regex is the oracle: " + c.pattern());
        }
    }

    /**
     * The minimiser must distinguish states by the <em>ends</em> of their ranges,
     * not only the starts. With a probe set built from the low endpoints alone,
     * {@code x[a-c]} and {@code z[a-f]} both probe 'a', nothing probes 'd', the two
     * states merge and the survivor's narrower range wins: the pattern compiled to
     * {@code [xz][a-c]} and "zd" silently stopped matching. Four thousand corpus
     * patterns did not catch this; it needs two sibling branches whose ranges share
     * a low bound and differ in the high one.
     */
    @Test
    void minimiserDistinguishesRangeEnds() {
        Case[] cases = {
            new Case("x[a-c]|z[a-f]", "zd", 2),
            new Case("v[0-5]|w[0-9]", "w8", 2),
            new Case("a[1-3]|b[1-9]", "b7", 2),
            new Case("p[a-c]|q[a-f]", "pd", -1),
        };
        for (Case c : cases) {
            assertEquals(c.expected(), end(FIRE, c.pattern(), c.input()),
                    c.pattern() + " on \"" + c.input() + "\"");
            assertEquals(javaLongestPrefix(c.pattern(), c.input()), end(FIRE, c.pattern(), c.input()),
                    "java.util.regex is the oracle: " + c.pattern());
        }
    }

    /**
     * Both bounds of a repetition are unrolled, so both must be bounded. Only the
     * maximum was checked, and {@code {n,}} has none — so a{30000,} built tens of
     * thousands of states and ground rather than failing.
     */
    @Test
    void bothRepetitionBoundsAreChecked() {
        assertThrows(RegexCompilationException.class, () -> FIRE.compile("a{1,999999}"), "max");
        assertThrows(RegexCompilationException.class, () -> FIRE.compile("a{999999,}"), "min of {n,}");
    }

    /**
     * An anchor beside a top-level alternation binds to one branch, which a DFA
     * cannot express — in {@code a|b$} the {@code $} applies to {@code b} alone.
     * Only the start anchor was checked, so {@code a|b$} compiled and then answered
     * -1 where the pattern matches. Both backends must refuse it.
     */
    @Test
    void eitherAnchorBesideTopLevelAlternationIsRefused() {
        assertThrows(RegexCompilationException.class, () -> FIRE.compile("a|b$"));
        assertThrows(RegexCompilationException.class, () -> FIRE.compile("^a|b"));
        assertThrows(RegexCompilationException.class, () -> AUTO.compile("a|b$"), "the two backends agree");
        // Inside a group it is expressible and must still work.
        assertEquals(1, end(FIRE, "(a|b)$", "b"));
    }

    /**
     * A pattern whose automaton the native engine can build but whose capturing
     * groups it cannot recover must be refused, not half-supported.
     *
     * <p>The capture plan is still parsed through the dk.brics front end whatever
     * engine built the automaton, so the native engine can accept a pattern that
     * front end rejects. That used to yield a working matcher reporting
     * {@code groupCount() == 0} for a pattern with groups, and every group came back
     * null from a successful match. Four rows of the regexlib corpus sit exactly
     * there. The library's own rule is to throw rather than silently succeed.
     */
    @Test
    void aPatternWithGroupsAndNoCapturePlanIsRefused() {
        // An empty alternation branch: the native engine reads it, the dk.brics
        // front end rejects it outright. All four affected regexlib rows are of
        // this shape, e.g. ([0-9])([0-9])*( )*(px|PX|Px|pX|pt|PT|Pt|pT|).
        String pattern = "([0-9]+)(px|pt|)";
        RegexCompilationException e = assertThrows(RegexCompilationException.class,
                () -> FIRE.compile(pattern),
                "a pattern with groups and no plan must not compile");
        // Firej.compile wraps the cause, so look down the chain for the reason.
        boolean saysWhy = false;
        for (Throwable c = e; c != null; c = c.getCause()) {
            saysWhy |= c.getMessage() != null && c.getMessage().contains("capturing-group plan");
        }
        assertTrue(saysWhy, "the refusal should name the reason: " + e.getMessage());

        // The same shape without groups has nothing to recover and still compiles.
        assertEquals(3, end(FIRE, "[a-z]+", "abc"));
    }

    /** A class is a set, not a rewrite: these three spellings are one automaton. */
    @Test
    void aClassIsASetNotARewrite() {
        for (String input : new String[] {"7", "a", "", "77"}) {
            int viaShorthand = end(FIRE, "\\d+", input);
            int viaRange = end(FIRE, "[0-9]+", input);
            int viaPosix = end(FIRE, "[[:digit:]]+", input);
            assertEquals(viaRange, viaShorthand, "\\d+ vs [0-9]+ on \"" + input + "\"");
            assertEquals(viaRange, viaPosix, "[[:digit:]]+ vs [0-9]+ on \"" + input + "\"");
        }
    }

    /** A class of thousands of code points costs what one character costs. */
    @Test
    void wideClassesDoNotEnumerateCharacters() {
        assertEquals(3, end(FIRE, "[\\x{0100}-\\x{FFFF}]+", "Ā中￿"));
        assertEquals(1, end(FIRE, "[^a]", "中"));
    }

    /**
     * The engine works over UTF-16 code units, so a character above the Basic
     * Multilingual Plane is two of them and {@code .} matches either half.
     *
     * <p>This is a limitation both engines share — the automaton library has the
     * same ceiling — so it is not a regression, but nothing pinned it. An escape
     * naming an astral code point is refused outright; a literal one is split into
     * its surrogate pair, which happens to work for a plain literal and does not for
     * a quantifier or a character class. Recorded here so a future change to the
     * code-unit assumption has to come past a test.
     */
    @Test
    void astralCharactersAreCodeUnitsNotCodePoints() {
        assertThrows(RegexCompilationException.class, () -> FIRE.compile("\\x{1F600}"),
                "an escape above the BMP is refused rather than truncated");

        String grin = "\uD83D\uDE00";                       // one code point, two code units
        assertEquals(2, end(FIRE, grin, grin), "a literal astral character matches as its pair");
        // '.' is one code unit, so it takes half the pair and not the whole character.
        assertEquals(1, end(FIRE, ".", grin), "'.' is a code unit");
        assertEquals(2, end(FIRE, "..", grin), "two of them make the character");
        // The automaton backend agrees; this is a shared ceiling, not a native quirk.
        assertEquals(end(AUTO, ".", grin), end(FIRE, ".", grin));
    }

    /** The two backends agree, so either can be chosen without changing meaning. */
    @ParameterizedTest
    @ValueSource(strings = {
        "[0-9]{5}", "[a-zA-Z_0-9.-]+@[a-z]+\\.[a-z]{2,4}", "(foo|bar|baz)+",
        "a*b+c?", "[^,]*,[^,]*", "\\d{1,3}(\\.\\d{1,3}){3}", "[[:alpha:]][[:alnum:]]*",
    })
    void bothBackendsAgree(String pattern) {
        for (String in : new String[] {"", "12345", "a@b.com", "foobar", "abc", "1.2.3.4", "x,y"}) {
            assertEquals(end(AUTO, pattern, in), end(FIRE, pattern, in),
                    "backends disagree on " + pattern + " against \"" + in + "\"");
        }
    }

    /** What needs an NFA is refused, not taken as a literal — the 2007 mistake. */
    @ParameterizedTest
    @ValueSource(strings = {"a\\bc", "(?=x)y", "(a)\\1", "\\Kx", "a\\Bc"})
    void whatNeedsAnNfaIsRefused(String pattern) {
        assertThrows(RegexCompilationException.class, () -> FIRE.compile(pattern), pattern);
    }

    /** The dialect option works on this backend too. */
    @Test
    void dialectApplies() {
        Firej posix = Firej.builder().pipeline(Pipeline.NATIVE)
                .dialect(Dialect.POSIX).cache(CacheKind.DISABLED).build();
        assertEquals(2, end(posix, "[[:digit:]]+", "42"));
        RegexCompilationException e =
                assertThrows(RegexCompilationException.class, () -> posix.compile("\\d+"));
        assertTrue(e.getMessage().contains("not POSIX ERE"), e.getMessage());
    }

    /** Anchors are flags; one in the middle is an error, not a literal. */
    @Test
    void anchorsAreLifted() {
        Regex r = FIRE.compile("^[0-9]+$");
        r.setData("123");
        assertEquals(3, r.exec(0));
        assertThrows(RegexCompilationException.class, () -> FIRE.compile("a^b"));
    }

    /** A pattern that would explode fails fast rather than hanging. */
    @Test
    void pathologicalPatternsAreBounded() {
        assertThrows(RegexCompilationException.class, () -> FIRE.compile("a{1,999999}"));
    }
}
