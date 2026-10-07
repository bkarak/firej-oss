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

import org.firej.dfa.BricsPreprocessor;
import org.firej.dfa.Preprocessor;
import org.firej.dfa.fire.IdentityPreprocessor;

/** The pipeline's first stage, as a selectable plug-in. */
public enum PreprocessorKind {
    /**
     * Rewrites nothing: lifts the anchors and passes the pattern through. What the
     * native engine wants, because it reads the dialect itself.
     */
    IDENTITY,
    /**
     * Rewrites the pattern into the dk.brics.automaton dialect, expanding every
     * character class into explicit ranges. Required by {@link ParserKind#AUTOMATON},
     * whose parser cannot express a class.
     */
    BRICS;

    public Preprocessor create(Preprocessor.Dialect dialect) {
        return switch (this) {
            case IDENTITY -> new IdentityPreprocessor(dialect);
            case BRICS -> BricsPreprocessor.getInstance(dialect);
        };
    }
}
