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
 * Deterministic finite automaton produced by a {@link Parser}. This is the
 * contract a third-party parser implements: {@link FlattenedDfa#from} reads
 * the states, their numbers, their accept flags and their transitions, and
 * nothing else.
 */
public interface DFA {
    State[] getStates();

    State getInitialState();

    /**
     * This automaton in the Graphviz DOT language, states renumbered as the
     * code generators see them. {@code Firej.toDot(pattern)} does the same
     * with the pattern and its anchors on the graph.
     */
    default String toDot() {
        return FlattenedDfa.from("", this, false, false).toDot();
    }
}
