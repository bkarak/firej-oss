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
package org.firej;

/**
 * One-shot matcher over a {@link CharSequence}, analogous to {@code java.util.regex.Matcher}.
 *
 * <p>As in the JDK, a successful {@link #matches()}, {@link #lookingAt()} or
 * {@link #find()} leaves the next {@link #find()} starting after the match
 * (one past it, if the match was empty). {@link #reset()} starts over.
 */
public final class Matcher {
    private final Regex regex;
    private CharSequence input;
    private int lastFind = 0;
    private boolean matched;

    Matcher(Regex regex, CharSequence input) {
        this.regex = regex;
        this.input = input;
        regex.setData(input);
    }

    public Matcher reset() {
        lastFind = 0;
        matched = false;
        return this;
    }

    public Matcher reset(CharSequence input) {
        this.input = input;
        regex.setData(input);
        return reset();
    }

    public boolean matches() {
        int end = regex.run(0);
        matched = end == input.length();
        if (matched) {
            advance(0, end);
        }
        return matched;
    }

    public boolean lookingAt() {
        int end = regex.run(0);
        matched = end >= 0;
        if (matched) {
            advance(0, end);
        }
        return matched;
    }

    /**
     * The next match, starting where the last one ended (one past it, if it
     * was empty).
     */
    public boolean find() {
        return search(lastFind);
    }

    /**
     * The first match starting at or after {@code start}.
     *
     * @throws IndexOutOfBoundsException if {@code start} is negative or past
     *         the end of the input
     */
    public boolean find(int start) {
        if (start < 0 || start > input.length()) {
            throw new IndexOutOfBoundsException("Illegal start index " + start);
        }
        return search(start);
    }

    private boolean search(int start) {
        int len = input.length();
        for (int i = start; i <= len; i++) {
            int end = regex.run(i);
            if (end >= i) {
                advance(i, end);
                matched = true;
                return true;
            }
        }
        matched = false;
        return false;
    }

    private void advance(int from, int end) {
        lastFind = end == from ? from + 1 : end;
    }

    public String group() {
        return group(0);
    }

    public String group(int group) {
        requireMatch();
        return regex.getMatchResult().group(group);
    }

    public int start() {
        return start(0);
    }

    public int start(int group) {
        requireMatch();
        return regex.getMatchResult().start(group);
    }

    public int end() {
        return end(0);
    }

    public int end(int group) {
        requireMatch();
        return regex.getMatchResult().end(group);
    }

    public int groupCount() {
        return regex.groupCount();
    }

    /** The matcher instance this wraps; for tests. */
    Regex regex() {
        return regex;
    }

    private void requireMatch() {
        if (!matched) {
            throw new IllegalStateException("No match available");
        }
    }
}
