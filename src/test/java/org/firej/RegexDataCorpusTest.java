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
import org.firej.dfa.PreProcessor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import dk.brics.automaton.Automaton;
import dk.brics.automaton.RegExp;
import dk.brics.automaton.RunAutomaton;

/**
 * Unit-test harness over {@code regex.data}. Rows that FIRE/J cannot compile
 * are skipped. Compiled rows are checked against Brics {@link RunAutomaton}
 * (same language, different walker) and across the bytecode and interpreter
 * backends. The sample string is not treated as a required match — the dump
 * is not a FIRE/J oracle.
 */
class RegexDataCorpusTest {

    private static final Firej INTERP = Firej.builder().generator(GeneratorKind.INTERPRETER).build();
    private static final Firej ASM = Firej.builder().generator(GeneratorKind.BYTECODE).build();

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
     * Brics {@code run(s, offset)} returns the longest accepted run length from
     * {@code offset}, or {@code -1}. At offset 0 that is FIRE/J's exclusive end.
     */
    private static int bricsLongestFromStart(String pattern, String input) {
        PreProcessor.Processed processed = PreProcessor.getInstance().process(pattern);
        Automaton automaton = new RegExp(processed.expression()).toAutomaton();
        automaton.determinize();
        int end = new RunAutomaton(automaton).run(input, 0);
        return processed.anchoredEnd() && end != input.length() ? -1 : end;
    }
}
