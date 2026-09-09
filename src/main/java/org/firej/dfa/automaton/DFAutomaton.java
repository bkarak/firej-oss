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

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.firej.dfa.AbstractDFA;
import org.firej.dfa.State;

import dk.brics.automaton.Automaton;

/**
 * FIRE/J view of a Brics {@link Automaton}. States are numbered densely in
 * encounter order — we never parse {@code State.toString()}, which is how the
 * 2007 code recovered numbers.
 */
public final class DFAutomaton extends AbstractDFA {
    private final Automaton automaton;
    private final StateAutomaton[] states;
    private final StateAutomaton initial;

    public DFAutomaton(Automaton automaton) {
        automaton.determinize();
        automaton.minimize();
        this.automaton = automaton;

        Map<dk.brics.automaton.State, StateAutomaton> map = new IdentityHashMap<>();
        List<StateAutomaton> list = new ArrayList<>();
        int n = 0;

        dk.brics.automaton.State rawInitial = automaton.getInitialState();
        if (rawInitial != null) {
            StateAutomaton wrapped = new StateAutomaton(this, rawInitial, n++);
            map.put(rawInitial, wrapped);
            list.add(wrapped);
        }

        Set<dk.brics.automaton.State> raw = automaton.getStates();
        for (dk.brics.automaton.State st : raw) {
            if (map.containsKey(st)) {
                continue;
            }
            StateAutomaton wrapped = new StateAutomaton(this, st, n++);
            map.put(st, wrapped);
            list.add(wrapped);
        }

        for (StateAutomaton s : list) {
            s.resolveTransitions(map);
        }
        this.states = list.toArray(StateAutomaton[]::new);
        this.initial = rawInitial == null ? null : map.get(rawInitial);
    }

    public Automaton getAutomaton() {
        return automaton;
    }

    @Override
    public State[] getStates() {
        return states.clone();
    }

    @Override
    public String toDot() {
        return automaton.toDot();
    }

    @Override
    public State getInitialState() {
        return initial;
    }

    @Override
    public void addState(State s) {
        throw new UnsupportedOperationException("Brics automata are immutable from FIRE/J");
    }
}
