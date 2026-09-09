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

import java.util.List;

import org.firej.codegen.GeneratorKind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class InterpreterBytecodeParityTest {

    @ParameterizedTest
    @EnumSource(value = GeneratorKind.class, names = {"BYTECODE", "BYTECODE_RANGES", "BYTECODE_SWITCH"})
    void bytecodeAgreesWithInterpreter(GeneratorKind kind) {
        Firej interp = Firej.builder().generator(GeneratorKind.INTERPRETER).build();
        Firej asm = Firej.builder().generator(kind).build();

        List<String[]> cases = List.of(
                new String[] {"[0-9]*", "555555555"},
                new String[] {"[0-9]*", ""},
                new String[] {"[0-9]*", "55a"},
                new String[] {"[0-9]+", "123"},
                new String[] {"[0-9]+", ""},
                new String[] {"abc", "abc"},
                new String[] {"abc", "ab"},
                new String[] {"a|b", "a"},
                new String[] {"a|b", "b"},
                new String[] {"a|b", "c"},
                new String[] {".*", "xyz"},
                new String[] {"[A-Za-z0-9]{8}-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}-[A-Za-z0-9]{12}",
                        "BFDB4D31-3E35-4DAB-AFCA-5E6E5C8F61EA"},
                new String[] {"\\d+", "42"},
                // more than two ranges in a state: class-map dispatch
                new String[] {"[a-zA-Z_0-9]+@[a-zA-Z_]+\\.[a-zA-Z]{2,3}", "joe@aol.com"},
                new String[] {"[a-zA-Z_0-9]+@[a-zA-Z_]+\\.[a-zA-Z]{2,3}", "joe@aol.c"},
                new String[] {"[a-zA-Z_0-9]+@[a-zA-Z_]+\\.[a-zA-Z]{2,3}", "jöe@aol.com"},
                new String[] {"[^\\n]+", "any\u00e9\uffffchars"},
                new String[] {"[^\\n]+", "line\nbreak"},
                new String[] {"(x|y|z|[0-9])*end", "xyz9end"},
                new String[] {"(x|y|z|[0-9])*end", "\uffff"}
        );

        for (String[] c : cases) {
            int expected = interp.compile(c[0]).run(c[1], 0);
            int actual = asm.compile(c[0]).run(c[1], 0);
            assertEquals(expected, actual, () -> "pattern=" + c[0] + " input=" + c[1]);
        }
    }
}
