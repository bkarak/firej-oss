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

import java.util.Objects;

import org.firej.cache.CacheKind;
import org.firej.cache.RegexCache;
import org.firej.cache.RegexTemplate;
import org.firej.codegen.CodeGenerator;
import org.firej.codegen.GeneratorKind;
import org.firej.dfa.DFA;
import org.firej.dfa.FlattenedDfa;
import org.firej.dfa.Parser;
import org.firej.dfa.PreProcessor;
import org.firej.parser.ParserKind;

/**
 * A configured FIRE/J engine: parser, code generator, and compiled-regex cache.
 *
 * <p>The default engine ({@link #standard()}) compiles each pattern to a
 * tailor-made class via ASM and caches the resulting template. Call
 * {@link #compile(String)} (or {@link Regex#compile(String)}) to obtain a
 * matcher instance. Matching mutates the instance; do not share one {@link Regex}
 * across threads.
 */
public final class Firej {
    private static final Firej STANDARD = builder().build();

    private final Parser parser;
    private final CodeGenerator generator;
    private final RegexCache cache;

    Firej(Parser parser, CodeGenerator generator, RegexCache cache) {
        this.parser = parser;
        this.generator = generator;
        this.cache = cache;
    }

    public static Firej standard() {
        return STANDARD;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * A fresh matcher for {@code pattern}, compiled on a cache miss.
     */
    public Regex compile(String pattern) {
        return template(pattern).newInstance();
    }

    /**
     * The compiled template for {@code pattern}: hold it to create matchers
     * without going back through the cache (which may be disabled or evicted).
     */
    public RegexTemplate template(String pattern) {
        Objects.requireNonNull(pattern, "pattern");
        RegexTemplate template = cache.get(pattern);
        if (template == null) {
            template = compileTemplate(pattern);
            cache.put(pattern, template);
        }
        return template;
    }

    public String toJavaSource(String pattern) {
        return org.firej.codegen.JavaSourceRenderer.render(flatten(pattern));
    }

    public void clearCache() {
        cache.clear();
    }

    private RegexTemplate compileTemplate(String pattern) {
        FlattenedDfa dfa = flatten(pattern);
        try {
            return generator.compile(dfa);
        } catch (RuntimeException e) {
            throw new RegexCompilationException("Failed to compile regex: " + pattern, e);
        }
    }

    private FlattenedDfa flatten(String pattern) {
        PreProcessor.Processed processed = parser.preprocess(pattern);
        DFA dfa = parser.getDFA(processed.expression(), 0);
        if (dfa == null) {
            throw new RegexCompilationException("Parser produced no DFA for: " + pattern);
        }
        return FlattenedDfa.from(pattern, dfa, processed.anchoredStart(), processed.anchoredEnd());
    }

    public static final class Builder {
        private ParserKind parserKind = ParserKind.AUTOMATON;
        private GeneratorKind generatorKind = GeneratorKind.BYTECODE;
        private CacheKind cacheKind = CacheKind.MEMORY;
        private Parser parser;
        private CodeGenerator generator;
        private RegexCache cache;

        public Builder parser(ParserKind kind) {
            this.parserKind = kind;
            return this;
        }

        public Builder parser(Parser parser) {
            this.parser = parser;
            return this;
        }

        public Builder generator(GeneratorKind kind) {
            this.generatorKind = kind;
            return this;
        }

        public Builder generator(CodeGenerator generator) {
            this.generator = generator;
            return this;
        }

        public Builder cache(CacheKind kind) {
            this.cacheKind = kind;
            return this;
        }

        public Builder cache(RegexCache cache) {
            this.cache = cache;
            return this;
        }

        public Firej build() {
            Parser p = parser != null ? parser : parserKind.create();
            CodeGenerator g = generator != null ? generator : generatorKind.create();
            RegexCache c = cache != null ? cache : cacheKind.create();
            return new Firej(p, g, c);
        }
    }
}
