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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Differential test against {@code java.util.regex}, on random inputs.
 *
 * <p>The two engines report different matches — the JDK the first one its
 * backtracking finds, FIRE/J the longest — but they recognise the same
 * language, and that is enough for an exact oracle: the longest match from
 * {@code start} is the largest {@code end} for which the JDK says the region
 * {@code [start, end)} matches in full. Non-anchoring, transparent bounds make
 * {@code ^} and {@code $} mean the ends of the whole input, as they do here.
 *
 * <p>From that oracle the test derives what every engine configuration must
 * return from {@code exec} at every offset, what {@code matches} must answer,
 * and the whole sequence {@code find} must walk.
 */
class JdkOracleTest {

    private static final String ALPHABET = "aaaabbbbccd01xz- ";
    private static final int INPUTS = 150;
    private static final int MAX_LENGTH = 12;

    @ParameterizedTest
    @ValueSource(strings = {
            "a", "ab", "a*", "a+b", "(ab)*", "(a|b)*c", "a?b?c?", "[ab]{2,3}",
            "(a|ab)(c|bcd)(d*)", "a{0,2}b{1,}", "(a*b*)*", "((a|b)c)+", "[^a]+", ".*b",
            "a.c", "(aa|a)*", "(a|aa)+b", "c(a|b){3}c", "^a+", "b+$", "^(ab|c)*$",
            "[a-c]*b[a-c]*", "\\d+", "[0-9]{2}-?[0-9]{2}", "x[a-c]|z[a-f]", "x{0,3}",
            "(a|b)*a(a|b){2}", "a*(b|c)a*", "(abc|ab|a)(bc|c)?", "[^ ]+", "\\w+", "\\s*\\S+",
            "(?:a|b)+?c", "[ab]++", "a{3,}", "(x|z)(0|1)*-", "^$", "^", "$", "(ab|ba)+",
            "[abc]{0,4}d", "(a(b(c)?)?)+", "[-x]+", "[^-a-c]*", "\\D\\d", "(a|b|c|d)*d$",
            "((ab)*|c)+", "b(a{1,2}|c{2,})?b", "[a-d]{1,3}[0-9]?", "a|b|c|d|x|z"
    })
    void longestMatchFromEveryOffset(String pattern) {
        java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(pattern);
        Random random = new Random(pattern.hashCode());
        List<String> inputs = new ArrayList<>();
        inputs.add("");
        for (int k = 0; k < INPUTS; k++) {
            StringBuilder b = new StringBuilder();
            int n = random.nextInt(MAX_LENGTH + 1);
            for (int i = 0; i < n; i++) {
                b.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            inputs.add(b.toString());
        }

        List<int[]> oracle = new ArrayList<>();
        for (String input : inputs) {
            oracle.add(longestEnds(jdk, input));
        }

        Engines.all().forEach(engine -> {
            Regex regex = engine.firej().compile(pattern);
            Pattern compiled = engine.compile(pattern);
            for (int k = 0; k < inputs.size(); k++) {
                String input = inputs.get(k);
                int[] expected = oracle.get(k);
                regex.setData(input);
                for (int start = 0; start <= input.length(); start++) {
                    assertEquals(expected[start], regex.exec(start),
                            () -> engine + " /" + pattern + "/ exec on \"" + input + "\"");
                }
                assertEquals(expected[0] == input.length(), compiled.matches(input),
                        () -> engine + " /" + pattern + "/ matches \"" + input + "\"");
                assertEquals(findAll(expected), findAll(compiled.matcher(input)),
                        () -> engine + " /" + pattern + "/ find in \"" + input + "\"");
            }
        });
    }

    /** For every start, the largest end the JDK accepts the region for, or -1. */
    private static int[] longestEnds(java.util.regex.Pattern jdk, String input) {
        int n = input.length();
        int[] ends = new int[n + 1];
        java.util.regex.Matcher m = jdk.matcher(input).useAnchoringBounds(false).useTransparentBounds(true);
        for (int start = 0; start <= n; start++) {
            ends[start] = -1;
            for (int end = n; end >= start; end--) {
                if (m.region(start, end).matches()) {
                    ends[start] = end;
                    break;
                }
            }
        }
        return ends;
    }

    /** The hits {@code find} must produce, derived from the oracle. */
    private static List<String> findAll(int[] ends) {
        List<String> hits = new ArrayList<>();
        int from = 0;
        while (from < ends.length) {
            int start = from;
            while (start < ends.length && ends[start] < 0) {
                start++;
            }
            if (start == ends.length) {
                break;
            }
            hits.add(start + "-" + ends[start]);
            from = ends[start] == start ? start + 1 : ends[start];
        }
        return hits;
    }

    private static List<String> findAll(Matcher m) {
        List<String> hits = new ArrayList<>();
        while (m.find()) {
            hits.add(m.start() + "-" + m.end());
        }
        return hits;
    }
}
