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

import org.firej.util.SourceBuilder;
import org.firej.util.Util;

public abstract class AbstractDFA implements DFA {

    @Override
    public void saveDot(String filename) {
        Util.writeBuffer(toDot().getBytes(java.nio.charset.StandardCharsets.UTF_8), filename);
    }

    public String toDot(State[] states) {
        SourceBuilder source = new SourceBuilder();
        source.appendln("digraph fireautomaton {");
        source.appendln(1, "rankdir=LR;");

        if (states.length > 0) {
            for (State s : states) {
                int stateNumber = s.getStateNumber();
                source.appendln(1, stateNumber + " [shape="
                        + (s.isAccept() ? "doublecircle" : "circle")
                        + ",label=STATE_" + stateNumber + "];");

                for (Transition t : s.getTransitions()) {
                    source.appendln(1, stateNumber
                            + " -> "
                            + t.getDest().getStateNumber()
                            + " [label= "
                            + (t.isSingle() ? "\"" + getRange(t.getMax()) + "\""
                                    : "\"" + getRange(t.getMin()) + "\" - \"" + getRange(t.getMax()) + "\"")
                            + "];");
                }
            }
            source.appendln(1, "initial [shape=plaintext,label=\"\"];");
            source.appendln(1, "initial -> " + getInitialState().getStateNumber() + ";");
        }
        source.appendln("}");
        return source.toString();
    }

    private static String getRange(char c) {
        if (c >= '\u0021' && c <= '\u007E') {
            return String.valueOf(c);
        }
        return Util.unicode(c).substring(1);
    }

    @Override
    public abstract void addState(State s);

    @Override
    public abstract State getInitialState();

    @Override
    public abstract State[] getStates();

    @Override
    public abstract String toDot();
}
