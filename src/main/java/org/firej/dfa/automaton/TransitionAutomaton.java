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

import org.firej.dfa.State;
import org.firej.dfa.Transition;

public final class TransitionAutomaton implements Transition {
    private final State from;
    private final State dest;
    private final char max;
    private final char min;
    private final boolean single;

    TransitionAutomaton(State from, dk.brics.automaton.Transition transition, State dest) {
        this.from = from;
        this.dest = dest;
        this.max = transition.getMax();
        this.min = transition.getMin();
        this.single = max == min;
    }

    @Override
    public State getDest() {
        return dest;
    }

    @Override
    public char getMax() {
        return max;
    }

    @Override
    public char getMin() {
        return min;
    }

    @Override
    public boolean isSingle() {
        return single;
    }

    @Override
    public int getMaxAsInt() {
        return max;
    }

    @Override
    public int getMinAsInt() {
        return min;
    }

    @Override
    public State getState() {
        return from;
    }

    @Override
    public String toString() {
        return min + "-" + max + " -> " + (dest == null ? "?" : dest.getStateNumber());
    }
}
