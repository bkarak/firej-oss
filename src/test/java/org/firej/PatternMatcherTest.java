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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.firej.parser.ParserKind;
import org.junit.jupiter.api.Test;

class PatternMatcherTest {

    @Test
    void patternMatchesAndFind() {
        Pattern p = Pattern.compile("[0-9]+");
        assertTrue(p.matches("123"));
        assertFalse(p.matches("12a"));

        Matcher m = p.matcher("ab12cd34");
        assertTrue(m.find());
        assertEquals("12", m.group());
        assertTrue(m.find());
        assertEquals("34", m.group());
        assertFalse(m.find());
    }

    @Test
    void lookingAt() {
        Matcher m = Pattern.compile("[0-9]+").matcher("12abc");
        assertTrue(m.lookingAt());
        assertEquals("12", m.group());
        assertFalse(m.matches());
    }

    @Test
    void groupWithoutMatchThrows() {
        Matcher m = Pattern.compile("a").matcher("b");
        assertFalse(m.matches());
        assertThrows(IllegalStateException.class, m::group);
    }

    @Test
    void nativeParserIsUnsupported() {
        Firej fire = Firej.builder().parser(ParserKind.FIRE).build();
        assertThrows(UnsupportedOperationException.class, () -> fire.compile("a"));
    }

    @Test
    void invalidRegexThrows() {
        assertThrows(RegexCompilationException.class, () -> Regex.compile("["));
    }

    @Test
    void lastInstanceCacheDoesNotReturnWrongPattern() {
        Firej engine = Firej.builder().cache(org.firej.cache.CacheKind.LAST_INSTANCE).build();
        assertTrue(engine.compile("[0-9]+").matches("123"));
        assertTrue(engine.compile("[a-z]+").matches("abc"));
        assertFalse(engine.compile("[a-z]+").matches("123"));
    }

    @Test
    void cacheReturnsFreshInstances() {
        Regex a = Regex.compile("[0-9]+");
        Regex b = Regex.compile("[0-9]+");
        assertTrue(a != b);
        a.setData("111");
        b.setData("222");
        assertTrue(a.matches("111"));
        assertTrue(b.matches("222"));
    }

    @Test
    void findContinuesAfterMatchesAndLookingAt() {
        Matcher m = Pattern.compile("[0-9]+").matcher("12ab34");
        assertTrue(m.lookingAt());
        assertEquals("12", m.group());
        assertTrue(m.find());
        assertEquals("34", m.group());
        assertFalse(m.find());

        Matcher whole = Pattern.compile("[0-9]+").matcher("123");
        assertTrue(whole.matches());
        assertFalse(whole.find());
        assertTrue(whole.reset().find());
    }

    @Test
    void patternHoldsItsTemplateWhenTheCacheIsDisabled() {
        Firej engine = Firej.builder().cache(org.firej.cache.CacheKind.DISABLED).build();
        Pattern p = Pattern.compile("[0-9]+", engine);
        Class<?> first = p.matcher("1").regex().getClass();
        for (int i = 0; i < 5; i++) {
            assertSame(first, p.matcher("1").regex().getClass());
        }
        assertTrue(p.matches("42"));
    }

    @Test
    void largeInputIsNotCopiedPerMatch() {
        String big = "a".repeat(200_000);
        Regex r = Regex.compile("a*");
        r.setData(big);
        assertSame(big, r.getData());
        assertEquals(big.length(), r.run(0));
    }

    @Test
    void javaSourceContainsSwitch() {
        String src = Firej.standard().toJavaSource("[0-9]+");
        assertTrue(src.contains("switch (state)"));
        assertTrue(src.contains("walk"));
    }
}
