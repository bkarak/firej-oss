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

import org.firej.dfa.FlattenedDfa;
import org.firej.dfa.FlattenedDfa.Range;
import org.firej.util.SourceBuilder;

/**
 * Renders a flattened DFA as Java source, equivalent to the old Velocity
 * {@code javaSource.vm} template. Used for inspection, not for compilation.
 */
public final class JavaSourceRenderer {
    private JavaSourceRenderer() {
    }

    public static String render(FlattenedDfa dfa) {
        String ret = "pos = i; " + (dfa.anchoredEnd() ? "return returnValue == len ? returnValue : -1;" : "return returnValue;");
        SourceBuilder src = new SourceBuilder();
        src.appendln("public final class GeneratedRegex extends org.firej.runtime.CharArrayRegex {");
        src.appendln(1, "public GeneratedRegex() {");
        src.appendln(2, "super(" + quote(dfa.pattern()) + ");");
        src.appendln(1, "}");
        src.appendln();
        src.appendln(1, "@Override");
        src.appendln(1, "protected int walk(int offset) {");
        if (dfa.anchoredStart()) {
            src.appendln(2, "if (offset != 0) {");
            src.appendln(3, "return -1;");
            src.appendln(2, "}");
        }
        src.appendln(2, "final char[] arr = arrayBuffer;");
        src.appendln(2, "final int len = length;");
        src.appendln(2, "int state = " + dfa.startState() + ";");
        src.appendln(2, "int returnValue = " + (dfa.accept()[dfa.startState()] ? "offset" : "-1") + ";");
        src.appendln(2, "for (int i = offset; i < len; i++) {");
        src.appendln(3, "int c = arr[i];");
        src.appendln(3, "switch (state) {");
        for (int s = 0; s < dfa.stateCount(); s++) {
            src.appendln(4, "case " + s + ":");
            for (Range edge : dfa.transitions()[s]) {
                if (edge.isSingle()) {
                    src.appendln(5, "if (c == " + edge.min() + ") {");
                } else {
                    src.appendln(5, "if (c >= " + edge.min() + " && c <= " + edge.max() + ") {");
                }
                src.appendln(6, "state = " + edge.dest() + ";");
                if (dfa.accept()[edge.dest()]) {
                    src.appendln(6, "returnValue = i + 1;");
                }
                src.appendln(6, "break;");
                src.appendln(5, "}");
            }
            src.appendln(5, ret);
        }
        src.appendln(3, "}");
        src.appendln(2, "}");
        src.appendln(2, "int i = len;");
        src.appendln(2, ret);
        src.appendln(1, "}");
        src.appendln("}");
        return src.toString();
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
