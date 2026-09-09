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

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.ClassWriter;

import dk.brics.automaton.Automaton;

/**
 * How the corpus filter launches a child JVM. The classpath is
 * taken from where the classes actually loaded from — {@code java.class.path}
 * is Maven's own boot jar under {@code exec:java}, and a hand-written fallback
 * would pin dependency versions a second time outside {@code pom.xml}.
 */
final class ChildJvm {
    private ChildJvm() {
    }

    static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    static String classpath() {
        Set<String> entries = new LinkedHashSet<>();
        for (Class<?> c : List.of(Main.class, Automaton.class, ClassWriter.class)) {
            String location = location(c);
            if (location != null) {
                entries.add(location);
            }
        }
        URL data = Main.class.getResource("/regex101.data");
        if (data != null && "file".equals(data.getProtocol())) {
            try {
                entries.add(Path.of(data.toURI()).getParent().toString());
            } catch (URISyntaxException ignored) {
                // leave it to the src/test/resources fallback in Regex101Data
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    private static String location(Class<?> c) {
        CodeSource source = c.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            return null;
        }
        try {
            return Path.of(source.getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
