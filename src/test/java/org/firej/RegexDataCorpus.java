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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The 2007 {@code regex.data} dump: {@code pattern<TAB>input} rows. Mixed
 * Perl/POSIX, so many rows are not a FIRE/J match oracle — only a pattern list
 * plus one sample string.
 */
final class RegexDataCorpus {
    private RegexDataCorpus() {
    }

    record Sample(int lineNumber, String pattern, String input) {
        @Override
        public String toString() {
            String p = pattern.length() > 80 ? pattern.substring(0, 80) + "…" : pattern;
            return "L" + lineNumber + " " + p + " ⇐ " + input;
        }
    }

    static List<Sample> load() {
        List<Sample> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(RegexDataCorpus.class.getResourceAsStream("/regex.data"),
                        "missing /regex.data on the test classpath"),
                StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                out.add(new Sample(lineNumber, line.substring(0, tab), line.substring(tab + 1)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(out);
    }

    static Stream<Sample> samples() {
        return load().stream();
    }
}
