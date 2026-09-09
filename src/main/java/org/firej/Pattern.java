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

import org.firej.cache.RegexTemplate;

/**
 * Compiled pattern, analogous to {@code java.util.regex.Pattern}. Thread-safe.
 * Obtain a {@link Matcher} per input (or per thread).
 *
 * <p>A pattern holds the {@link RegexTemplate} it was compiled to, so every
 * {@link #matcher} instantiates the same generated class no matter what the
 * engine's cache does afterwards.
 */
public final class Pattern {
    private final String pattern;
    private final RegexTemplate template;
    private final int groupCount;

    private Pattern(String pattern, Firej engine) {
        this.pattern = pattern;
        this.template = engine.template(pattern);
        this.groupCount = template.newInstance().groupCount();
    }

    public static Pattern compile(String regex) {
        return compile(regex, Firej.standard());
    }

    public static Pattern compile(String regex, Firej engine) {
        return new Pattern(regex, engine);
    }

    public String pattern() {
        return pattern;
    }

    /**
     * Number of capturing {@code (...)} groups. Group 0 (the whole match) is
     * not included.
     */
    public int groupCount() {
        return groupCount;
    }

    public Matcher matcher(CharSequence input) {
        return new Matcher(template.newInstance(), input);
    }

    public boolean matches(CharSequence input) {
        return matcher(input).matches();
    }

    @Override
    public String toString() {
        return pattern;
    }
}
