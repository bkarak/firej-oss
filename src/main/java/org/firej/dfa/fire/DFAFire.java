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

import java.util.ArrayList;
import java.util.List;

import org.firej.dfa.AbstractDFA;
import org.firej.dfa.State;

public final class DFAFire extends AbstractDFA {
    private final List<State> states = new ArrayList<>();
    private final State initial;

    public DFAFire() {
        this.initial = new StateFire(this, 0, 0, false);
        states.add(initial);
    }

    @Override
    public String toDot() {
        return toDot(getStates());
    }

    @Override
    public void addState(State s) {
        states.add(s);
    }

    @Override
    public State getInitialState() {
        return initial;
    }

    @Override
    public State[] getStates() {
        return states.toArray(State[]::new);
    }
}
