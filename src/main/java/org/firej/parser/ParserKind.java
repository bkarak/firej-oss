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
package org.firej.parser;

import org.firej.dfa.Parser;
import org.firej.dfa.Preprocessor;
import org.firej.dfa.automaton.ParserAutomaton;
import org.firej.dfa.fire.ParserFire;

public enum ParserKind {
    AUTOMATON,
    FIRE;

    /**
     * The engine for {@code dialect}. Only {@code FIRE} reads the dialect itself;
     * {@code AUTOMATON} is handed what the {@code BRICS} preprocessor rewrote.
     */
    public Parser create(Preprocessor.Dialect dialect) {
        return switch (this) {
            case AUTOMATON -> new ParserAutomaton();
            case FIRE -> new ParserFire(dialect);
        };
    }
}
