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

import org.firej.dfa.DFA;
import org.firej.dfa.State;
import org.firej.dfa.Transition;

public final class StateFire implements State {
    private final DFA dfa;
    private final int stateNumber;
    private final int group;
    private boolean accept;
    private final List<Transition> trans = new ArrayList<>();

    public StateFire(DFA dfa, int stateNo, int group, boolean accept) {
        this.dfa = dfa;
        this.stateNumber = stateNo;
        this.group = group;
        this.accept = accept;
    }

    @Override
    public void addTransition(Transition t) {
        trans.add(t);
    }

    @Override
    public DFA getDFA() {
        return dfa;
    }

    @Override
    public int getStateNumber() {
        return stateNumber;
    }

    @Override
    public Transition[] getTransitions() {
        return trans.toArray(Transition[]::new);
    }

    @Override
    public int group() {
        return group;
    }

    @Override
    public boolean isAccept() {
        return accept;
    }

    @Override
    public void setAccept(boolean accept) {
        this.accept = accept;
    }
}
