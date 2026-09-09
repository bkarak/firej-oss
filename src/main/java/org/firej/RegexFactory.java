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

import org.firej.codegen.GeneratorKind;

/**
 * Convenience facade over {@link Firej#standard()}.
 */
public final class RegexFactory {
    private RegexFactory() {
    }

    public static Regex createRegex(String regex) {
        return Firej.standard().compile(regex);
    }

    public static Regex createRegex(String regex, GeneratorKind generator) {
        return Firej.builder().generator(generator).build().compile(regex);
    }
}
