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

/**
 * {@link #BYTECODE} is the goto-threaded emitter (the paper's Algorithm 2)
 * with class-map dispatch, and the default. {@link #BYTECODE_RANGES} is the
 * same threading with a compare chain per state instead of the class map;
 * {@link #BYTECODE_SWITCH} is the loop-and-{@code tableswitch} form
 * (Algorithm 1). Both are kept for comparison. {@link #INTERPRETER} walks the
 * flattened tables and is the oracle the three are tested against.
 */
public enum GeneratorKind {
    BYTECODE,
    BYTECODE_RANGES,
    BYTECODE_SWITCH,
    INTERPRETER;

    public CodeGenerator create() {
        return switch (this) {
            case BYTECODE -> new AsmCodeGenerator(true, true);
            case BYTECODE_RANGES -> new AsmCodeGenerator(true, false);
            case BYTECODE_SWITCH -> new AsmCodeGenerator(false);
            case INTERPRETER -> new InterpreterCodeGenerator();
        };
    }
}
