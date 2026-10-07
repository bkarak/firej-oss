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
package org.firej.cache;

/**
 * Where a {@code Firej} engine keeps the templates it has compiled, keyed by
 * the pattern as written. Implementations must be safe for concurrent use;
 * plug one in with {@code Firej.builder().cache(...)}.
 */
public interface RegexCache {
    /** The template for {@code regex}, or {@code null} on a miss. */
    RegexTemplate get(String regex);

    void put(String regex, RegexTemplate template);

    /** Drops the template for {@code regex}, if there is one. */
    void remove(String regex);

    void clear();

    /** A short name, for diagnostics. */
    String name();
}
