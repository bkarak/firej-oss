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
package org.firej.tools;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.firej.Regex;
import org.firej.RegexFactory;
import org.firej.codegen.GeneratorKind;

import dk.brics.automaton.Automaton;
import dk.brics.automaton.RegExp;
import dk.brics.automaton.RunAutomaton;

/**
 * Indicative micro-benchmark: the three FIRE/J back-ends vs Brics RunAutomaton
 * vs {@code java.util.regex}. Times the DFA walk alone ({@code exec}), like the
 * paper; capturing groups are not recovered by any of them.
 */
public final class MiniBenchmark {
    private MiniBenchmark() {
    }

    private static long fire(String regex, String data, int iterations, GeneratorKind kind) {
        Regex r = RegexFactory.createRegex(regex, kind);
        if (!r.matches(data)) {
            return -1;
        }
        r.setData(data);
        long sink = 0;
        for (int i = 0; i < iterations; i++) {
            sink += r.exec(0);
        }
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            sink += r.exec(0);
        }
        long elapsed = System.nanoTime() - start;
        if (sink == Long.MIN_VALUE) {
            throw new IllegalStateException("unreachable");
        }
        return TimeUnit.NANOSECONDS.toMillis(elapsed);
    }

    /**
     * The same walk followed by group recovery ({@code run} instead of
     * {@code exec}): what a caller pays for capturing groups on top of the span.
     */
    private static long fireRun(String regex, String data, int iterations, GeneratorKind kind) {
        Regex r = RegexFactory.createRegex(regex, kind);
        if (!r.matches(data)) {
            return -1;
        }
        r.setData(data);
        long sink = 0;
        int warm = Math.max(1, iterations / 10);
        for (int i = 0; i < warm; i++) {
            sink += r.run(0);
        }
        long start = System.nanoTime();
        for (int i = 0; i < warm; i++) {
            sink += r.run(0);
        }
        long elapsed = System.nanoTime() - start;
        if (sink == Long.MIN_VALUE) {
            throw new IllegalStateException("unreachable");
        }
        // scaled to the iteration count of the other rows, so the columns compare
        return TimeUnit.NANOSECONDS.toMillis(elapsed * (iterations / warm));
    }

    private static long automaton(String regex, String data, int iterations) {
        RunAutomaton runAutomaton;
        try {
            Automaton automaton = new RegExp(regex).toAutomaton();
            automaton.determinize();
            runAutomaton = new RunAutomaton(automaton);
        } catch (RuntimeException e) {
            return -1;
        }
        if (!runAutomaton.run(data)) {
            return -1;
        }
        for (int i = 0; i < iterations; i++) {
            runAutomaton.run(data);
        }
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            runAutomaton.run(data);
        }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private static long jdk(String regex, String data, int iterations) {
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(data);
        if (!matcher.matches()) {
            return -1;
        }
        for (int i = 0; i < iterations; i++) {
            matcher.matches();
        }
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            matcher.matches();
        }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    public static void run() {
        Map<String, String> bench = new LinkedHashMap<>();
        bench.put("(([01]?[0-9][0-9]?|2[0-4][0-9]|25[0-5])\\.){3}([01]?[0-9][0-9]?|2[0-4][0-9]|25[0-5])",
                "255.255.255.255");
        bench.put("[0-9]*", "999999999999999999999");
        bench.put("[a-zA-Z_0-9]+@[a-zA-Z_]+\\.[a-zA-Z]{2,3}", "joe@aol.com");
        final int iterations = 1_000_000;

        for (Map.Entry<String, String> e : bench.entrySet()) {
            System.out.println("Regex: " + e.getKey());
            System.out.printf("Engine: %s Time: %s msecs%n", "fire (goto)",
                    fire(e.getKey(), e.getValue(), iterations, GeneratorKind.BYTECODE));
            System.out.printf("Engine: %s Time: %s msecs%n", "fire (goto, ranges)",
                    fire(e.getKey(), e.getValue(), iterations, GeneratorKind.BYTECODE_RANGES));
            System.out.printf("Engine: %s Time: %s msecs%n", "fire (switch)",
                    fire(e.getKey(), e.getValue(), iterations, GeneratorKind.BYTECODE_SWITCH));
            System.out.printf("Engine: %s Time: %s msecs%n", "fire (interp)",
                    fire(e.getKey(), e.getValue(), iterations, GeneratorKind.INTERPRETER));
            System.out.printf("Engine: %s Time: %s msecs%n", "fire (goto, run with groups)",
                    fireRun(e.getKey(), e.getValue(), iterations, GeneratorKind.BYTECODE));
            System.out.printf("Engine: %s Time: %s msecs%n", "automaton", automaton(e.getKey(), e.getValue(), iterations));
            System.out.printf("Engine: %s Time: %s msecs%n", "jdk", jdk(e.getKey(), e.getValue(), iterations));
        }
    }
}
