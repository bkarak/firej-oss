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

/**
 * Character classes for a generated matcher. The generator partitions the
 * character space at the boundaries of every transition range of the
 * automaton; a character's class is the number of boundaries at or below it.
 * The generated class calls {@link #of} once, from its static initialiser,
 * with the boundaries packed into a string constant, and then resolves a
 * character with one array load — or, at or above the last boundary, with
 * the constant class index the generator already knows.
 */
public final class ClassMap {
    private ClassMap() {
    }

    /**
     * The class of every character below the last boundary.
     *
     * @param boundaries the class boundaries, ascending, none of them 0
     */
    public static char[] of(String boundaries) {
        int n = boundaries.length();
        if (n == 0) {
            return new char[0];
        }
        char[] map = new char[boundaries.charAt(n - 1)];
        int cls = 0;
        for (int c = 0; c < map.length; c++) {
            while (cls < n && boundaries.charAt(cls) <= c) {
                cls++;
            }
            map[c] = (char) cls;
        }
        return map;
    }
}
