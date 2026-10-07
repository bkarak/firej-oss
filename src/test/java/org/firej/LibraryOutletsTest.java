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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.firej.cache.CacheKind;
import org.firej.cache.RegexCache;
import org.firej.cache.RegexTemplate;
import org.firej.capture.CapturePlan;
import org.firej.codegen.CodeGenerator;
import org.firej.codegen.GeneratorKind;
import org.firej.dfa.DFA;
import org.firej.dfa.FlattenedDfa;
import org.firej.dfa.Parser;
import org.firej.dfa.Preprocessor;
import org.firej.dfa.State;
import org.firej.dfa.Transition;
import org.firej.dfa.fire.IdentityPreprocessor;
import org.firej.parser.ParserKind;
import org.firej.parser.Pipeline;
import org.firej.parser.PreprocessorKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The library's extension points and inspection outlets: what another library
 * builds on. Every stage of the pipeline can be replaced by an implementation
 * of its interface, and every compiled automaton can be looked at — as data,
 * as Graphviz DOT, as Java source.
 */
class LibraryOutletsTest {

    // --- inspection ----------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Pipeline.class)
    void toDotDrawsTheMinimalAutomaton(Pipeline pipeline) {
        String dot = Firej.builder().pipeline(pipeline).build().toDot("(a|b)*abb");
        assertTrue(dot.startsWith("digraph dfa {\n"), dot);
        assertTrue(dot.endsWith("}\n"), dot);
        assertTrue(dot.contains("\tlabel=\"(a|b)*abb\";"), dot);
        assertTrue(dot.contains("\tstart -> 0;"), dot);
        assertEquals(1, count(dot, "doublecircle"), "one accepting state");
        assertEquals(8, count(dot, " -> ") - 1, "two edges from each of four states:\n" + dot);
    }

    @Test
    void toDotMarksTheAnchors() {
        String dot = Firej.standard().toDot("^ab$");
        assertTrue(dot.contains("start -> 0 [label=\"^\"];"), dot);
        assertTrue(dot.contains("[shape=doublecircle,xlabel=\"$\"];"), dot);
    }

    @Test
    void toDotMergesRangesToOneDestinationAndEscapes() {
        String dot = Firej.standard().toDot("[a-cx-z\"\\\\]\\n");
        assertTrue(dot.contains("0 -> 1 [label=\"\\\", \\\\, a-c, x-z\"];"), dot);
        assertTrue(dot.contains("1 -> 2 [label=\"U+000A\"];"), dot);
    }

    @Test
    void anyDfaRendersItself() {
        DFA dfa = ParserKind.FIRE.create(Preprocessor.Dialect.EXTENDED).getDFA("ab|cd");
        String dot = dfa.toDot();
        assertTrue(dot.startsWith("digraph dfa {"), dot);
        assertFalse(dot.contains("\tlabel="), "no pattern, no graph label");
        for (String label : List.of("a", "b", "c", "d")) {
            assertEquals(1, count(dot, "[label=\"" + label + "\"]"), label + " in\n" + dot);
        }
        assertEquals(1, count(dot, "doublecircle"), "the two branches share one accepting state");
    }

    @Test
    void theAutomatonIsAvailableAsData() {
        FlattenedDfa dfa = Firej.standard().automaton("^[0-9]+$");
        assertTrue(dfa.anchoredStart());
        assertTrue(dfa.anchoredEnd());
        assertEquals(2, dfa.stateCount());
        assertFalse(dfa.accept()[dfa.startState()]);
        FlattenedDfa.Range[] out = dfa.transitions()[dfa.startState()];
        assertEquals(1, out.length);
        assertEquals('0', out[0].min());
        assertEquals('9', out[0].max());
        assertTrue(dfa.accept()[out[0].dest()]);
        assertEquals(dfa, Firej.builder().pipeline(Pipeline.CLASSIC).build().automaton("^[0-9]+$"),
                "both engines build the same minimal automaton, numbered the same way");
    }

