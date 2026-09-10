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
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.firej;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.firej.codegen.AsmCodeGenerator;
import org.firej.tools.ExpressionMetrics;
import org.firej.tools.SizeBenchmark;
import org.firej.tools.SizeBenchmark.Config;
import org.firej.tools.SizeBenchmark.Engine;
import org.firej.tools.SizeBenchmark.Row;
import org.firej.tools.SizeBenchmark.Shape;
import org.firej.tools.SizeBenchmark.Timing;
import org.junit.jupiter.api.Test;

class SizeBenchmarkTest {

    @Test
    void shapeMeasuresTheExpressionAndTheAutomaton() {
        Shape literal = SizeBenchmark.shape("abcd");
        assertEquals(4, literal.chars());
        assertEquals(4, literal.size());
        assertEquals(4, literal.length());
        assertEquals(5, literal.states());
        assertEquals(4, literal.ranges());
        assertEquals(1, literal.maxRanges());
        assertTrue(literal.bytesMap() > 0 && literal.bytesRanges() > 0 && literal.bytesSwitch() > 0);

        Shape blowup = SizeBenchmark.shape("(a|b)*a(a|b){3}");
        assertEquals(16, blowup.states());
        assertEquals(2, blowup.maxRanges());
        assertEquals(5, blowup.size());
        assertEquals(5, blowup.length());

        // Each state of [a-zA-Z0-9_]{n} has four ranges, so it dispatches through the class map,
        // and the walk grows with n.
        Shape ident8 = SizeBenchmark.shape("[a-zA-Z0-9_]{8}");
        Shape ident64 = SizeBenchmark.shape("[a-zA-Z0-9_]{64}");
        assertEquals(9, ident8.states());
        assertEquals(4, ident8.maxRanges());
        assertTrue(ident64.bytesMap() > 4 * ident8.bytesMap());

        // The threaded emitters split a big walk into methods under the JIT's limit;
        // the switch emitter is one method and reports -1 past the JVM's 64 KiB cap.
        Shape huge = SizeBenchmark.shape("(a|b)*a(a|b){11}");
        assertEquals(4096, huge.states());
        assertTrue(huge.bytesMap() > 65536, "total " + huge.bytesMap());
        assertTrue(huge.segments() > 8, "segments " + huge.segments());
        assertTrue(huge.largest() <= AsmCodeGenerator.JIT_LIMIT, "largest " + huge.largest());
        assertTrue(huge.bytesRanges() > 65536);
        assertEquals(-1, huge.bytesSwitch());
        assertEquals(1, ident8.segments());
        assertEquals(ident8.bytesMap(), ident8.largest());
    }

    @Test
    void ehrenfeuchtZeigerMeasures() {
        assertEquals(new ExpressionMetrics.Complexity(3, 10), ExpressionMetrics.of("[0-9]{5}-[0-9]{4}"));
        assertEquals(new ExpressionMetrics.Complexity(19, 15), ExpressionMetrics.of(
                "(([01]?[0-9][0-9]?|2[0-4][0-9]|25[0-5])\\.){3}([01]?[0-9][0-9]?|2[0-4][0-9]|25[0-5])"));
        assertEquals(new ExpressionMetrics.Complexity(2, 1), ExpressionMetrics.of("^(a|b)*$"));
    }

    @Test
    void measuresSyntheticFamiliesAndCorpusSlices() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        PrintStream log = new PrintStream(OutputStream.nullOutputStream());
        List<Row> rows = SizeBenchmark.measure(Config.smoke(), "test", out, log);
        assertFalse(rows.isEmpty());

        Row literal4 = rows.stream().filter(r -> r.family().equals("literal") && r.n() == 4).findFirst().orElseThrow();
        assertEquals(5, literal4.shape().states());
        assertEquals(4, literal4.inputLen());
        for (Engine e : Engine.values()) {
            Timing t = literal4.timings().get(e);
            assertEquals("ok", t.status(), e.name());
            assertEquals(4, t.end(), e.name());
            assertTrue(t.matchNs() > 0, e.name());
            assertTrue(t.compileNs() > 0, e.name());
        }
        Row blowup3 = rows.stream().filter(r -> r.family().equals("blowup") && r.n() == 3).findFirst().orElseThrow();
        assertEquals(16, blowup3.shape().states());
        assertEquals(blowup3.inputLen(), blowup3.timings().get(Engine.JDK).end());
        assertEquals(blowup3.inputLen(), blowup3.timings().get(Engine.BRICS).end());
        assertTrue(rows.stream().anyMatch(r -> r.family().equals(SizeBenchmark.CORPUS_2007)));
        assertTrue(rows.stream().anyMatch(r -> r.family().equals(SizeBenchmark.CORPUS_101)));

        // Every #row line the child prints must come back as the same row in the parent.
        int decoded = 0;
        for (String line : bytes.toString(StandardCharsets.UTF_8).split("\n")) {
            if (!line.startsWith("#row\t")) {
                continue;
            }
            Row back = Row.decode(line);
            Row original = rows.get(decoded++);
            assertEquals(original.pattern(), back.pattern());
            assertEquals(original.shape(), back.shape());
            assertEquals(original.timings().get(Engine.FIRE).matchNs(), back.timings().get(Engine.FIRE).matchNs(), 1e-3);
            assertEquals(original.timings().get(Engine.JDK).status(), back.timings().get(Engine.JDK).status());
            assertEquals(line, back.encode());
        }
        assertEquals(rows.size(), decoded);

        String summary = SizeBenchmark.summary(rows);
        assertTrue(summary.contains("literal:"), summary);
        assertTrue(summary.contains("by walk bytes"), summary);
        assertTrue(summary.contains("JIT cost"), summary);
    }
}
