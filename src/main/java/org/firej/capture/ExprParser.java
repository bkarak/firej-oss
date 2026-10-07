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

import java.util.ArrayList;
import java.util.List;

import org.firej.capture.Expr.Capture;
import org.firej.capture.Expr.Ch;
import org.firej.capture.Expr.Cls;
import org.firej.capture.Expr.Complement;
import org.firej.capture.Expr.Concat;
import org.firej.capture.Expr.Empty;
import org.firej.capture.Expr.Intersect;
import org.firej.capture.Expr.Interval;
import org.firej.capture.Expr.Nothing;
import org.firej.capture.Expr.Repeat;
import org.firej.capture.Expr.Span;
import org.firej.capture.Expr.Str;
import org.firej.dfa.Preprocessor;
import org.firej.dfa.BricsPreprocessor;

/**
 * Parses the preprocessed Brics dialect. Unlike Brics, {@code (...)} becomes a
 * {@link Capture} (left-to-right, 1-based).
 */
final class ExprParser {
    static final int UNBOUNDED = Integer.MAX_VALUE;

    private final String src;
    private final java.util.BitSet nonCapturing;
    private int pos;
    private int nextGroup = 1;
    private int groupOrdinal;

    private ExprParser(String src, java.util.BitSet nonCapturing) {
        this.src = src;
        this.nonCapturing = nonCapturing;
    }

    static Parsed parse(String pattern) {
        Preprocessor.Processed p = BricsPreprocessor.getInstance().process(pattern);   // EXTENDED: the widest reading
        String processed = p.expression();
        if (processed.isEmpty()) {
            return new Parsed(new Empty(), 0);
        }
        ExprParser parser = new ExprParser(processed, p.nonCapturing());
        Expr root = parser.parseUnion();
        if (parser.more()) {
            throw new IllegalArgumentException("trailing input at position " + parser.pos);
        }
        return new Parsed(root, parser.nextGroup - 1);
    }

    record Parsed(Expr root, int groupCount) {
    }

    private Expr parseUnion() {
        Expr e = parseInter();
        if (match('|')) {
            List<Expr> alts = new ArrayList<>();
            alts.add(e);
            alts.add(parseUnion());
            return flattenAlt(alts);
        }
        return e;
    }

    private static Expr flattenAlt(List<Expr> alts) {
        List<Expr> flat = new ArrayList<>();
        for (Expr a : alts) {
            if (a instanceof Expr.Alt alt) {
                flat.addAll(List.of(alt.alts()));
            } else {
                flat.add(a);
            }
        }
        return new Expr.Alt(flat.toArray(Expr[]::new));
    }

    private Expr parseInter() {
        Expr e = parseConcat();
        if (match('&')) {
            return new Intersect(e, parseInter());
        }
        return e;
    }

    private Expr parseConcat() {
        Expr e = parseRepeat();
        if (more() && !peek(")|") && !peek("&")) {
            List<Expr> parts = new ArrayList<>();
            appendConcat(parts, e);
            appendConcat(parts, parseConcat());
            return new Concat(parts.toArray(Expr[]::new));
        }
        return e;
    }

    private static void appendConcat(List<Expr> parts, Expr e) {
        if (e instanceof Concat c) {
            parts.addAll(List.of(c.parts()));
        } else {
            parts.add(e);
        }
    }

    private Expr parseRepeat() {
        Expr e = parseCompl();
        while (peek("?*+{")) {
            if (match('?')) {
                e = new Repeat(e, 0, 1);
            } else if (match('*')) {
                e = new Repeat(e, 0, UNBOUNDED);
            } else if (match('+')) {
                e = new Repeat(e, 1, UNBOUNDED);
            } else if (match('{')) {
                int start = pos;
                while (peek("0123456789")) {
                    next();
                }
                if (start == pos) {
                    throw new IllegalArgumentException("integer expected at position " + pos);
                }
                int n = Integer.parseInt(src.substring(start, pos));
                int m = n;
                if (match(',')) {
                    start = pos;
                    while (peek("0123456789")) {
                        next();
                    }
                    m = start == pos ? UNBOUNDED : Integer.parseInt(src.substring(start, pos));
                }
                if (!match('}')) {
                    throw new IllegalArgumentException("expected '}' at position " + pos);
                }
                e = new Repeat(e, n, m);
            }
        }
        return e;
    }

    private Expr parseCompl() {
        if (match('~')) {
            return new Complement(parseCompl());
        }
        return parseCharClass();
    }

    private Expr parseCharClass() {
        if (!match('[')) {
            return parseSimple();
        }
        boolean negate = match('^');
        List<Span> spans = new ArrayList<>();
        do {
            char c = parseChar();
            if (match('-')) {
                if (peek("]")) {
                    spans.add(new Span(c, c));
                    spans.add(new Span('-', '-'));
                } else {
                    spans.add(new Span(c, parseChar()));
                }
            } else {
                spans.add(new Span(c, c));
            }
        } while (more() && !peek("]"));
        if (!match(']')) {
            throw new IllegalArgumentException("expected ']' at position " + pos);
        }
        return new Cls(negate, spans.toArray(Span[]::new));
    }

    private Expr parseSimple() {
        if (match('.')) {
            return new Expr.Any();
        }
        if (match('#')) {
            return new Nothing();
        }
        if (match('@')) {
            return new Expr.AnyString();
        }
        if (match('"')) {
            int start = pos;
            while (more() && !peek("\"")) {
                next();
            }
            if (!match('"')) {
                throw new IllegalArgumentException("expected '\"' at position " + pos);
            }
            return new Str(src.substring(start, pos - 1));
        }
        if (match('(')) {
            boolean capturing = !nonCapturing.get(groupOrdinal++);
            if (match(')')) {
                return capturing ? new Capture(nextGroup++, new Empty()) : new Empty();
            }
            int index = capturing ? nextGroup++ : -1;
            Expr inner = parseUnion();
            if (!match(')')) {
                throw new IllegalArgumentException("expected ')' at position " + pos);
            }
            return capturing ? new Capture(index, inner) : inner;
        }
        if (match('<')) {
            int start = pos;
            while (more() && !peek(">")) {
                next();
            }
            if (!match('>')) {
                throw new IllegalArgumentException("expected '>' at position " + pos);
            }
            String body = src.substring(start, pos - 1);
            int dash = body.indexOf('-');
            if (dash <= 0 || dash != body.lastIndexOf('-') || dash == body.length() - 1) {
                return new Nothing();
            }
            String smin = body.substring(0, dash);
            String smax = body.substring(dash + 1);
            int imin = Integer.parseInt(smin);
            int imax = Integer.parseInt(smax);
            int digits = smin.length() == smax.length() ? smin.length() : 0;
            if (imin > imax) {
                int t = imin;
                imin = imax;
                imax = t;
            }
            return new Interval(imin, imax, digits);
        }
        return new Ch(parseChar());
    }

    private char parseChar() {
        match('\\');
        return next();
    }

    private boolean peek(String chars) {
        return more() && chars.indexOf(src.charAt(pos)) >= 0;
    }

    private boolean match(char c) {
        if (pos >= src.length() || src.charAt(pos) != c) {
            return false;
        }
        pos++;
        return true;
    }

    private boolean more() {
        return pos < src.length();
    }

    private char next() {
        if (!more()) {
            throw new IllegalArgumentException("unexpected end-of-string");
        }
        return src.charAt(pos++);
    }
}
