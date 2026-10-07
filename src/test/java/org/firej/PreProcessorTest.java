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
package org.firej;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;

import org.firej.dfa.BricsPreprocessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The shorthand expansions are checked against {@code java.util.regex}, not
 * against Brics: the corpus tests feed Brics the same preprocessed string, so
 * they cannot see a wrong expansion.
 */
class PreProcessorTest {

    static Stream<String> shorthands() {
        return Stream.of(
                "\\d", "\\D", "\\s", "\\S", "\\w", "\\W",
                "\\p{Lower}", "\\p{Upper}", "\\p{ASCII}", "\\p{Alpha}", "\\p{Digit}", "\\p{Alnum}",
                "\\p{Punct}", "\\p{Graph}", "\\p{Print}", "\\p{Blank}", "\\p{Cntrl}", "\\p{XDigit}",
                "\\p{Space}", "\\P{Alpha}", "\\P{ASCII}",
                "[\\s-]", "[^\\S]", "[a\\D]", "[^\\w.]", "[\\p{Punct}0]", "x|\\d");
    }

    @ParameterizedTest
    @MethodSource("shorthands")
    void agreesWithJavaUtilRegexOnEveryChar(String shorthand) {
        Regex fire = Regex.compile(shorthand);
        java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(shorthand);
        for (int c = 0; c <= 0x3FF; c++) {
            check(fire, jdk, (char) c);
        }
        for (char c : new char[] {'\u00A0', '\u3000', '\uFFFF'}) {
            check(fire, jdk, c);
        }
    }

    private static void check(Regex fire, java.util.regex.Pattern jdk, char c) {
        String s = String.valueOf(c);
        assertEquals(jdk.matcher(s).matches(), fire.matches(s),
                () -> fire.getRegex() + " on U+" + Integer.toHexString(c));
    }

    @Test
    void whitespaceShorthandMatchesTabNotTheLetterT() {
        Regex r = Regex.compile("\\s+");
        assertTrue(r.matches(" \t\n\f\r"));
        assertFalse(r.matches("t"));
    }

    @Test
    void escapedBackslashIsNotAShorthand() {
        assertEquals("a\\\\d", BricsPreprocessor.getInstance().processExpression("a\\\\d"));
        Regex r = Regex.compile("a\\\\d");
        assertTrue(r.matches("a\\d"));
        assertFalse(r.matches("a5"));
    }

    @Test
    void expansionText() {
        BricsPreprocessor pp = BricsPreprocessor.getInstance();
        assertEquals("[0-9]", pp.processExpression("\\d"));
        assertEquals("[^0-9]", pp.processExpression("[^\\d]"));
        assertEquals("[a-]", pp.processExpression("[a-]"));
        assertEquals("[\\\t-\\\r\\ ]", pp.processExpression("\\s"));
    }

    @Test
    void characterEscapesNameCharacters() {
        // the pattern text holds a backslash and a letter, as it would in a file
        assertTrue(Regex.compile("a\\tb").matches("a\tb"));
        assertFalse(Regex.compile("a\\tb").matches("atb"));
        assertTrue(Regex.compile("[^\\t ]+").matches("tnx0Bfr"));
        assertTrue(Regex.compile("\\x41\\u0042\\x{43}").matches("ABC"));
        assertTrue(Regex.compile("\\0101").matches("A"));
        assertTrue(Regex.compile("\\cA").matches("\u0001"));
        assertTrue(Regex.compile("[\\b]").matches("\b"));
        assertTrue(Regex.compile("\\Q.+\\E").matches(".+"));
        assertFalse(Regex.compile("\\Q.+\\E").matches("ab"));
    }

    @Test
    void bricsOperatorsAreLiterals() {
        assertTrue(Regex.compile("#?[0-9]+").matches("#12"));
        assertTrue(Regex.compile("#?[0-9]+").matches("12"));
        assertTrue(Regex.compile("a@b").matches("a@b"));
        assertFalse(Regex.compile("a@b").matches("axyzb"));
        assertTrue(Regex.compile("<a>&~\"").matches("<a>&~\""));
        assertTrue(Regex.compile("[<>@#]+").matches("<>@#"));
    }

    @Test
    void dotExcludesNewline() {
        Regex r = Regex.compile("a.c");
        assertTrue(r.matches("axc"));
        assertTrue(r.matches("a\tc"));
        assertFalse(r.matches("a\nc"));
    }

    @Test
    void lazyAndPossessiveQuantifiersRecogniseTheSameLanguage() {
        assertTrue(Regex.compile("a+?b").matches("aab"));
        assertFalse(Regex.compile("a+?b").matches("b"));
        assertTrue(Regex.compile("a*?").matches(""));
        assertTrue(Regex.compile("a{2,3}?b").matches("aab"));
        assertTrue(Regex.compile("a++b").matches("aab"));
        assertTrue(Regex.compile("a??b").matches("b"));
    }

    @Test
    void groupsWithoutSemantics() {
        assertTrue(Regex.compile("(?:ab)+").matches("abab"));
        assertEquals(0, Regex.compile("(?:ab)+").groupCount());
        Regex named = Regex.compile("(?<year>[0-9]{4})-(?P<m>[0-9]{2})");
        assertTrue(named.matches("2026-09"));
        assertEquals(2, named.groupCount());
        assertTrue(Regex.compile("a(?#comment)b").matches("ab"));
    }

    @Test
    void escapedAnchorsAreAnchors() {
        Regex r = Regex.compile("\\Aab\\z");
        assertTrue(r.matches("ab"));
        assertFalse(r.lookingAt("abc"));
        assertTrue(Regex.compile("ab\\Z").matches("ab"));
    }

    @Test
    void nfaOnlyConstructsAreRejectedNotMisread() {
        for (String p : new String[] {"\\bword\\b", "a\\Bb", "(a)\\1", "(?=a)b", "(?!a)b", "(?<=a)b", "(?i)abc",
                "a\\Gb", "\\k<n>", "a\\Ab", "a\\zb", "\\h+", "\\R"}) {
            assertThrows(RegexCompilationException.class, () -> Regex.compile(p), p);
        }
    }

    @Test
    void unknownNamedClassThrows() {
        assertThrows(RegexCompilationException.class, () -> Regex.compile("\\p{Nope}"));
        assertThrows(RegexCompilationException.class, () -> Regex.compile("\\p{Alpha"));
        assertThrows(RegexCompilationException.class, () -> Regex.compile("abc\\"));
    }
}
