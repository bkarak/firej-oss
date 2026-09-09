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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.firej.codegen.GeneratorKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CapturingGroupTest {

    @ParameterizedTest
    @EnumSource(GeneratorKind.class)
    void twoGroups(GeneratorKind generator) {
        Matcher m = compile(generator, "([0-9]+)-([0-9]+)").matcher("12-34");
        assertTrue(m.matches());
        assertEquals(2, m.groupCount());
        assertEquals("12-34", m.group());
        assertEquals("12", m.group(1));
        assertEquals("34", m.group(2));
        assertEquals(0, m.start(1));
        assertEquals(2, m.end(1));
        assertEquals(3, m.start(2));
        assertEquals(5, m.end(2));
    }

    @ParameterizedTest
    @EnumSource(GeneratorKind.class)
    void nested(GeneratorKind generator) {
        Matcher m = compile(generator, "(([0-9]+)-([0-9]+))").matcher("12-34");
        assertTrue(m.matches());
        assertEquals(3, m.groupCount());
        assertEquals("12-34", m.group(1));
        assertEquals("12", m.group(2));
        assertEquals("34", m.group(3));
    }

    @ParameterizedTest
    @EnumSource(GeneratorKind.class)
    void alternationPicksTakenBranch(GeneratorKind generator) {
        Matcher m = compile(generator, "(a|bb)c").matcher("bbc");
        assertTrue(m.matches());
        assertEquals("bb", m.group(1));
    }

    @ParameterizedTest
    @EnumSource(GeneratorKind.class)
    void unusedAlternativeStaysUnset(GeneratorKind generator) {
        Matcher m = compile(generator, "(a)|(b)").matcher("b");
        assertTrue(m.matches());
        assertNull(m.group(1));
        assertEquals(-1, m.start(1));
        assertEquals("b", m.group(2));
    }

    @ParameterizedTest
    @EnumSource(GeneratorKind.class)
    void optionalGroupUnset(GeneratorKind generator) {
        Matcher m = compile(generator, "(a)?b").matcher("b");
        assertTrue(m.matches());
        assertEquals(1, m.groupCount());
        assertNull(m.group(1));
        assertEquals(-1, m.start(1));
        assertEquals(-1, m.end(1));
    }

    @ParameterizedTest
    @EnumSource(GeneratorKind.class)
    void optionalGroupSet(GeneratorKind generator) {
        Matcher m = compile(generator, "(a)?b").matcher("ab");
        assertTrue(m.matches());
        assertEquals("a", m.group(1));
    }

    @ParameterizedTest
    @EnumSource(GeneratorKind.class)
    void repeatedGroupKeepsLastIteration(GeneratorKind generator) {
        Matcher m = compile(generator, "(a)+").matcher("aaa");
        assertTrue(m.matches());
        assertEquals("a", m.group(1));
        assertEquals(2, m.start(1));
        assertEquals(3, m.end(1));
    }

    @ParameterizedTest
    @EnumSource(GeneratorKind.class)
    void findFillsGroupsAtEachHit(GeneratorKind generator) {
        Matcher m = compile(generator, "([0-9]+)").matcher("ab12cd34");
        assertTrue(m.find());
        assertEquals("12", m.group(1));
        assertEquals(2, m.start(1));
        assertTrue(m.find());
        assertEquals("34", m.group(1));
        assertEquals(6, m.start(1));
    }

    @ParameterizedTest
    @EnumSource(GeneratorKind.class)
    void escapedParensAreLiteral(GeneratorKind generator) {
        Matcher m = compile(generator, "\\(([0-9]+)\\)").matcher("(42)");
        assertTrue(m.matches());
        assertEquals(1, m.groupCount());
        assertEquals("42", m.group(1));
    }

    @ParameterizedTest
    @EnumSource(GeneratorKind.class)
    void emptyGroup(GeneratorKind generator) {
        Matcher m = compile(generator, "a()b").matcher("ab");
        assertTrue(m.matches());
        assertEquals("", m.group(1));
        assertEquals(1, m.start(1));
        assertEquals(1, m.end(1));
    }

    @Test
    void noCapturesMeansGroupCountZero() {
        Regex r = Regex.compile("abc");
        assertEquals(0, r.groupCount());
        assertTrue(r.matches("abc"));
        assertEquals("abc", r.getMatchResult().group());
    }

    @Test
    void outOfRangeGroupThrows() {
        Matcher m = Pattern.compile("(a)").matcher("a");
        assertTrue(m.matches());
        assertThrows(IndexOutOfBoundsException.class, () -> m.group(2));
        assertThrows(IndexOutOfBoundsException.class, () -> m.start(-1));
    }

    @Test
    void patternReportsGroupCount() {
        assertEquals(2, Pattern.compile("(a)-(b)").groupCount());
        assertEquals(0, Pattern.compile("ab").groupCount());
    }

    private static Pattern compile(GeneratorKind generator, String regex) {
        Firej engine = Firej.builder().generator(generator).cache(org.firej.cache.CacheKind.DISABLED).build();
        return Pattern.compile(regex, engine);
    }
}
