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

/**
 * Indenting string buffer used by DOT and Java source renderers.
 */
public final class SourceBuilder {
    private static final String[] TABS = {
            "", "\t", "\t\t", "\t\t\t", "\t\t\t\t", "\t\t\t\t\t", "\t\t\t\t\t\t"
    };
    private final StringBuilder buf = new StringBuilder();

    public void appendln() {
        buf.append('\n');
    }

    public void appendln(String str) {
        buf.append(str).append('\n');
    }

    public void append(int indent, String str) {
        if (indent < TABS.length) {
            buf.append(TABS[indent]).append(str);
        } else {
            buf.append(TABS[TABS.length - 1]);
            buf.append("\t".repeat(indent - (TABS.length - 1)));
            buf.append(str);
        }
    }

    public void appendln(int indent, String str) {
        append(indent, str);
        buf.append('\n');
    }

    @Override
    public String toString() {
        return buf.toString();
    }
}
