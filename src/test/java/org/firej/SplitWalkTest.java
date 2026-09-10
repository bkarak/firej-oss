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
package org.firej;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.firej.cache.CacheKind;
import org.firej.codegen.AsmCodeGenerator;
import org.firej.codegen.GeneratorKind;
import org.firej.dfa.FlattenedDfa;
import org.firej.dfa.PreProcessor;
import org.firej.dfa.automaton.ParserAutomaton;
import org.firej.tools.CodeSize;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import dk.brics.automaton.Automaton;
import dk.brics.automaton.RegExp;
import dk.brics.automaton.RunAutomaton;

/**
 * A generated walk longer than HotSpot's 8,000-byte JIT limit is split into
 * segment methods. These tests pin that every method of such a class is
 * under the limit, and that the split walk matches exactly as the interpreter
 * and Brics do — across hops, past the 64-character copy window, from any
 * start offset, with both anchors, and on the 64 KiB-plus automata that used
 * to fall back to the interpreter.
 */
class SplitWalkTest {

    private static final String LITERAL = "abcdefghijklmnopqrstuvwxyz".repeat(24); // 624 states
    private static final String IDENT = "[a-zA-Z0-9_]{300}";
    private static final String DOT = ".{700}";
    private static final String BLOWUP = "(a|b)*a(a|b){9}"; // 1,024 states, two ranges each
    private static final String HUGE = "(a|b)*a(a|b){12}"; // 8,192 states, over 64 KiB as one method

