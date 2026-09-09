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
package org.firej.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Util {
    private Util() {
    }

    public static String unicode(String str) {
        StringBuilder buf = new StringBuilder(str.length() * 6);
        for (int i = 0; i < str.length(); i++) {
            buf.append(unicode(str.charAt(i)));
        }
        return buf.toString();
    }

    public static String unicode(char c) {
        if (c == '\\') {
            return "\\\\";
        }
        return "\\u%04x".formatted((int) c);
    }

    public static void writeBuffer(byte[] bytes, String filename) {
        try (OutputStream out = Files.newOutputStream(Path.of(filename))) {
            out.write(bytes);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write " + filename, e);
        }
    }

    public static String load(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + file, e);
        }
    }
}
