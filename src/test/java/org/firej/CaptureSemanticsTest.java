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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * How groups are recovered, and where that differs from {@code java.util.regex}.
 *
 * <p>The DFA fixes the span first: the longest match. Groups are then filled by
 * one parse of exactly that span in which every quantifier and every
 * alternation, left to right, takes as much of the input as still lets the
 * rest match — the POSIX rule. The JDK instead reports the first parse its
 * backtracking reaches: the left alternative before the right, and a lazy
 * quantifier as little as possible. Both agree whenever the first parse is
 * also the longest one, which is the common case; the tests below pin the
 * cases where it is not, so a change to either rule shows up here.
 */
class CaptureSemanticsTest {

    private static void assertGroups(Engines engine, String pattern, String input, String... groups) {
        Matcher m = engine.compile(pattern).matcher(input);
        assertTrue(m.matches(), () -> engine + " /" + pattern + "/ should match \"" + input + "\"");
        String[] actual = new String[m.groupCount()];
        for (int g = 1; g <= actual.length; g++) {
            actual[g - 1] = m.group(g);
        }
        assertEquals(Arrays.asList(groups), Arrays.asList(actual),
                () -> engine + " /" + pattern + "/ on \"" + input + "\"");
    }

    /** What the JDK reports for the same match, for the record. */
    private static List<String> jdkGroups(String pattern, String input) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(input);
        assertTrue(m.matches());
        List<String> groups = new ArrayList<>();
        for (int g = 1; g <= m.groupCount(); g++) {
            groups.add(m.group(g));
        }
        return groups;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.firej.Engines#all")
    void anEarlierAlternativeTakesTheLongerBranch(Engines engine) {
        assertGroups(engine, "(a|ab)(c|bcd)(d*)", "abcd", "ab", "c", "d");
        assertEquals(List.of("a", "bcd", ""), jdkGroups("(a|ab)(c|bcd)(d*)", "abcd"));

        assertGroups(engine, "(a|ab)(bc|c)", "abc", "ab", "c");
        assertEquals(List.of("a", "bc"), jdkGroups("(a|ab)(bc|c)", "abc"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.firej.Engines#all")
    void aLazyQuantifierIsGreedyForGroupsToo(Engines engine) {
        assertGroups(engine, "(.*?)(\\d+)", "abc123", "abc12", "3");
        assertEquals(List.of("abc", "123"), jdkGroups("(.*?)(\\d+)", "abc123"));

        assertGroups(engine, "(a+?)(a*)", "aaa", "aaa", "");
        assertEquals(List.of("a", "aa"), jdkGroups("(a+?)(a*)", "aaa"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.firej.Engines#all")
    void aStarredGroupDoesNotEndOnAnEmptyIteration(Engines engine) {
        assertGroups(engine, "(a*)+", "aaa", "aaa");
        assertEquals(List.of(""), jdkGroups("(a*)+", "aaa"));

        assertGroups(engine, "(a*)*", "aa", "aa");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.firej.Engines#all")
    void whereTheFirstParseIsTheLongestBothAgree(Engines engine) {
        String[][] cases = {
                {"(a+)(a+)", "aaaa"},
                {"(\\w+)@(\\w+)\\.com", "joe@example.com"},
                {"((a)|b)+", "ab"},
                {"((a)|b)+", "ba"},
                {"(ab|a)(bc|c)?", "abc"},
                {"((a)(b))*", "abab"},
                {"(x?)(x?)x", "xx"},
                {"(.*)/(.*)", "a/b/c"},
                {"([^.]*)\\.(.*)", "archive.tar.gz"},
                {"(a)|b", "b"},
                {"(\\d+)(?:px|pt)", "12pt"},
        };
        for (String[] c : cases) {
            assertGroups(engine, c[0], c[1], jdkGroups(c[0], c[1]).toArray(String[]::new));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.firej.Engines#all")
    void groupsAreNumberedByTheirOpeningParenthesis(Engines engine) {
        // Non-capturing, named and escaped parentheses, and parentheses in a
        // class or \Q...\E, all count or not as the JDK counts them.
        String pattern = "(?:x)(?<a>a)(\\()([(])(\\Q(\\E)((b)(?:c)(d))";
        assertGroups(engine, pattern, "xa(((bcd", "a", "(", "(", "(", "bcd", "b", "d");
        assertEquals(jdkGroups(pattern, "xa(((bcd"), List.of("a", "(", "(", "(", "bcd", "b", "d"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.firej.Engines#all")
    void manyGroups(Engines engine) {
        StringBuilder pattern = new StringBuilder();
        StringBuilder input = new StringBuilder();
        String[] expected = new String[26];
        for (int i = 0; i < 26; i++) {
            char c = (char) ('a' + i);
            pattern.append('(').append(c).append("+)");
            input.append(String.valueOf(c).repeat(i % 3 + 1));
            expected[i] = String.valueOf(c).repeat(i % 3 + 1);
        }
        assertGroups(engine, pattern.toString(), input.toString(), expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.firej.Engines#all")
    void groupsPastTheCopyWindow(Engines engine) {
        String head = "h".repeat(63);
        Matcher m = engine.compile("(h+)(-+)(t+)").matcher(head + "--" + "t".repeat(40));
        assertTrue(m.matches());
        assertEquals(head, m.group(1));
        assertEquals(63, m.start(2));
        assertEquals(65, m.end(2));
        assertEquals("t".repeat(40), m.group(3));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.firej.Engines#all")
    void groupsOfAFindAreAbsoluteOffsets(Engines engine) {
        Matcher m = engine.compile("<(\\w+)>").matcher("text <b> more <em>");
        assertTrue(m.find());
        assertEquals(5, m.start());
        assertEquals(6, m.start(1));
        assertEquals("b", m.group(1));
        assertTrue(m.find());
        assertEquals(15, m.start(1));
        assertEquals("em", m.group(1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.firej.Engines#all")
    void anyCharSequenceIsInput(Engines engine) {
        StringBuilder input = new StringBuilder("key=").append("v".repeat(100));
        Matcher m = engine.compile("(\\w+)=(\\w+)").matcher(input);
        assertTrue(m.matches());
        assertEquals("key", m.group(1));
        assertEquals("v".repeat(100), m.group(2));
        assertTrue(engine.compile("[a-z]+").matcher(java.nio.CharBuffer.wrap("abc")).matches());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.firej.Engines#all")
    void resetWithNewInputClearsTheOldMatch(Engines engine) {
        Matcher m = engine.compile("([a-z]+)(\\d)?").matcher("abc1");
        assertTrue(m.matches());
        assertEquals("1", m.group(2));
        m.reset("xyz");
        assertThrows(IllegalStateException.class, m::group, "no match yet on the new input");
        assertTrue(m.matches());
        assertEquals("xyz", m.group(1));
        assertNull(m.group(2));
        assertTrue(m.reset().find());
        assertEquals("xyz", m.group());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.firej.Engines#all")
    void aFailedMatchLeavesNoGroups(Engines engine) {
        Matcher m = engine.compile("(a)(b)").matcher("ab");
        assertTrue(m.matches());
        m.reset("ax");
        assertFalse(m.matches());
        assertThrows(IllegalStateException.class, () -> m.group(1));
        assertThrows(IllegalStateException.class, () -> m.start(1));
        assertThrows(IllegalStateException.class, m::end);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.firej.Engines#all")
    void regexRunFillsTheMatchResult(Engines engine) {
        Regex r = engine.firej().compile("(\\d+)-(\\d+)");
        assertEquals(2, r.groupCount());
        assertEquals(8, r.run("x 12-345 y", 2));
        MatchResult result = r.getMatchResult();
        assertEquals(2, result.start());
        assertEquals(8, result.end());
        assertEquals("12", result.group(1));
        assertEquals("345", result.group(2));
        assertEquals(-1, r.run("x 12-345 y", 0));
        assertThrows(IllegalStateException.class, result::group);
        assertThrows(IndexOutOfBoundsException.class, () -> {
            r.run("1-2", 0);
            result.group(3);
        });
    }

    @Test
    void execIsTheSpanOnlyCall() {
        Regex r = Regex.compile("(a+)(b+)");
        r.setData("aabbbc");
        assertEquals(5, r.exec(0));
        assertEquals(5, r.exec(1));
        assertEquals(-1, r.exec(5));
    }

    @Test
    void onePatternServesManyThreads() throws Exception {
        Pattern p = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int id = t;
                results.add(pool.submit(() -> {
                    for (int i = 0; i < 2_000; i++) {
                        String input = id + "." + i + "." + (i * 7);
                        Matcher m = p.matcher(input);
                        if (!m.matches() || !m.group(1).equals(String.valueOf(id))
                                || !m.group(2).equals(String.valueOf(i))
                                || !m.group(3).equals(String.valueOf(i * 7))) {
                            return false;
                        }
                    }
                    return true;
                }));
            }
            for (Future<Boolean> f : results) {
                assertTrue(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
