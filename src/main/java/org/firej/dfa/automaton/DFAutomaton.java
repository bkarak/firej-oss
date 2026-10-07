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

import org.firej.dfa.DFA;
import org.firej.dfa.State;
import org.firej.dfa.Transition;

import dk.brics.automaton.Automaton;

/**
 * FIRE/J view of a Brics {@link Automaton}, determinized and minimized. States
 * are numbered densely in encounter order, the initial state first — we never
 * parse {@code State.toString()}, which is how the 2007 code recovered numbers.
 */
final class DFAutomaton implements DFA {
    private final BricsState[] states;
    private final BricsState initial;

    DFAutomaton(Automaton automaton) {
        automaton.determinize();
        automaton.minimize();

        Map<dk.brics.automaton.State, BricsState> map = new IdentityHashMap<>();
        List<dk.brics.automaton.State> raw = new ArrayList<>();
        dk.brics.automaton.State rawInitial = automaton.getInitialState();
        if (rawInitial != null) {
            raw.add(rawInitial);
        }
        Set<dk.brics.automaton.State> all = automaton.getStates();
        for (dk.brics.automaton.State st : all) {
            if (st != rawInitial) {
                raw.add(st);
            }
        }
        for (dk.brics.automaton.State st : raw) {
            map.put(st, new BricsState(map.size(), st.isAccept(), new Transition[st.getTransitions().size()]));
        }
        for (dk.brics.automaton.State st : raw) {
            BricsState s = map.get(st);
            int i = 0;
            for (dk.brics.automaton.Transition t : st.getTransitions()) {
                s.transitions[i++] = new BricsTransition(t.getMin(), t.getMax(), map.get(t.getDest()));
            }
        }
        this.states = raw.stream().map(map::get).toArray(BricsState[]::new);
        this.initial = rawInitial == null ? null : map.get(rawInitial);
    }

    @Override
    public State[] getStates() {
        return states.clone();
    }

    @Override
    public State getInitialState() {
        return initial;
    }

    private record BricsState(int number, boolean accept, Transition[] transitions) implements State {
        @Override
        public int getStateNumber() {
            return number;
        }

        @Override
        public boolean isAccept() {
            return accept;
        }

        @Override
        public Transition[] getTransitions() {
            return transitions.clone();
        }
    }

    private record BricsTransition(int min, int max, State dest) implements Transition {
        @Override
        public int getMin() {
            return min;
        }

        @Override
        public int getMax() {
            return max;
        }

        @Override
        public State getDest() {
            return dest;
        }
    }
}
