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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.firej.tools.Regex101Benchmark;
import org.firej.tools.Regex101Benchmark.Result;
import org.junit.jupiter.api.Test;

class Regex101BenchmarkTest {

    @Test
    void runsFirejAgainstJdkOnCorpus() {
        StringBuilder out = new StringBuilder();
        Result result = Regex101Benchmark.run(5, out);
        assertTrue(result.loaded() > 1000, result.summary());
        assertTrue(result.comparable() > 50, result.summary());
        assertTrue(result.fireCompileMs() >= 0, result.summary());
        assertTrue(result.jdkCompileMs() >= 0, result.summary());
        assertTrue(result.fireExecMs() >= 0, result.summary());
        assertTrue(result.jdkExecMs() >= 0, result.summary());
        assertFalse(out.isEmpty());
        assertTrue(out.toString().contains("compile fire:"));
        assertTrue(out.toString().contains("exec fire:"));
        assertTrue(out.toString().contains("IPv4"), result.summary());

        // The forked run rebuilds each child's Result from its #result line;
        // every field the averaged summary prints must survive the round trip.
        Result parsed = Result.parse(result.machineLine());
        assertEquals(result.loaded(), parsed.loaded());
        assertEquals(result.comparable(), parsed.comparable());
        assertEquals(result.iterations(), parsed.iterations());
        assertEquals(result.fireCompileNs(), parsed.fireCompileNs());
        assertEquals(result.jdkExecNs(), parsed.jdkExecNs());
        assertEquals(result.ipv4().fireCompileNs(), parsed.ipv4().fireCompileNs());
        assertEquals(result.apache().jdkMatchNs(), parsed.apache().jdkMatchNs(), 1.0);
        assertEquals(result.fireMatch().q1(), parsed.fireMatch().q1(), 1e-4);
        assertEquals(result.fireMatch().max(), parsed.fireMatch().max(), 1e-4);
        assertEquals(result.jdkCompile().avg(), parsed.jdkCompile().avg(), 1e-4);
        // The line rounds to 4 decimals / whole nanoseconds, so the summaries agree
        // in shape but not necessarily in the last digit; compare a few fields instead.
        assertTrue(parsed.summary().contains("regex101.data rows: " + result.loaded()));
        assertTrue(parsed.summary().contains("exec iterations/pattern: " + result.iterations()));
    }
}
