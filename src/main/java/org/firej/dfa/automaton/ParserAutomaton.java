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
package org.firej.dfa.automaton;

import org.firej.RegexCompilationException;
import org.firej.dfa.DFA;
import org.firej.dfa.Parser;
import org.firej.dfa.PreProcessor;

import dk.brics.automaton.RegExp;

/**
 * Parser backend using Anders Møller's automaton library (the original FIRE/J default).
 */
public final class ParserAutomaton implements Parser {

    @Override
    public PreProcessor.Processed preprocess(String regex) {
        return PreProcessor.getInstance().process(regex);
    }

    @Override
    public DFA getDFA(String expression, int flags) {
        try {
            RegExp reg = new RegExp(expression);
            return new DFAutomaton(reg.toAutomaton());
        } catch (IllegalArgumentException e) {
            throw new RegexCompilationException("Invalid regular expression: " + expression, e);
        }
    }

    @Override
    public String getAuthor() {
        return "Vassilios Karakoidas";
    }

    @Override
    public String getName() {
        return "AUTOMATON";
    }
}
