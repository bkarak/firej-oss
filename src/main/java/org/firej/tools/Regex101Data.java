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
package org.firej.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loader for {@code regex101.data}: the FIRE/J-compilable slice of
 * huggingface.co/datasets/innovatorved/regex_dataset.
 */
public final class Regex101Data {
    private Regex101Data() {
    }

    public record Sample(int lineNumber, String pattern, String input) {
        @Override
        public String toString() {
            String p = pattern.length() > 80 ? pattern.substring(0, 80) + "…" : pattern;
            String in = input.length() > 40 ? input.substring(0, 40) + "…" : input;
            return "L" + lineNumber + " " + p + " ⇐ " + in;
        }
    }

    public static List<Sample> load() {
        List<Sample> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(open(), StandardCharsets.UTF_8))) {
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
                out.add(new Sample(lineNumber, unescape(line.substring(0, tab)),
                        unescape(line.substring(tab + 1))));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(out);
    }

    private static InputStream open() throws IOException {
        InputStream in = Regex101Data.class.getResourceAsStream("/regex101.data");
        if (in != null) {
            return in;
        }
        Path file = Path.of("src/test/resources/regex101.data");
        if (Files.isRegularFile(file)) {
            return Files.newInputStream(file);
        }
        throw new IllegalStateException("regex101.data not found on the classpath or at " + file);
    }

    static String unescape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                b.append(switch (n) {
                    case 't' -> '\t';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case '\\' -> '\\';
                    default -> n;
                });
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
