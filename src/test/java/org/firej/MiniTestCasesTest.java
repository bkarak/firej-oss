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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.firej.codegen.GeneratorKind;
import org.firej.tools.MiniTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class MiniTestCasesTest {

    @ParameterizedTest
    @EnumSource(GeneratorKind.class)
    void originalMiniTestCasesMatch(GeneratorKind generator) {
        Firej engine = Firej.builder().generator(generator).cache(org.firej.cache.CacheKind.DISABLED).build();
        for (Map.Entry<String, String> e : MiniTest.cases().entrySet()) {
            Regex r = engine.compile(e.getKey());
            assertTrue(r.matches(e.getValue()), () -> generator + " failed on " + e.getKey());
        }
    }

    @Test
    void factoryDefaultMatchesIpv4() {
        assertTrue(RegexFactory.createRegex(
                "(([01]?[0-9][0-9]?|2[0-4][0-9]|25[0-5])\\.){3}([01]?[0-9][0-9]?|2[0-4][0-9]|25[0-5])")
                .matches("193.92.177.2"));
    }

    @Test
    void simpleEscapedParens() {
        assertTrue(Regex.compile("\\(ABCDEF\\).").matches("(ABCDEF)."));
    }

    @Test
    void digitsStarMatchesEmptyAndDigits() {
        Regex r = Regex.compile("[0-9]*");
        assertTrue(r.matches(""));
        assertTrue(r.matches("555"));
        assertFalse(r.matches("55a"));
        assertEquals(2, r.run("55a", 0));
    }

    @Test
    void plusRequiresAtLeastOne() {
        Regex r = Regex.compile("[0-9]+");
        assertFalse(r.matches(""));
        assertTrue(r.matches("0"));
        assertFalse(r.lookingAt("a1"));
    }
}
