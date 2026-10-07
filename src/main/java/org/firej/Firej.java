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
import org.firej.capture.CapturePlan;
import org.firej.codegen.CodeGenerator;
import org.firej.codegen.GeneratorKind;
import org.firej.codegen.JavaSourceRenderer;
import org.firej.dfa.DFA;
import org.firej.dfa.FlattenedDfa;
import org.firej.dfa.Parser;
import org.firej.dfa.Preprocessor;
import org.firej.dfa.Preprocessor.Dialect;
import org.firej.parser.ParserKind;
import org.firej.parser.Pipeline;
import org.firej.parser.PreprocessorKind;

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

    private final Preprocessor preprocessor;
    private final Parser parser;
    private final CodeGenerator generator;
    private final RegexCache cache;

    Firej(Preprocessor preprocessor, Parser parser, CodeGenerator generator, RegexCache cache) {
        this.preprocessor = preprocessor;
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

    /**
     * The Java source equivalent of the matcher {@code pattern} compiles to
     * (the loop-and-switch form), for reading; it is never compiled.
     */
    public String toJavaSource(String pattern) {
        return JavaSourceRenderer.render(flatten(pattern));
    }

    /**
     * The automaton {@code pattern} compiles to, in the Graphviz DOT language:
     * {@code dot -Tsvg} draws it. States are numbered as the generated code
     * numbers them.
     */
    public String toDot(String pattern) {
        return flatten(pattern).toDot();
    }

    /** The automaton {@code pattern} compiles to, as the code generators see it. */
    public FlattenedDfa automaton(String pattern) {
        return flatten(pattern);
    }

    /** Drops the cached template for {@code pattern}, if any. */
    public void evict(String pattern) {
        cache.remove(pattern);
    }

    public void clearCache() {
        cache.clear();
    }

    /** The configuration, e.g. {@code Firej[IDENTITY > FIRE > BYTECODE, cache MEMORY]}. */
    @Override
    public String toString() {
        return "Firej[" + preprocessor.name() + " > " + parser.name() + " > " + generator.name()
                + ", cache " + cache.name() + "]";
    }

    private RegexTemplate compileTemplate(String pattern) {
        FlattenedDfa dfa = flatten(pattern);
        // Built once here, before anything is cached, so that a pattern whose
        // groups cannot be recovered fails to compile instead of failing on
        // every instantiation, and every matcher of the template shares it.
        CapturePlan capturePlan = CapturePlan.compile(pattern);
        try {
            return generator.compile(dfa, capturePlan);
        } catch (RuntimeException e) {
            throw new RegexCompilationException("Failed to compile regex: " + pattern, e);
        }
    }

    private FlattenedDfa flatten(String pattern) {
        Preprocessor.Processed processed = preprocessor.process(pattern);
        DFA dfa = parser.getDFA(processed.expression());
        if (dfa == null) {
            throw new RegexCompilationException("Parser produced no DFA for: " + pattern);
        }
        return FlattenedDfa.from(pattern, dfa, processed.anchoredStart(), processed.anchoredEnd());
    }

    public static final class Builder {
        private PreprocessorKind preprocessorKind = Pipeline.NATIVE.preprocessor();
        private ParserKind parserKind = Pipeline.NATIVE.parser();
        private Dialect dialect = Dialect.EXTENDED;
        private GeneratorKind generatorKind = GeneratorKind.BYTECODE;
        private CacheKind cacheKind = CacheKind.MEMORY;
        private Parser parser;
        private Preprocessor preprocessor;
        private CodeGenerator generator;
        private RegexCache cache;

        /**
         * Which spelling of a regular expression this engine accepts:
         * {@code EXTENDED} (the default -- POSIX ERE plus {@code \d}, {@code \w},
         * {@code \s} and the other conveniences) or {@code POSIX} (POSIX ERE
         * alone, where a digit is {@code [[:digit:]]} and {@code \d} is an error).
         * The automaton, the generated code and the matching semantics are
         * identical; only the front end differs.
         */
        public Builder dialect(Dialect dialect) {
            this.dialect = dialect;
            return this;
        }

        /**
         * The pipeline's first stage. {@code IDENTITY} rewrites nothing and is what
         * the native engine wants; {@code BRICS} rewrites into the dk.brics.automaton
         * dialect and is required by that engine, whose parser cannot express a
         * character class.
         */
        public Builder preprocessor(PreprocessorKind kind) {
            this.preprocessorKind = kind;
            return this;
        }

        public Builder preprocessor(Preprocessor preprocessor) {
            this.preprocessor = preprocessor;
            return this;
        }

        /** Preprocessor and engine together, by name. */
        public Builder pipeline(Pipeline pipeline) {
            this.preprocessorKind = pipeline.preprocessor();
            this.parserKind = pipeline.parser();
            return this;
        }

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
            // The dk.brics parser cannot read a character class, so handing it a
            // pattern nobody rewrote means \d silently becomes the letter d --
            // exactly the class of defect this pipeline exists to make impossible.
            // Refuse the pairing rather than compile something that lies.
            if (parser == null && preprocessor == null
                    && parserKind == ParserKind.AUTOMATON && preprocessorKind == PreprocessorKind.IDENTITY) {
                throw new IllegalStateException(
                        "PreprocessorKind.IDENTITY cannot feed ParserKind.AUTOMATON: the dk.brics parser has no "
                                + "character classes, so an un-rewritten \\d would be read as the letter d. "
                                + "Use Pipeline.CLASSIC, or Pipeline.NATIVE for the native engine.");
            }
            Parser p = parser != null ? parser : parserKind.create(dialect);
            Preprocessor pre = preprocessor != null ? preprocessor : preprocessorKind.create(dialect);
            CodeGenerator g = generator != null ? generator : generatorKind.create();
            RegexCache c = cache != null ? cache : cacheKind.create();
            return new Firej(pre, p, g, c);
        }
    }
}