    private static String words(int k) {
        Random random = new Random(7);
        List<String> words = new ArrayList<>();
        while (words.size() < k) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                b.append((char) ('a' + random.nextInt(26)));
            }
            if (!words.contains(b.toString())) {
                words.add(b.toString());
            }
        }
        return "(" + String.join("|", words) + ")";
    }

    private static String brief(String pattern) {
        return pattern.length() <= 24 ? pattern : pattern.substring(0, 24) + "…";
    }

    private static FlattenedDfa flatten(String pattern) {
        PreProcessor.Processed p = PreProcessor.getInstance().process(pattern);
        return FlattenedDfa.from(pattern, new ParserAutomaton().getDFA(p.expression(), 0),
                p.anchoredStart(), p.anchoredEnd());
    }

    private static int brics(String pattern, String input, int start) {
        PreProcessor.Processed p = PreProcessor.getInstance().process(pattern);
        if (p.anchoredStart() && start != 0) {
            return -1;
        }
        Automaton automaton = new RegExp(p.expression()).toAutomaton();
        automaton.determinize();
        int end = new RunAutomaton(automaton).run(input, start);
        return end < 0 ? -1 : p.anchoredEnd() && start + end != input.length() ? -1 : start + end;
    }

    @Test
    void statesAreNumberedAlongPaths() {
        FlattenedDfa chain = flatten("abcd");
        assertEquals(0, chain.startState());
        for (int s = 0; s < 4; s++) {
            assertEquals(1, chain.transitions()[s].length);
            assertEquals(s + 1, chain.transitions()[s][0].dest(), "state " + s);
        }
        assertEquals(0, chain.transitions()[4].length);
        // Depth-first: the first alternative's whole path comes before the second's.
        FlattenedDfa fork = flatten("(abc|xyz)");
        assertEquals(0, fork.startState());
        assertEquals('a', fork.transitions()[0][0].min());
        assertEquals(1, fork.transitions()[0][0].dest());
        assertEquals(2, fork.transitions()[1][0].dest());
        assertEquals('x', fork.transitions()[0][1].min());
        assertTrue(fork.transitions()[0][1].dest() > 2);
    }

    @Test
    void bigWalksAreSplitIntoMethodsUnderTheJitLimit() {
        for (String pattern : List.of(LITERAL, IDENT, DOT, BLOWUP, HUGE, words(200))) {
            for (boolean classMap : new boolean[] {true, false}) {
                Map<String, Integer> methods = CodeSize.methods(new AsmCodeGenerator(true, classMap).emit(flatten(pattern)));
                long segments = methods.keySet().stream().filter(name -> name.startsWith("seg")).count();
                assertTrue(segments >= 2, brief(pattern) + " classMap=" + classMap + " segments=" + segments);
                int total = 0;
                for (Map.Entry<String, Integer> e : methods.entrySet()) {
                    if (e.getKey().equals("walk") || e.getKey().startsWith("seg")) {
                        assertTrue(e.getValue() <= AsmCodeGenerator.JIT_LIMIT,
                                brief(pattern) + " " + e.getKey() + " is " + e.getValue() + " bytes");
                        total += e.getValue();
                    }
                }
                assertTrue(total > AsmCodeGenerator.JIT_LIMIT, "total " + total);
            }
        }
        // A small walk stays one method.
        Map<String, Integer> small = CodeSize.methods(new AsmCodeGenerator().emit(flatten("[0-9]{3}-[0-9]{4}")));
        assertTrue(small.containsKey("walk"));
        assertTrue(small.keySet().stream().noneMatch(name -> name.startsWith("seg")));
    }

    @Test
    void theSplitCanBeSwitchedOffForMeasurement() {
        // The size study compares the same build with and without the split.
        Map<String, Integer> unsplit = CodeSize.methods(new AsmCodeGenerator(true, true, false).emit(flatten(LITERAL)));
        assertTrue(unsplit.keySet().stream().noneMatch(name -> name.startsWith("seg")), unsplit.keySet().toString());
        assertTrue(unsplit.get("walk") > AsmCodeGenerator.JIT_LIMIT, "walk is " + unsplit.get("walk") + " bytes");
        Map<String, Integer> split = CodeSize.methods(new AsmCodeGenerator(true, true, true).emit(flatten(LITERAL)));
        assertTrue(split.keySet().stream().anyMatch(name -> name.startsWith("seg")));
        // And the unsplit class still matches like the split one.
        Firej unsplitEngine = Firej.builder().generator(new AsmCodeGenerator(true, true, false))
                .cache(CacheKind.DISABLED).build();
        Firej splitEngine = Firej.builder().generator(new AsmCodeGenerator(true, true, true))
                .cache(CacheKind.DISABLED).build();
        String input = LITERAL.substring(0, 300);
        assertEquals(splitEngine.compile(LITERAL).matches(input), unsplitEngine.compile(LITERAL).matches(input));
        assertTrue(unsplitEngine.compile(LITERAL).matches(LITERAL));
    }

    @ParameterizedTest
    @EnumSource(value = GeneratorKind.class, names = {"BYTECODE", "BYTECODE_RANGES"})
    void splitWalkMatchesLikeTheInterpreterAndBrics(GeneratorKind kind) {
        Firej generated = Firej.builder().generator(kind).cache(CacheKind.DISABLED).build();
        Firej interpreted = Firej.builder().generator(GeneratorKind.INTERPRETER).cache(CacheKind.DISABLED).build();
        Random random = new Random(11);
        StringBuilder ident = new StringBuilder();
        for (int i = 0; i < 320; i++) {
            ident.append("abcXYZ019_".charAt(random.nextInt(10)));
        }
        String words = words(200);
        String lastWord = words.substring(words.lastIndexOf('|') + 1, words.length() - 1);
        String firstWord = words.substring(1, words.indexOf('|'));
        List<String[]> cases = List.of(
                new String[] {LITERAL, LITERAL},
                new String[] {LITERAL, LITERAL + "tail"},
                new String[] {LITERAL, LITERAL.substring(0, 500)},
                new String[] {LITERAL, LITERAL.substring(0, 63)},
                new String[] {LITERAL, LITERAL.substring(0, 64)},
                new String[] {LITERAL, LITERAL.substring(0, 65)},
                new String[] {LITERAL, "x" + LITERAL},
                new String[] {LITERAL, ""},
                new String[] {"^" + LITERAL + "$", LITERAL},
                new String[] {"^" + LITERAL + "$", LITERAL + "x"},
                new String[] {LITERAL + "$", LITERAL.substring(0, 300)},
                new String[] {IDENT, ident.toString()},
                new String[] {IDENT, ident.substring(0, 300)},
                new String[] {IDENT, ident.substring(0, 299) + "-" + ident.substring(300)},
                new String[] {DOT, ident.toString()},
                new String[] {DOT, ident.substring(0, 100) + "\n" + ident.substring(101)},
                new String[] {BLOWUP, "b".repeat(32) + "a" + "b".repeat(9)},
                new String[] {BLOWUP, "ab".repeat(100)},
                new String[] {BLOWUP, "bbbb"},
                new String[] {BLOWUP, "a".repeat(200) + "c"},
                new String[] {HUGE, "b".repeat(50) + "a" + "b".repeat(12)},
                new String[] {HUGE, "ab".repeat(120) + "x"},
                new String[] {words, lastWord},
                new String[] {words, firstWord + "more"},
                new String[] {words, lastWord.substring(0, 7)},
                new String[] {words, "zzzzzzzz"}
        );
        for (String[] c : cases) {
            String pattern = c[0];
            String input = c[1];
            Regex gen = generated.compile(pattern);
            Regex interp = interpreted.compile(pattern);
            int[] starts = {0, 1, 5, Math.max(0, input.length() - 1), input.length()};
            for (int start : starts) {
                if (start > input.length()) {
                    continue;
                }
                int expected = interp.run(input, start);
                assertEquals(brics(pattern, input, start), expected, () -> "oracle disagreement on " + brief(pattern));
                assertEquals(expected, gen.run(input, start),
                        () -> kind + " on " + brief(pattern) + " input length " + input.length() + " from " + start);
                // A non-String input is copied whole; the walk must not care.
                assertEquals(expected, gen.run(new StringBuilder(input), start),
                        () -> kind + " (StringBuilder) on " + brief(pattern) + " from " + start);
            }
        }
    }

    @Test
    void hopsPreserveTheStopPositionForTheCopyWindow() {
        // A match longer than the copy window that crosses segment boundaries: exec must
        // refill after the first walk stops at the window's end, and the result must
        // match a walk over the whole input at once.
        Regex gen = Firej.builder().generator(GeneratorKind.BYTECODE).cache(CacheKind.DISABLED).build().compile(LITERAL);
        String input = LITERAL + LITERAL;
        assertEquals(LITERAL.length(), gen.run(input, 0));
        assertEquals(LITERAL.length(), gen.run(new StringBuilder(input), 0));
        int[] ends = new int[3];
        for (int i = 0; i < ends.length; i++) {
            ends[i] = gen.run(input, 0);
        }
        assertArrayEquals(new int[] {LITERAL.length(), LITERAL.length(), LITERAL.length()}, ends);
    }
}
