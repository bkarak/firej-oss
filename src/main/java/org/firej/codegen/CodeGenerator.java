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
package org.firej.codegen;

import org.firej.cache.RegexTemplate;
import org.firej.capture.CapturePlan;
import org.firej.dfa.FlattenedDfa;

public interface CodeGenerator {
    /**
     * A template whose instances walk {@code dfa} and recover groups with
     * {@code capturePlan}, which every instance shares.
     */
    RegexTemplate compile(FlattenedDfa dfa, CapturePlan capturePlan);

    /** A short name, for diagnostics. */
    String name();
}
