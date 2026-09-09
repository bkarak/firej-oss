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

import org.firej.dfa.State;
import org.firej.dfa.Transition;

public final class TransitionFire implements Transition {
    private final int min;
    private final int max;
    private final State from;
    private final State to;

    public TransitionFire(int min, int max, State from, State to) {
        this.min = min;
        this.max = max;
        this.from = from;
        this.to = to;
    }

    @Override
    public State getDest() {
        return to;
    }

    @Override
    public char getMax() {
        return (char) max;
    }

    @Override
    public int getMaxAsInt() {
        return max;
    }

    @Override
    public char getMin() {
        return (char) min;
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
    public boolean isSingle() {
        return min == max;
    }
}
