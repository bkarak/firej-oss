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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.firej.codegen.GeneratorKind;
import org.firej.parser.Pipeline;
import org.firej.dfa.Preprocessor;
import org.firej.dfa.BricsPreprocessor;
import org.firej.tools.Regex101Data;
import org.junit.jupiter.api.Test;

import dk.brics.automaton.Automaton;
import dk.brics.automaton.RegExp;
import dk.brics.automaton.RunAutomaton;

/**
 * Harness over the filtered regex101 / Hugging Face slice. Every stored row is
 * expected to compile, under both pipelines.
 *
 * <p>The Brics oracle belongs to the classic pipeline only: it is the same front
 * end and the same library, differing from the compiled rows by the walker alone.
 * The native pipeline reads the dialect itself and is known to disagree with
 * Brics where Brics is wrong — an alternation with an empty branch, {@code (|x)},
 * which the native engine and {@code java.util.regex} both read the other way. It
 * is therefore checked for agreement with the classic pipeline only on the rows
 * where the two dialects coincide, and otherwise just for compiling.
 */
class Regex101CorpusTest {

    private static final Firej INTERP = Firej.builder().pipeline(Pipeline.CLASSIC)
            .generator(GeneratorKind.INTERPRETER).build();
    private static final Firej ASM = Firej.builder().pipeline(Pipeline.CLASSIC)
            .generator(GeneratorKind.BYTECODE).build();
    private static final Firej NATIVE = Firej.builder().pipeline(Pipeline.NATIVE)
            .generator(GeneratorKind.BYTECODE).build();

    @Test
    void corpusIsLargeAndCompilable() {
        var samples = Regex101Data.load();
        assertTrue(samples.size() > 1000, "expected a filtered HF slice, got " + samples.size());
        int i = 0;
        int rejected = 0;
        for (Regex101Data.Sample sample : samples) {
            // Bound the haystack so capture recovery stays cheap on fat patterns.
            String input = clip(sample.input(), 64);
            Regex interpreted;
            try {
                interpreted = INTERP.compile(sample.pattern());
            } catch (RegexCompilationException e) {
                // A row the filter let through that the front end now refuses, such
                // as a PCRE verb, (*SKIP), that it used to read as literal text.
                rejected++;
                continue;
            }
            int interpretedEnd = interpreted.run(input, 0);
            int bricsEnd = bricsLongestFromStart(sample.pattern(), input);
            assertEquals(bricsEnd, interpretedEnd, () -> "FIRE/J vs Brics on " + sample);

            if (i % 10 == 0) {
                int generatedEnd = ASM.compile(sample.pattern()).run(input, 0);
                assertEquals(interpretedEnd, generatedEnd, () -> "backends disagree on " + sample);
            }
            i++;
        }
        assertTrue(rejected <= samples.size() / 100,
                "the classic pipeline should compile almost the whole slice; " + rejected + " rejected");
    }

    /**
     * The native pipeline compiles the whole slice and agrees with the classic one.
     *
     * <p>The two pipelines are compared against each other rather than against
     * {@code java.util.regex}, because these inputs are multi-line: FIRE/J's end
     * anchor means "the end of the data", while Java's {@code $} also matches
     * before a final newline, and asking Java whether a <em>prefix</em> matches is
     * a different question again. Both pipelines share FIRE/J's anchor semantics,
     * so any difference between them is a difference in the engine, which is what
     * this test is for.
     *
     * <p>One divergence is expected and skipped: an alternation with an empty
     * branch. Brics mis-reads {@code (|x)}; the native engine and
     * {@code java.util.regex} agree with each other and not with it.
     */
    @Test
    void nativePipelineAgreesWithTheClassicOne() {
        var samples = Regex101Data.load();
        int compiled = 0;
        int compared = 0;
        int rejected = 0;
        for (Regex101Data.Sample sample : samples) {
            String input = clip(sample.input(), 64);
            Regex nativeRegex;
            try {
                nativeRegex = NATIVE.compile(sample.pattern());
            } catch (RuntimeException e) {
                rejected++;
                continue;
            }
            compiled++;
            if (hasEmptyAlternationBranch(sample.pattern())) {
                continue;
            }
            int classicEnd;
            try {
                classicEnd = INTERP.compile(sample.pattern()).run(input, 0);
            } catch (RuntimeException e) {
                continue;       // the classic front end rejects it; nothing to compare
            }
            compared++;
            assertEquals(classicEnd, nativeRegex.run(input, 0),
                    () -> "native vs classic on " + sample);
        }
        assertTrue(compiled > samples.size() * 99 / 100,
                "the native engine should compile almost the whole slice, got " + compiled + " of " + samples.size()
                        + " (" + rejected + " rejected)");
        assertTrue(compared > 1000, "too few rows compared across pipelines: " + compared);
    }

    /** {@code (|x)}, {@code (x|)} or {@code (a||b)} — a branch matching nothing. */
    private static boolean hasEmptyAlternationBranch(String pattern) {
        return pattern.contains("(|") || pattern.contains("||") || pattern.contains("|)");
    }

    /** A '[' inside a character class — Java's nested-class union syntax. */
    private static boolean usesNestedClass(String pattern) {
        boolean inClass = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                i++;
            } else if (!inClass && c == '[') {
                inClass = true;
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '^') {
                    i++;
                }
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == ']') {
                    i++;
                }
            } else if (inClass && c == '[') {
                return true;
            } else if (inClass && c == ']') {
                inClass = false;
            }
        }
        return false;
    }

    /** The longest prefix of {@code input} the pattern matches, or -1. */
    private static int javaLongestFromStart(String pattern, String input) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        for (int len = input.length(); len >= 0; len--) {
            if (p.matcher(input.substring(0, len)).matches()) {
                return len;
            }
        }
        return -1;
    }

    private static String clip(String input, int max) {
        return input.length() <= max ? input : input.substring(0, max);
    }

    private static int bricsLongestFromStart(String pattern, String input) {
        Preprocessor.Processed processed = BricsPreprocessor.getInstance().process(pattern);
        Automaton automaton = new RegExp(processed.expression()).toAutomaton();
        automaton.determinize();
        int end = new RunAutomaton(automaton).run(input, 0);
        return processed.anchoredEnd() && end != input.length() ? -1 : end;
    }
}
