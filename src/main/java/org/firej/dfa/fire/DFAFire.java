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

import java.util.List;

import org.firej.dfa.DFA;
import org.firej.dfa.State;
import org.firej.dfa.Transition;

/** The native engine's automaton: state {@code 0} is the initial one. */
final class DFAFire implements DFA {
    private final StateFire[] states;

    /**
     * @param trans per state, its transitions as {@code {lo, hi, destination}}
     */
    DFAFire(boolean[] accept, List<List<int[]>> trans) {
        states = new StateFire[accept.length];
        for (int i = 0; i < states.length; i++) {
            states[i] = new StateFire(i, accept[i], new Transition[trans.get(i).size()]);
        }
        for (int i = 0; i < states.length; i++) {
            List<int[]> edges = trans.get(i);
            for (int k = 0; k < edges.size(); k++) {
                int[] e = edges.get(k);
                states[i].transitions[k] = new TransitionFire(e[0], e[1], states[e[2]]);
            }
        }
    }

    @Override
    public State getInitialState() {
        return states[0];
    }

    @Override
    public State[] getStates() {
        return states.clone();
    }

    private record StateFire(int number, boolean accept, Transition[] transitions) implements State {
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

    private record TransitionFire(int min, int max, State dest) implements Transition {
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
