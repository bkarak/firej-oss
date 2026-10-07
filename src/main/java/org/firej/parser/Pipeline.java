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

/**
 * A named preprocessor-and-engine pair, so callers can say what they want rather
 * than assemble it.
 *
 * <p>The generator is the third stage and stays separate: every pipeline here
 * works with every {@code GeneratorKind}.
 */
public enum Pipeline {
    /**
     * Nothing is rewritten: the pattern goes straight to the native engine, which
     * reads the dialect itself and builds the automaton over character sets.
     */
    NATIVE(PreprocessorKind.IDENTITY, ParserKind.FIRE),
    /**
     * The historical path: the pattern is rewritten into the dk.brics.automaton
     * dialect, and that library builds the automaton.
     */
    CLASSIC(PreprocessorKind.BRICS, ParserKind.AUTOMATON);

    private final PreprocessorKind preprocessor;
    private final ParserKind parser;

    Pipeline(PreprocessorKind preprocessor, ParserKind parser) {
        this.preprocessor = preprocessor;
        this.parser = parser;
    }

    public PreprocessorKind preprocessor() {
        return preprocessor;
    }

    public ParserKind parser() {
        return parser;
    }
}
