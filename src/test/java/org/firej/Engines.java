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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.firej.codegen.GeneratorKind;
import org.firej.parser.Pipeline;

/**
 * Every engine configuration the library ships: each pipeline with each
 * generator. Tests that state a behaviour of the library, rather than of one
 * back end, run against all of them.
 */
record Engines(Pipeline pipeline, GeneratorKind generator, Firej firej) {

    private static final List<Engines> ALL = build();

    private static List<Engines> build() {
        List<Engines> all = new ArrayList<>();
        for (Pipeline p : Pipeline.values()) {
            for (GeneratorKind g : GeneratorKind.values()) {
                all.add(new Engines(p, g, Firej.builder().pipeline(p).generator(g).build()));
            }
        }
        return List.copyOf(all);
    }

    static Stream<Engines> all() {
        return ALL.stream();
    }

    Pattern compile(String regex) {
        return Pattern.compile(regex, firej);
    }

    @Override
    public String toString() {
        return pipeline + "/" + generator;
    }
}
