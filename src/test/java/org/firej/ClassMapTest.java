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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.firej.cache.CacheKind;
import org.firej.codegen.GeneratorKind;
import org.firej.runtime.ClassMap;
import org.junit.jupiter.api.Test;

class ClassMapTest {

    @Test
    void mapCountsBoundariesAtOrBelowTheCharacter() {
        // cuts at 'a', 'd' and 'z'+1: classes [0,'a') [a,d) [d,z] [z+1,...)
        char[] map = ClassMap.of("ad{");
        assertEquals('{', map.length);
        assertEquals(0, map['`']);
        assertEquals(1, map['a']);
        assertEquals(1, map['c']);
        assertEquals(2, map['d']);
        assertEquals(2, map['z']);
        assertEquals(0, ClassMap.of("").length);
    }

    @Test
    void classMapAndRangeChainAgreeWithTheInterpreterOnWideClasses() {
        String[] patterns = {
                "[a-zA-Z_0-9]+@[a-zA-Z_]+\\.[a-zA-Z]{2,3}",
                "[^\\n]*",
                "([A-Fa-f0-9]{2}:){5}[A-Fa-f0-9]{2}",
                "[\\p{Punct}\\p{Alpha}]+[\\d]?",
        };
        String[] inputs = {"joe@aol.com", "xé￿\t!", "01:AB:cd:EF:23:45", "hello,world!7", "", "\n", "￿￿"};
        Firej interp = engine(GeneratorKind.INTERPRETER);
        Firej ranges = engine(GeneratorKind.BYTECODE_RANGES);
        Firej classes = engine(GeneratorKind.BYTECODE);
        for (String p : patterns) {
            for (String in : inputs) {
                int expected = interp.compile(p).run(in, 0);
                assertEquals(expected, ranges.compile(p).run(in, 0), p + " on " + in + " (ranges)");
                assertEquals(expected, classes.compile(p).run(in, 0), p + " on " + in + " (class map)");
                // a non-String input takes the char[] walker; it must agree with the String one
                CharSequence copied = new StringBuilder(in);
                assertEquals(expected, interp.compile(p).run(copied, 0), p + " on " + in + " (interp, chars)");
                assertEquals(expected, classes.compile(p).run(copied, 0), p + " on " + in + " (class map, chars)");
                assertEquals(expected, ranges.compile(p).run(copied, 0), p + " on " + in + " (ranges, chars)");
            }
        }
    }

    @Test
    void copyWindowIsRefilledWhenTheWalkRunsOffIt() {
        int w = org.firej.runtime.CharArrayRegex.WINDOW;
        for (GeneratorKind kind : GeneratorKind.values()) {
            Regex r = engine(kind).compile("[a-z]+1?");
            String big = "z".repeat(100_000);
            r.setData(big);
            assertTrue(r.getData() == big);
            assertEquals(big.length(), r.exec(0), kind + " long match");
            // exactly the window, one past it, and a walk that dies inside it
            assertEquals(w, r.run("z".repeat(w), 0), kind + " window-long");
            assertEquals(w + 1, r.run("z".repeat(w) + "1", 0), kind + " one past the window");
            assertEquals(w, r.run("z".repeat(w) + "1x", 0) - 1, kind + " match ends after the window");
            assertEquals(3, r.run("abc!" + "q".repeat(200), 0), kind + " dies inside the window");
            assertEquals(-1, r.run("!" + "q".repeat(200), 0), kind + " dies at once");
            // a $ pattern must see the real end, not the window's
            Regex anchored = engine(kind).compile("[a-z]+$");
            assertEquals(w + 10, anchored.run("z".repeat(w + 10), 0), kind + " anchored past the window");
            assertEquals(-1, anchored.run("z".repeat(w + 10) + "1", 0), kind + " anchored, fails at the end");
            assertEquals(-1, anchored.run("z".repeat(w - 1) + "1", 0), kind + " anchored, fails inside");
            // non-String input takes the full copy at once
            assertEquals(w + 5, r.run(new StringBuilder("z".repeat(w + 5)), 0), kind + " StringBuilder");
            // a later, shorter input after a big one must not see stale characters
            assertEquals(2, r.run("zz", 0), kind + " after a long input");
        }
    }

    @Test
    void generatorsAreNamed() {
        assertEquals("BYTECODE", GeneratorKind.BYTECODE.create().name());
        assertEquals("BYTECODE_RANGES", GeneratorKind.BYTECODE_RANGES.create().name());
        assertEquals("BYTECODE_SWITCH", GeneratorKind.BYTECODE_SWITCH.create().name());
        assertTrue(engine(GeneratorKind.BYTECODE).compile("[a-zA-Z_0-9]+").matches("Az_9"));
    }

    private static Firej engine(GeneratorKind kind) {
        return Firej.builder().generator(kind).cache(CacheKind.DISABLED).build();
    }
}
