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
 * Indenting string buffer for {@link JavaSourceRenderer}.
 */
final class SourceBuilder {
    private final StringBuilder buf = new StringBuilder();

    void appendln() {
        buf.append('\n');
    }

    void appendln(String str) {
        buf.append(str).append('\n');
    }

    void appendln(int indent, String str) {
        buf.append("\t".repeat(indent)).append(str).append('\n');
    }

    @Override
    public String toString() {
        return buf.toString();
    }
}
