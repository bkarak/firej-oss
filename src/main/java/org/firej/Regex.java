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

import org.firej.capture.CapturePlan;

/**
 * A compiled regular expression. Instances are not safe to share across threads
 * while matching; compile (or {@link Pattern}) once and create a fresh instance
 * per thread from the cache.
 *
 * <p>{@link #exec(int)} and {@link #run(int)} return the exclusive end index of
 * the longest match starting at {@code offset}, or {@code -1} if nothing matched.
 * A pattern that begins with {@code ^} only matches at offset 0; one that ends
 * with {@code $} only matches if the match reaches the end of the input.
 *
 * <p>{@link #groupCount()} is the number of capturing {@code (...)} groups
 * (Java convention: group 0 is the whole match and is not counted).
 */
public abstract class Regex {
    protected final String regex;
    protected final int groupCount;
    protected final MatchResult matchResult;
    private final CapturePlan capturePlan;
    private CapturePlan.Engine captureEngine;

    protected Regex(String regex) {
        this.regex = regex;
        this.capturePlan = CapturePlan.compile(regex);
        this.groupCount = capturePlan.groupCount();
        this.matchResult = new MatchResult(this);
    }

    public static Regex compile(String pattern) {
        return Firej.standard().compile(pattern);
    }

    public MatchResult getMatchResult() {
        return matchResult;
    }

    public String getRegex() {
        return regex;
    }

    public int groupCount() {
        return groupCount;
    }

    public int run(CharSequence data, int start) {
        setData(data);
        return run(start);
    }

    /**
     * {@link #exec(int)} plus group bookkeeping. Capturing groups are only
     * recovered when the pattern has some and the match succeeded; otherwise
     * this costs the same as {@code exec}.
     */
    public int run(int start) {
        int end = exec(start);
        matchResult.record(start, end);
        if (end >= 0 && groupCount > 0) {
            if (captureEngine == null) {
                captureEngine = capturePlan.newEngine();
            }
            captureEngine.fill(getData(), start, end, matchResult);
        }
        return end;
    }

    /**
     * {@code true} if the entire input is a match.
     */
    public boolean matches(CharSequence input) {
        int end = run(input, 0);
        return end == input.length();
    }

    /**
     * {@code true} if a match (possibly empty) exists at index 0.
     */
    public boolean lookingAt(CharSequence input) {
        return run(input, 0) >= 0;
    }

    /**
     * Exclusive end index of the longest match starting at {@code start}, or {@code -1}.
     */
    public abstract int exec(int start);

    public abstract void setData(CharSequence data);

    /**
     * The input given to the last {@link #setData(CharSequence)}, by reference.
     */
    public abstract CharSequence getData();
}
