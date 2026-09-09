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
package org.firej.dfa;

/**
 * Builds a {@link DFA} from a regular expression string.
 */
public interface Parser {
    /**
     * Rewrites shorthands and lifts anchors out of a user pattern. The result's
     * {@link PreProcessor.Processed#expression()} is what {@link #getDFA} takes.
     */
    PreProcessor.Processed preprocess(String regex);

    /**
     * Builds the automaton for an already-preprocessed expression.
     */
    DFA getDFA(String expression, int flags);

    String getAuthor();

    String getName();
}
