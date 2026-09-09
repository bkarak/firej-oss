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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.firej.cache.CacheKind;
import org.firej.codegen.GeneratorKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code ^} and {@code $} are lifted out of the pattern and enforced by the
 * generated code: a match may only start at 0, or must reach the end.
 */
class AnchorTest {

    @ParameterizedTest
    @EnumSource(GeneratorKind.class)
    void bothAnchors(GeneratorKind generator) {
        Pattern p = compile(generator, "^ab$");
        assertTrue(p.matches("ab"));
        assertFalse(p.matches("abc"));
        assertFalse(p.matcher("abc").lookingAt());
        assertFalse(p.matcher("xab").find());
        assertTrue(p.matcher("ab").find());
    }

    @ParameterizedTest
    @EnumSource(GeneratorKind.class)
    void startAnchorOnlyMatchesAtOffsetZero(GeneratorKind generator) {
        Pattern p = compile(generator, "^ab");
        assertTrue(p.matcher("abc").lookingAt());
        assertFalse(p.matcher("xab").find());
        Matcher m = p.matcher("abab");
        assertTrue(m.find());
        assertEquals(0, m.start());
        assertFalse(m.find());
    }

    @ParameterizedTest
    @EnumSource(GeneratorKind.class)
    void endAnchorRequiresTheMatchToReachTheEnd(GeneratorKind generator) {
        Pattern p = compile(generator, "ab$");
        assertTrue(p.matches("ab"));
        assertFalse(p.matcher("abc").lookingAt());
        Matcher m = p.matcher("xab");
        assertTrue(m.find());
        assertEquals(1, m.start());
        assertEquals(3, m.end());
    }

    @ParameterizedTest
    @EnumSource(GeneratorKind.class)
    void endAnchorDoesNotFallBackToAShorterMatch(GeneratorKind generator) {
        // Longest match of a* on "aab" is "aa"; with $ that is a failure, not "a".
        Regex r = engine(generator).compile("a*$");
        assertEquals(-1, r.run("aab", 0));
        assertEquals(3, r.run("aaa", 0));
        assertEquals(0, r.run("", 0));
    }

    @ParameterizedTest
    @EnumSource(GeneratorKind.class)
    void emptyAnchoredPattern(GeneratorKind generator) {
        Pattern p = compile(generator, "^$");
        assertTrue(p.matches(""));
        assertFalse(p.matches("a"));
    }

    @ParameterizedTest
    @EnumSource(GeneratorKind.class)
    void anchorsWithGroups(GeneratorKind generator) {
        Matcher m = compile(generator, "^([0-9]+)-([0-9]+)$").matcher("12-34");
        assertTrue(m.matches());
        assertEquals("12", m.group(1));
        assertEquals("34", m.group(2));
    }

    @Test
    void escapedAndClassedAnchorsAreLiteral() {
        assertTrue(Regex.compile("\\$").matches("$"));
        assertTrue(Regex.compile("\\^").matches("^"));
        assertTrue(Regex.compile("[$^]+").matches("$^"));
        assertTrue(Regex.compile("[^$]").matches("a"));
        assertFalse(Regex.compile("[^$]").matches("$"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a^b", "a$b", "(^a)", "(a$)", "^a|b", "a|b$", "^^a", "a$$"})
    void anchorsElsewhereAreRejected(String pattern) {
        assertThrows(RegexCompilationException.class, () -> Regex.compile(pattern));
    }

    @Test
    void javaSourceShowsTheChecks() {
        String src = Firej.standard().toJavaSource("^a$");
        assertTrue(src.contains("if (offset != 0)"));
        assertTrue(src.contains("returnValue == len ? returnValue : -1"));
    }

    private static Firej engine(GeneratorKind generator) {
        return Firej.builder().generator(generator).cache(CacheKind.DISABLED).build();
    }

    private static Pattern compile(GeneratorKind generator, String regex) {
        return Pattern.compile(regex, engine(generator));
    }
}
