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

import org.firej.dfa.Preprocessor;
import org.firej.dfa.DFA;
import org.firej.dfa.Parser;

/**
 * The native parser: pattern text straight to a minimal {@code DFA}, with no
 * intermediate dialect and no third-party automaton library.
 *
 * <p>This is the backend the 2007 sources left unfinished. The preprocessor
 * exists because the automaton library of the time could not express a character
 * class: {@code \d} had to be rewritten into explicit ranges, and those ranges
 * re-escaped into that library's own syntax, before anything could be parsed. So
 * a pattern was written once by the user, rewritten by the preprocessor, and
 * parsed a second time by the library — and every one of the dialect defects
 * found on 2026-09-06 lived in that rewriting step, where a {@code \t} became
 * the letter t and {@code \b} became the letter b.
 *
 * <p>{@link RegexParser} reads the dialect directly and yields a tree over
 * {@link CharSet} values, so the rewriting step is gone. Lifting the anchors — the
 * only thing that still has to happen before an automaton can be built, because an
 * automaton has no notion of position — is {@link IdentityPreprocessor}'s job, the
 * first stage of the same pipeline.
 *
 * <p>Semantics are those of the other backend — leftmost-longest, no
 * backreferences, no lookaround — and the two are checked against each other on
 * both corpora.
 */
public final class ParserFire implements Parser {

    private final Preprocessor.Dialect dialect;

    public ParserFire(Preprocessor.Dialect dialect) {
        this.dialect = dialect;
    }

    @Override
    public DFA getDFA(String expression) {
        return DfaCompiler.compile(expression, dialect);
    }

    @Override
    public String name() {
        return "FIRE";
    }
}
