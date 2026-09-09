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

import org.firej.Regex;

/**
 * The base of every generated matcher and of the interpreter: the walk reads
 * a {@code char[]} copy of the input, the paper's buffer subsystem.
 *
 * <p>The copy is made in two steps. {@link #setData} copies only the first
 * {@link #WINDOW} characters of a {@code String}; the walk runs over those,
 * and only if it reaches the end of the window without stopping (the walker
 * leaves its position in {@link #pos}) is the rest copied and the walk run
 * again from {@code start}. Real-world inputs mostly stop within a few
 * characters, so the full copy was costing more than the walk itself; a match
 * longer than the window pays for the window twice. Reading the {@code String}
 * in place through {@code charAt} was tried and rejected: a large generated
 * method has one call site per state, and past the JIT's inlining budget they
 * stay calls, at three times the cost of the copy.
 *
 * <p>The {@link CharSequence} passed to {@link #setData} is kept by reference
 * for group extraction; mutating a {@code StringBuilder} after handing it in
 * leaves the match offsets pointing at text that has changed.
 */
public abstract class CharArrayRegex extends Regex {
    /** Characters copied before the first walk. */
    public static final int WINDOW = 64;

    protected char[] arrayBuffer = new char[WINDOW];
    /** Valid characters in {@code arrayBuffer}: the walk's bound. */
    protected int length;
    /** Where the last walk stopped; written by the walker on exit. */
    protected int pos;
    private CharSequence data = "";
    private int total;

    protected CharArrayRegex(String regex) {
        super(regex);
    }

    @Override
    public void setData(CharSequence data) {
        this.data = data;
        total = data.length();
        if (data instanceof String s) {
            length = Math.min(total, WINDOW);
            s.getChars(0, length, arrayBuffer, 0);
            return;
        }
        length = total;
        if (arrayBuffer.length < length) {
            arrayBuffer = new char[length];
        }
        for (int i = 0; i < length; i++) {
            arrayBuffer[i] = data.charAt(i);
        }
    }

    @Override
    public CharSequence getData() {
        return data;
    }

    @Override
    public final int exec(int start) {
        pos = start;
        int end = walk(start);
        if (length < total && pos >= length) {
            // The walk ran off the window: copy the rest and walk again.
            String s = (String) data;
            if (arrayBuffer.length < total) {
                char[] bigger = new char[total];
                System.arraycopy(arrayBuffer, 0, bigger, 0, length);
                arrayBuffer = bigger;
            }
            s.getChars(length, total, arrayBuffer, length);
            length = total;
            pos = start;
            end = walk(start);
        }
        return end;
    }

    /**
     * The DFA walk over {@code arrayBuffer[start..length)}: the exclusive end
     * of the longest match or {@code -1}, with {@link #pos} left at the index
     * where the walk stopped.
     */
    protected abstract int walk(int start);
}
