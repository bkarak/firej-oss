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
package org.firej.dfa.fire;

import org.firej.dfa.DFA;
import org.firej.dfa.Parser;
import org.firej.dfa.PreProcessor;

/**
 * Placeholder for the never-finished native POSIX parser. The 2007 sources
 * returned an empty DFA; that silently compiled every pattern to a non-matcher.
 * This backend now fails fast — use {@link org.firej.parser.ParserKind#AUTOMATON}.
 */
public final class ParserFire implements Parser {

    @Override
    public PreProcessor.Processed preprocess(String regex) {
        return new PreProcessor.Processed(regex, false, false);
    }

    @Override
    public DFA getDFA(String expression, int flags) {
        throw new UnsupportedOperationException(
                "The native FIRE parser was never completed; use ParserKind.AUTOMATON");
    }

    @Override
    public String getAuthor() {
        return "Vassilios Karakoidas";
    }

    @Override
    public String getName() {
        return "FIRE";
    }
}
