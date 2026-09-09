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
package org.firej.capture;

/**
 * Brics-dialect AST used only to recover capturing-group offsets after the DFA
 * has already found the match span.
 */
sealed interface Expr {

    record Empty() implements Expr {
    }

    record Nothing() implements Expr {
    }

    record Any() implements Expr {
    }

    record AnyString() implements Expr {
    }

    record Ch(char c) implements Expr {
    }

    record Str(String s) implements Expr {
    }

    record Cls(boolean negated, Span[] spans) implements Expr {
        boolean contains(char c) {
            boolean in = false;
            for (Span span : spans) {
                if (c >= span.min && c <= span.max) {
                    in = true;
                    break;
                }
            }
            return negated != in;
        }
    }

    record Span(int min, int max) {
    }

    record Concat(Expr[] parts) implements Expr {
    }

    record Alt(Expr[] alts) implements Expr {
    }

    record Repeat(Expr child, int min, int max) implements Expr {
    }

    record Capture(int index, Expr child) implements Expr {
    }

    record Intersect(Expr left, Expr right) implements Expr {
    }

    record Complement(Expr child) implements Expr {
    }

    record Interval(int min, int max, int digits) implements Expr {
    }
}