    @Test
    void renderedJavaSourceCompilesAndAgreesWithTheEngine(@TempDir Path dir) throws Exception {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assumeTrue(javac != null, "needs a JDK");
        String pattern = "^([a-z]+)@([a-z]+)\\.(com|org)$";
        Path source = dir.resolve("GeneratedRegex.java");
        Files.writeString(source, Firej.standard().toJavaSource(pattern));
        Path classes = Path.of(Regex.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertEquals(0, javac.run(null, null, null, "-cp", classes.toString(), "-d", dir.toString(),
                source.toString()), "the rendered source must compile");

        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {dir.toUri().toURL()},
                Regex.class.getClassLoader())) {
            Regex rendered = (Regex) loader.loadClass("GeneratedRegex")
                    .getConstructor(CapturePlan.class).newInstance(CapturePlan.compile(pattern));
            Regex engine = Regex.compile(pattern);
            for (String input : List.of("ada@example.com", "ada@example.net", "x@y.org", "@y.org", "", "a@b.com.")) {
                assertEquals(engine.matches(input), rendered.matches(input), input);
            }
            assertTrue(rendered.matches("ada@example.org"));
            assertEquals("example", rendered.getMatchResult().group(2));
        }
    }

    // --- diagnostics ---------------------------------------------------------

    @Test
    void anEngineSaysWhatItIsMadeOf() {
        assertEquals("Firej[IDENTITY > FIRE > BYTECODE, cache MEMORY]", Firej.standard().toString());
        assertEquals("Firej[BRICS > AUTOMATON > INTERPRETER, cache DISABLED]", Firej.builder()
                .pipeline(Pipeline.CLASSIC).generator(GeneratorKind.INTERPRETER).cache(CacheKind.DISABLED)
                .build().toString());
        for (GeneratorKind g : GeneratorKind.values()) {
            assertEquals(g.name(), g.create().name());
        }
        for (CacheKind c : CacheKind.values()) {
            assertEquals(c.name(), c.create().name());
        }
        for (PreprocessorKind p : PreprocessorKind.values()) {
            assertEquals(p.name(), p.create(Preprocessor.Dialect.EXTENDED).name());
        }
        for (ParserKind p : ParserKind.values()) {
            assertEquals(p.name(), p.create(Preprocessor.Dialect.EXTENDED).name());
        }
    }

    @Test
    void anUnsoundPairingIsRefused() {
        assertThrows(IllegalStateException.class, () -> Firej.builder()
                .preprocessor(PreprocessorKind.IDENTITY).parser(ParserKind.AUTOMATON).build());
    }

    // --- the cache -----------------------------------------------------------

    @Test
    void evictForcesARecompile() {
        Firej engine = Firej.builder().build();
        RegexTemplate first = engine.template("[a-z]+");
        assertSame(first, engine.template("[a-z]+"));
        engine.evict("[a-z]+");
        RegexTemplate second = engine.template("[a-z]+");
        assertNotSame(first, second);
        assertNotSame(first.newInstance().getClass(), second.newInstance().getClass());
        engine.evict("never compiled");
    }

    @ParameterizedTest
    @EnumSource(CacheKind.class)
    void everyCacheKindHonoursItsContract(CacheKind kind) {
        RegexCache cache = kind.create();
        RegexTemplate a = () -> null;
        RegexTemplate b = () -> null;
        cache.put("a", a);
        cache.put("b", b);
        switch (kind) {
            case MEMORY -> {
                assertSame(a, cache.get("a"));
                assertSame(b, cache.get("b"));
            }
            case LAST_INSTANCE -> {
                assertEquals(null, cache.get("a"), "only the last pattern is kept");
                assertSame(b, cache.get("b"));
            }
            case DISABLED -> assertEquals(null, cache.get("b"));
        }
        cache.remove("b");
        assertEquals(null, cache.get("b"));
        cache.clear();
        assertEquals(null, cache.get("a"));
    }

    /** A cache another library might plug in: counts its traffic. */
    private static final class CountingCache implements RegexCache {
        final Map<String, RegexTemplate> map = new ConcurrentHashMap<>();
        final AtomicInteger hits = new AtomicInteger();
        final AtomicInteger misses = new AtomicInteger();
        final List<String> removed = new ArrayList<>();

        @Override
        public RegexTemplate get(String regex) {
            RegexTemplate t = map.get(regex);
            (t == null ? misses : hits).incrementAndGet();
            return t;
        }

        @Override
        public void put(String regex, RegexTemplate template) {
            map.put(regex, template);
        }

        @Override
        public void remove(String regex) {
            removed.add(regex);
            map.remove(regex);
        }

        @Override
        public void clear() {
            map.clear();
        }

        @Override
        public String name() {
            return "COUNTING";
        }
    }

    @Test
    void aCustomCacheIsUsed() {
        CountingCache cache = new CountingCache();
        Firej engine = Firej.builder().cache(cache).build();
        assertTrue(engine.compile("a+").matches("aa"));
        assertTrue(engine.compile("a+").matches("a"));
        assertTrue(Pattern.compile("a+", engine).matches("aaa"));
        assertEquals(1, cache.misses.get());
        assertEquals(2, cache.hits.get());
        engine.evict("a+");
        assertEquals(List.of("a+"), cache.removed);
        assertTrue(engine.toString().endsWith("cache COUNTING]"));
    }

    // --- replacing a stage ---------------------------------------------------

    /**
     * A parser another library might plug in: it ignores the expression and
     * hands over an automaton built by hand, for the language {@code ab*}.
     */
    private static final class HandBuiltParser implements Parser {
        record S(int number, boolean accept, List<Transition> out) implements State {
            @Override
            public Transition[] getTransitions() {
                return out.toArray(Transition[]::new);
            }

            @Override
            public boolean isAccept() {
                return accept;
            }

            @Override
            public int getStateNumber() {
                return number;
            }
        }

        record T(int min, int max, State dest) implements Transition {
            @Override
            public State getDest() {
                return dest;
            }

            @Override
            public int getMin() {
                return min;
            }

            @Override
            public int getMax() {
                return max;
            }
        }

        @Override
        public DFA getDFA(String expression) {
            S start = new S(10, false, new ArrayList<>());
            S tail = new S(20, true, new ArrayList<>());
            start.out().add(new T('a', 'a', tail));
            tail.out().add(new T('b', 'b', tail));
            return new DFA() {
                @Override
                public State[] getStates() {
                    return new State[] {tail, start};
                }

                @Override
                public State getInitialState() {
                    return start;
                }
            };
        }

        @Override
        public String name() {
            return "HAND";
        }
    }

    @ParameterizedTest
    @EnumSource(GeneratorKind.class)
    void aCustomParserFeedsEveryGenerator(GeneratorKind generator) {
        Firej engine = Firej.builder().parser(new HandBuiltParser()).generator(generator).build();
        Pattern p = Pattern.compile("^ab*", engine);
        assertTrue(p.matches("a"));
        assertTrue(p.matches("abbb"));
        assertFalse(p.matches("ba"));
        Matcher m = p.matcher("xabb ab");
        assertFalse(m.find(), "the anchor lifted by the preprocessor still applies");
        assertEquals("Firej[IDENTITY > HAND > " + generator + ", cache MEMORY]", engine.toString());
        assertTrue(engine.toDot("whatever").contains("doublecircle"));
    }

    /** A preprocessor another library might plug in: free-spacing mode. */
    private static final class FreeSpacing implements Preprocessor {
        private final Preprocessor next = new IdentityPreprocessor();

        @Override
        public Processed process(String regex) {
            return next.process(regex.replaceAll("\\s+", ""));
        }

        @Override
        public Dialect dialect() {
            return next.dialect();
        }

        @Override
        public String name() {
            return "FREE_SPACING";
        }
    }

    @Test
    void aCustomPreprocessorRewritesBeforeTheEngine() {
        Firej engine = Firej.builder().preprocessor(new FreeSpacing()).build();
        assertTrue(engine.compile("^ [0-9]{3} - [0-9]{4} $").matches("555-1234"));
        assertTrue(engine.toString().startsWith("Firej[FREE_SPACING > FIRE"));
    }

    /** A generator another library might plug in: counts compiles, delegates. */
    @Test
    void aCustomGeneratorWrapsAnother() {
        AtomicInteger compiles = new AtomicInteger();
        CodeGenerator inner = GeneratorKind.BYTECODE.create();
        CodeGenerator counting = new CodeGenerator() {
            @Override
            public RegexTemplate compile(FlattenedDfa dfa, CapturePlan capturePlan) {
                compiles.incrementAndGet();
                return inner.compile(dfa, capturePlan);
            }

            @Override
            public String name() {
                return "COUNTED_" + inner.name();
            }
        };
        Firej engine = Firej.builder().generator(counting).build();
        Matcher m = Pattern.compile("(\\w+)=(\\w+)", engine).matcher("k=v");
        assertTrue(m.matches());
        assertEquals("v", m.group(2));
        engine.compile("(\\w+)=(\\w+)");
        assertEquals(1, compiles.get(), "the second compile is a cache hit");
    }

    @Test
    void aPatternWhoseGroupsCannotBeRecoveredIsNotCached() {
        CountingCache cache = new CountingCache();
        Firej engine = Firej.builder().cache(cache).build();
        assertThrows(RegexCompilationException.class, () -> engine.compile("([0-9]+)(px|pt|)"));
        assertTrue(cache.map.isEmpty());
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
