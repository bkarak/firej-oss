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
package org.firej.runtime;

import org.firej.capture.CapturePlan;
import org.firej.dfa.FlattenedDfa;
import org.firej.dfa.FlattenedDfa.Range;

/**
 * Walks a flattened DFA at runtime. Used as the fallback generator and as the
 * oracle the bytecode backend is tested against.
 */
public final class InterpreterRegex extends CharArrayRegex {
    private final int startState;
    private final boolean[] accept;
    private final Range[][] transitions;
    private final boolean anchoredStart;
    private final boolean anchoredEnd;

    public InterpreterRegex(FlattenedDfa dfa, CapturePlan capturePlan) {
        super(dfa.pattern(), capturePlan);
        this.startState = dfa.startState();
        this.accept = dfa.accept();
        this.transitions = dfa.transitions();
        this.anchoredStart = dfa.anchoredStart();
        this.anchoredEnd = dfa.anchoredEnd();
    }

    @Override
    protected int walk(int start) {
        if (anchoredStart && start != 0) {
            return -1;
        }
        final char[] arr = arrayBuffer;
        final int len = length;
        int state = startState;
        int returnValue = accept[state] ? start : -1;

        int i = start;
        for (; i < len; i++) {
            int c = arr[i];
            Range[] edges = transitions[state];
            boolean moved = false;
            for (Range edge : edges) {
                if (c >= edge.min() && c <= edge.max()) {
                    state = edge.dest();
                    if (accept[state]) {
                        returnValue = i + 1;
                    }
                    moved = true;
                    break;
                }
            }
            if (!moved) {
                break;
            }
        }
        pos = i;
        return finish(returnValue, len);
    }

    private int finish(int returnValue, int len) {
        return anchoredEnd && returnValue != len ? -1 : returnValue;
    }
}
