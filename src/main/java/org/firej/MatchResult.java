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
 * Captured match offsets for a {@link Regex} run. Group 0 is the whole match;
 * groups 1..{@link #groupCount()} are filled from {@code (...)} after the DFA
 * finds the span. A group that did not participate is unset ({@code start == -1},
 * {@link #group(int)} returns {@code null}).
 */
public final class MatchResult {
    private final Regex regex;
    private final Group[] groups;

    MatchResult(Regex regex) {
        this.regex = regex;
        this.groups = new Group[regex.groupCount() + 1];
        for (int i = 0; i < groups.length; i++) {
            groups[i] = new Group();
        }
    }

    void record(int start, int end) {
        groups[0].start = start;
        groups[0].end = end;
        for (int i = 1; i < groups.length; i++) {
            groups[i].start = -1;
            groups[i].end = -1;
        }
    }

    public void capture(int group, int start, int end) {
        groups[group].start = start;
        groups[group].end = end;
    }

    public int start() {
        return start(0);
    }

    public int start(int group) {
        checkMatched();
        checkGroup(group);
        return groups[group].start;
    }

    public int end() {
        return end(0);
    }

    public int end(int group) {
        checkMatched();
        checkGroup(group);
        return groups[group].end;
    }

    public String group() {
        return group(0);
    }

    public String group(int group) {
        checkMatched();
        checkGroup(group);
        Group g = groups[group];
        if (g.start < 0 || g.end < 0) {
            return null;
        }
        return regex.getData().subSequence(g.start, g.end).toString();
    }

    public int groupCount() {
        return regex.groupCount();
    }

    private void checkMatched() {
        if (groups[0].end < 0) {
            throw new IllegalStateException("No match available");
        }
    }

    private void checkGroup(int group) {
        if (group < 0 || group >= groups.length) {
            throw new IndexOutOfBoundsException("No group " + group);
        }
    }

    static final class Group {
        int start = -1;
        int end = -1;
    }
}
