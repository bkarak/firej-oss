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

import java.util.Map;
import java.util.Set;

import org.firej.dfa.DFA;
import org.firej.dfa.State;
import org.firej.dfa.Transition;

public final class StateAutomaton implements State {
    private final dk.brics.automaton.State state;
    private final int stateNumber;
    private final DFA dfa;
    private Transition[] transitions = new Transition[0];

    StateAutomaton(DFA dfa, dk.brics.automaton.State state, int stateNumber) {
        this.dfa = dfa;
        this.state = state;
        this.stateNumber = stateNumber;
    }

    void resolveTransitions(Map<dk.brics.automaton.State, StateAutomaton> map) {
        Set<dk.brics.automaton.Transition> raw = state.getTransitions();
        Transition[] resolved = new Transition[raw.size()];
        int i = 0;
        for (dk.brics.automaton.Transition t : raw) {
            resolved[i++] = new TransitionAutomaton(this, t, map.get(t.getDest()));
        }
        this.transitions = resolved;
    }

    @Override
    public void addTransition(Transition transition) {
        throw new UnsupportedOperationException("Brics states are immutable from FIRE/J");
    }

    @Override
    public Transition[] getTransitions() {
        return transitions.clone();
    }

    @Override
    public boolean isAccept() {
        return state.isAccept();
    }

    @Override
    public void setAccept(boolean accept) {
        state.setAccept(accept);
    }

    @Override
    public String toString() {
        return "StateAutomaton(" + stateNumber + (isAccept() ? ",accept" : "") + ")";
    }

    @Override
    public int getStateNumber() {
        return stateNumber;
    }

    @Override
    public DFA getDFA() {
        return dfa;
    }

    @Override
    public int group() {
        return 0;
    }
}
