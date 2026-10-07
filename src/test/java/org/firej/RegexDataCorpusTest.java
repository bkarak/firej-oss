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

import java.util.stream.Stream;

import org.firej.codegen.GeneratorKind;
import org.firej.parser.Pipeline;
import org.firej.dfa.Preprocessor;
import org.firej.dfa.BricsPreprocessor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import dk.brics.automaton.Automaton;
import dk.brics.automaton.RegExp;
import dk.brics.automaton.RunAutomaton;

/**
 * Unit-test harness over {@code regex.data}. Rows FIRE/J cannot compile are
 * skipped; the sample string is not treated as a required match, because the dump
 * is not a FIRE/J oracle.
 *
 * <p><b>Which oracle.</b> For the classic pipeline the oracle is Brics
 * {@link RunAutomaton}: the same front end and the same library, differing only
 * in the walker, which is exactly the right check for generated code. It is
 * <em>not</em> a valid oracle for the native pipeline, which reads the dialect
 * itself and is known to disagree with Brics by design — Brics mis-reads an
 * alternation with an empty branch, so {@code (|x)} makes the two differ and the
 * native engine is the one that agrees with {@code java.util.regex}. The native
 * rows are therefore checked against {@code java.util.regex} instead.
 */
class RegexDataCorpusTest {

    private static final Firej INTERP = Firej.builder().pipeline(Pipeline.CLASSIC)
            .generator(GeneratorKind.INTERPRETER).build();
    private static final Firej ASM = Firej.builder().pipeline(Pipeline.CLASSIC)
            .generator(GeneratorKind.BYTECODE).build();
    private static final Firej NATIVE = Firej.builder().pipeline(Pipeline.NATIVE)
            .generator(GeneratorKind.BYTECODE).build();

    static Stream<RegexDataCorpus.Sample> samples() {
        return RegexDataCorpus.samples();
    }

    @Test
    void corpusIsPresent() {
        assertFalse(RegexDataCorpus.load().isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    void compiledSampleAgreesWithBricsAndBackends(RegexDataCorpus.Sample sample) {
        Regex generated;
        try {
            generated = ASM.compile(sample.pattern());
        } catch (RuntimeException e) {
            Assumptions.assumeTrue(false, "unsupported: " + e.getMessage());
            return;
        }

        int firejEnd = generated.run(sample.input(), 0);
        int bricsEnd = bricsLongestFromStart(sample.pattern(), sample.input());
        assertEquals(bricsEnd, firejEnd, () -> "FIRE/J vs Brics on " + sample);

        int interpretedEnd = INTERP.compile(sample.pattern()).run(sample.input(), 0);
        assertEquals(firejEnd, interpretedEnd, () -> "backends disagree on " + sample);

        assertEquals(firejEnd >= 0, generated.lookingAt(sample.input()));
        assertEquals(firejEnd == sample.input().length(), generated.matches(sample.input()));
    }

    /**
     * The native pipeline over the same corpus, against {@code java.util.regex}.
     * Rows either engine rejects are skipped: the two dialects are close but not
     * identical, and a refusal is a documented outcome, not a failure.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    void nativePipelineAgreesWithJavaUtilRegex(RegexDataCorpus.Sample sample) {
        Regex generated;
        try {
            generated = NATIVE.compile(sample.pattern());
        } catch (RuntimeException e) {
            Assumptions.assumeTrue(false, "unsupported by the native engine: " + e.getMessage());
            return;
        }
        // java.util.regex reads a '[' inside a class as opening a nested class and
        // unions the two; POSIX, PCRE, Go, Brics and both FIRE/J engines read a
        // literal '['. Neither engine here has ever supported the Java form, so on
        // these rows java.util.regex is not an oracle for this library.
        Assumptions.assumeFalse(usesNestedClass(sample.pattern()),
                "nested character class: a documented dialect divergence from java.util.regex");
        int expected;
        try {
            expected = javaLongestFromStart(sample.pattern(), sample.input());
        } catch (RuntimeException e) {
            Assumptions.assumeTrue(false, "java.util.regex rejects the pattern: " + e.getMessage());
            return;
        }
        assertEquals(expected, generated.run(sample.input(), 0),
                () -> "native vs java.util.regex on " + sample);
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

    /**
     * Brics {@code run(s, offset)} returns the longest accepted run length from
     * {@code offset}, or {@code -1}. At offset 0 that is FIRE/J's exclusive end.
     */
    private static int bricsLongestFromStart(String pattern, String input) {
        Preprocessor.Processed processed = BricsPreprocessor.getInstance().process(pattern);
        Automaton automaton = new RegExp(processed.expression()).toAutomaton();
        automaton.determinize();
        int end = new RunAutomaton(automaton).run(input, 0);
        return processed.anchoredEnd() && end != input.length() ? -1 : end;
    }
}
