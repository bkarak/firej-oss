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
import org.firej.dfa.PreProcessor;
import org.firej.tools.Regex101Data;
import org.junit.jupiter.api.Test;

import dk.brics.automaton.Automaton;
import dk.brics.automaton.RegExp;
import dk.brics.automaton.RunAutomaton;

/**
 * Harness over the filtered regex101 / Hugging Face slice. Every stored row is
 * expected to compile. Checked against Brics and both FIRE/J backends.
 */
class Regex101CorpusTest {

    private static final Firej INTERP = Firej.builder().generator(GeneratorKind.INTERPRETER).build();
    private static final Firej ASM = Firej.builder().generator(GeneratorKind.BYTECODE).build();

    @Test
    void corpusIsLargeAndCompilable() {
        var samples = Regex101Data.load();
        assertTrue(samples.size() > 1000, "expected a filtered HF slice, got " + samples.size());
        int i = 0;
        for (Regex101Data.Sample sample : samples) {
            // Bound the haystack so capture recovery stays cheap on fat patterns.
            String input = clip(sample.input(), 64);
            int interpretedEnd = INTERP.compile(sample.pattern()).run(input, 0);
            int bricsEnd = bricsLongestFromStart(sample.pattern(), input);
            assertEquals(bricsEnd, interpretedEnd, () -> "FIRE/J vs Brics on " + sample);

            if (i % 10 == 0) {
                int generatedEnd = ASM.compile(sample.pattern()).run(input, 0);
                assertEquals(interpretedEnd, generatedEnd, () -> "backends disagree on " + sample);
            }
            i++;
        }
    }

    private static String clip(String input, int max) {
        return input.length() <= max ? input : input.substring(0, max);
    }

    private static int bricsLongestFromStart(String pattern, String input) {
        PreProcessor.Processed processed = PreProcessor.getInstance().process(pattern);
        Automaton automaton = new RegExp(processed.expression()).toAutomaton();
        automaton.determinize();
        int end = new RunAutomaton(automaton).run(input, 0);
        return processed.anchoredEnd() && end != input.length() ? -1 : end;
    }
}
