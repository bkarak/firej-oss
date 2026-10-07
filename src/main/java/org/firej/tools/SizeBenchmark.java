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
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.firej.tools;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.lang.management.CompilationMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.firej.Firej;
import org.firej.Regex;
import org.firej.cache.CacheKind;
import org.firej.codegen.AsmCodeGenerator;
import org.firej.codegen.GeneratorKind;
import org.firej.dfa.Preprocessor;
import org.firej.dfa.DFA;
import org.firej.dfa.FlattenedDfa;
import org.firej.dfa.FlattenedDfa.Range;
import org.firej.dfa.BricsPreprocessor;
import org.firej.dfa.automaton.ParserAutomaton;
import org.objectweb.asm.MethodTooLargeException;

import dk.brics.automaton.Automaton;
import dk.brics.automaton.RegExp;
import dk.brics.automaton.RunAutomaton;

/**
 * How the race between generated code and interpretation moves with the
 * size and complexity of the expression.
 *
 * <p>The SPE protocol gives one number per engine over a corpus. This tool
 * asks how that number changes with the expression — its length in
 * characters, its Ehrenfeucht–Zeiger size (letters) and length, the states
 * and transitions of the DFA, and the bytecode size of the generated
 * {@code walk}. Six walkers are timed on every pattern: the three FIRE/J
 * bytecode emitters, the FIRE/J interpreter, Brics's table-driven
 * {@code RunAutomaton} on the same preprocessed expression, and
 * {@code java.util.regex}. First over synthetic families whose size is the
 * parameter (a literal of {@code n} letters, {@code [0-9]{n}}, {@code .{n}},
 * {@code [a-zA-Z0-9_]{n}}, {@code [aeiouAEIOU0-9]{n}}, an alternation of
 * {@code k} words, the {@code (a|b)*a(a|b){n}} blow-up, and {@code [a-z]+}
 * over a growing input as the control), with inputs that match in full so
 * that a time per character is meaningful; then over the two corpora,
 * bucketed by each measure.
 *
 * <p>Per engine and pattern it records the compile time (median of three),
 * the cold first call, the warm-up until the pace is steady — at least
 * 20,000 calls, past HotSpot's tier-4 thresholds, then chunks until three in
 * a row agree within 5% with no JIT compile finishing in between; a
 * generated class is compiled on its own, an interpreter's loop is hot
 * already — and the steady per-call time, timed in a method of its own so
 * that no on-stack replacement is inside the measurement.
 *
 * <p>The CLI forks one child JVM per variant of {@code firej.bench.jvms}
 * (default: {@code -Xbatch}, and {@code -Xbatch -XX:-DontCompileHugeMethods};
 * see {@link #DEFAULT_JVMS} for why compiles are synchronous), because part
 * of the answer is the JIT's: HotSpot leaves a method over 8,000 bytes of
 * bytecode to its interpreter, and a generated {@code walk} crosses that
 * line at a few hundred states. Other properties:
 * {@code firej.bench.corpus} ({@code all}, {@code regex101}, {@code regex},
 * {@code none}), {@code firej.bench.limit} (rows per corpus file, 0 = all),
 * {@code firej.bench.corpusAll} (run the corpus in every JVM variant, not
 * only the first), {@code firej.bench.out} (results directory, default
 * {@code target/size-bench}: {@code rows.tsv} and {@code summary.txt}).
 */
public final class SizeBenchmark {
    /**
     * The child JVM variants. {@code -Xbatch} makes every JIT compile
     * synchronous, so that once the warm-up has crossed the tier-4 threshold
     * the timed loop runs the final code of {@code walk}; with background
     * compilation a large method's C2 compile can outlast a warm-up that
     * already looks steady in tier-3 code, and the race would be measured
     * against C1. The code cache reservation keeps the compiler from being
     * switched off part-way through a corpus of thousands of generated classes.
     */
    static final String DEFAULT_JVMS = "-Xbatch -XX:ReservedCodeCacheSize=512m;"
            + "-Xbatch -XX:ReservedCodeCacheSize=512m -XX:-DontCompileHugeMethods";
    /** HotSpot's {@code HugeMethodLimit}: bytecode past it is not JIT-compiled unless the flag is off. */
    public static final int HUGE_METHOD_LIMIT = 8000;
    public static final String CORPUS_2007 = "regex.data";
    public static final String CORPUS_101 = "regex101.data";
    static final List<String> FAMILIES =
            List.of("literal", "digits", "dot", "ident", "vowels", "words", "blowup", "loop");

    private static final int COMPILES = 3;
    private static final int CHUNK = 1_000;
    private static final int PRIME_ROUNDS = 800;
    private static final double STEADY = 0.05;
    private static final int[] FULL_SIZES = {1, 2, 4, 8, 16, 32, 64, 128, 192, 256, 384, 512, 768, 1024, 1536, 2048};
    private static final int[] FULL_WORDS = {1, 2, 4, 8, 16, 32, 64, 128, 256, 512};
    private static final int[] FULL_BLOWUP = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
    private static final int[] FULL_LOOPS = {8, 64, 512, 2048};
    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyz";
    private static final String IDENT = LETTERS + LETTERS.toUpperCase() + "0123456789_";
    private static final String DIGITS = "0123456789";

    private SizeBenchmark() {
    }

    // ------------------------------------------------------------------ CLI

    public static void run() {
        String corpusSetting = System.getProperty("firej.bench.corpus", "all");
        int limit = Integer.getInteger("firej.bench.limit", 0);
        if (Boolean.getBoolean("firej.bench.worker")) {
            String jvm = System.getProperty("firej.bench.jvm", "default");
            measure(Config.full(corpora(corpusSetting), limit), jvm, System.out, System.err);
            return;
        }
        try {
            orchestrate(corpusSetting, limit);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("size benchmark failed", e);
        }
    }

    static List<String> corpora(String setting) {
        return switch (setting) {
            case "none" -> List.of();
            case "regex", "regex.data", "2007" -> List.of(CORPUS_2007);
            case "regex101", "regex101.data" -> List.of(CORPUS_101);
            default -> List.of(CORPUS_2007, CORPUS_101);
        };
    }

    private static void orchestrate(String corpusSetting, int limit) throws IOException, InterruptedException {
        List<List<String>> variants = variants(System.getProperty("firej.bench.jvms", DEFAULT_JVMS));
        boolean corpusEverywhere = Boolean.getBoolean("firej.bench.corpusAll");
        Path outDir = Path.of(System.getProperty("firej.bench.out", "target/size-bench"));
        Files.createDirectories(outDir);
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < variants.size(); i++) {
            List<String> args = variants.get(i);
            String label = args.isEmpty() ? "default" : String.join(" ", args);
            String corpus = i == 0 || corpusEverywhere ? corpusSetting : "none";
            System.out.printf("%n===== child JVM %d/%d: %s (corpus: %s) =====%n",
                    i + 1, variants.size(), label, corpus);
            rows.addAll(fork(args, label, corpus, limit));
        }
        Path tsv = outDir.resolve("rows.tsv");
        try (BufferedWriter w = Files.newBufferedWriter(tsv, StandardCharsets.UTF_8)) {
            w.write(Row.header());
            w.newLine();
            for (Row r : rows) {
                w.write(r.encode());
                w.newLine();
            }
        }
        String summary = summary(rows);
        Files.writeString(outDir.resolve("summary.txt"), summary, StandardCharsets.UTF_8);
        System.out.println();
        System.out.println(summary);
        System.out.println("rows: " + tsv + "   summary: " + outDir.resolve("summary.txt"));
    }

    static List<List<String>> variants(String spec) {
        List<List<String>> out = new ArrayList<>();
        for (String variant : spec.split(";")) {
            String v = variant.strip();
            if (v.isEmpty()) {
                continue;
            }
            out.add("default".equals(v) ? List.of() : List.of(v.split("\\s+")));
        }
        if (out.isEmpty()) {
            out.add(List.of());
        }
        return out;
    }

    private static List<Row> fork(List<String> jvmArgs, String label, String corpus, int limit)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(ChildJvm.java());
        cmd.addAll(jvmArgs);
        cmd.add("-Dfirej.bench.worker=true");
        cmd.add("-Dfirej.bench.jvm=" + label);
        cmd.add("-Dfirej.bench.corpus=" + corpus);
        cmd.add("-Dfirej.bench.limit=" + limit);
        cmd.add("-cp");
        cmd.add(ChildJvm.classpath());
        cmd.add(Main.class.getName());
        cmd.add("benchmark-size");
        Process process = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        List<Row> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#row\t")) {
                    rows.add(Row.decode(line));
                } else {
                    System.out.println(line);
                }
            }
        }
        if (process.waitFor() != 0) {
            throw new IllegalStateException("child JVM " + label + " exited " + process.exitValue());
        }
        return rows;
    }

    // ---------------------------------------------------------- measuring

    public enum Engine {
        FIRE("fire", GeneratorKind.BYTECODE),
        RANGES("ranges", GeneratorKind.BYTECODE_RANGES),
        SWITCH("switch", GeneratorKind.BYTECODE_SWITCH),
        INTERP("interp", GeneratorKind.INTERPRETER),
        BRICS("brics", null),
        JDK("jdk", null);

        final String id;
        final GeneratorKind kind;

        Engine(String id, GeneratorKind kind) {
            this.id = id;
            this.kind = kind;
        }

        public String id() {
            return id;
        }
    }

    /**
     * What is run. {@code sizes} parameterise the length families,
     * {@code words} the alternation, {@code blowup} the exponential family,
     * {@code loops} the input-only control. The warm-up runs chunks of about
     * {@code chunkNs} until three consecutive chunks agree within 5% and at
     * least {@code minWarmup} calls have run, never past {@code budgetNs};
     * the timed phase covers at least {@code timedNs}. A cold call over
     * {@code slowNs} is timed three times and flagged instead.
     */
    public record Config(int[] sizes, int[] words, int[] blowup, int[] loops, List<String> corpora,
            int corpusLimit, long minWarmup, long chunkNs, long timedNs, long budgetNs, long slowNs) {

        public static Config full(List<String> corpora, int corpusLimit) {
            // 20,000 warm-up calls at least: HotSpot's tier-4 thresholds are 5,000
            // invocations (15,000 with back edges), and the generated walk has
            // no shared hot loop to ride on.
            return new Config(FULL_SIZES, FULL_WORDS, FULL_BLOWUP, FULL_LOOPS, corpora, corpusLimit,
                    20_000, 2_000_000L, 20_000_000L, 3_000_000_000L, 20_000_000L);
        }

        public static Config smoke() {
            return new Config(new int[] {1, 4, 16}, new int[] {1, 4}, new int[] {1, 3}, new int[] {8},
                    List.of(CORPUS_2007, CORPUS_101), 8, 200, 200_000L, 1_000_000L, 100_000_000L, 20_000_000L);
        }
    }

    public record Case(String family, int n, String pattern, String input) {
    }

    /**
     * The expression as written and as an automaton, plus what the emitters
     * make of it: the total bytecode carrying the walk per emitter ({@code -1}
     * when ASM cannot write it and the interpreter stands in), and for the
     * default emitter the number of methods it is split across and the
     * largest of them — the number the JIT's limit applies to.
     */
    public record Shape(int chars, int size, int length, int states, int ranges, int maxRanges, int classes,
            int bytesMap, int bytesRanges, int bytesSwitch, int segments, int largest) {
    }

    /** What one emitter produced: total walk bytecode, methods carrying it, the largest of them. */
    record Emitted(int total, int segments, int largest) {
        static final Emitted FALLBACK = new Emitted(-1, 0, -1);
    }

    public record Timing(String status, long compileNs, long firstNs, long warmupNs, long warmed, boolean steady,
            long timed, double matchNs, int end) {
        static final String[] FIELDS =
                {"status", "compile_ns", "first_ns", "warmup_ns", "warmed", "steady", "timed", "match_ns", "end"};
        static final Timing UNSUPPORTED = new Timing("unsupported", 0, 0, 0, 0, false, 0, Double.NaN, -1);
        static final Timing ERROR = new Timing("error", 0, 0, 0, 0, false, 0, Double.NaN, -1);

        public boolean ok() {
            return "ok".equals(status) || "slow".equals(status);
        }

        void encode(StringBuilder b) {
            b.append('\t').append(status).append('\t').append(compileNs).append('\t').append(firstNs)
                    .append('\t').append(warmupNs).append('\t').append(warmed).append('\t').append(steady ? 1 : 0)
                    .append('\t').append(timed).append('\t').append(String.format("%.4f", matchNs))
                    .append('\t').append(end);
        }

        static Timing decode(String[] f, int at) {
            return new Timing(f[at], Long.parseLong(f[at + 1]), Long.parseLong(f[at + 2]),
                    Long.parseLong(f[at + 3]), Long.parseLong(f[at + 4]), "1".equals(f[at + 5]),
                    Long.parseLong(f[at + 6]), Double.parseDouble(f[at + 7]), Integer.parseInt(f[at + 8]));
        }
    }

    public record Row(String jvm, String family, int n, Shape shape, int inputLen, String pattern,
            Map<Engine, Timing> timings) {

        public double matchNs(Engine e) {
            Timing t = timings.get(e);
            return t != null && t.ok() ? t.matchNs() : Double.NaN;
        }

        /** Nanoseconds per character walked; the synthetic inputs match in full. */
        public double nsPerChar(Engine e) {
            Timing t = timings.get(e);
            return t != null && t.ok() ? t.matchNs() / Math.max(1, t.end()) : Double.NaN;
        }

        public double ratio(Engine a, Engine b) {
            return matchNs(a) / matchNs(b);
        }

        boolean corpus() {
            return CORPUS_2007.equals(family) || CORPUS_101.equals(family);
        }

        static String header() {
            StringBuilder b = new StringBuilder("#row\tjvm\tfamily\tn\tchars\tsize\tlength\tstates\tranges"
                    + "\tmax_ranges\tclasses\tbytes_map\tbytes_ranges\tbytes_switch\tsegments\tlargest\tinput_len");
            for (Engine e : Engine.values()) {
                for (String f : Timing.FIELDS) {
                    b.append('\t').append(e.id).append('_').append(f);
                }
            }
            return b.append("\tpattern").toString();
        }

        public String encode() {
            StringBuilder b = new StringBuilder("#row");
            b.append('\t').append(jvm).append('\t').append(family).append('\t').append(n);
            b.append('\t').append(shape.chars()).append('\t').append(shape.size()).append('\t').append(shape.length())
                    .append('\t').append(shape.states()).append('\t').append(shape.ranges())
                    .append('\t').append(shape.maxRanges()).append('\t').append(shape.classes())
                    .append('\t').append(shape.bytesMap()).append('\t').append(shape.bytesRanges())
                    .append('\t').append(shape.bytesSwitch()).append('\t').append(shape.segments())
                    .append('\t').append(shape.largest());
            b.append('\t').append(inputLen);
            for (Engine e : Engine.values()) {
                timings.getOrDefault(e, Timing.UNSUPPORTED).encode(b);
            }
            b.append('\t').append(escape(pattern));
            return b.toString();
        }

        public static Row decode(String line) {
            String[] f = line.split("\t", -1);
            int i = 1;
            String jvm = f[i++];
            String family = f[i++];
            int n = Integer.parseInt(f[i++]);
            Shape shape = new Shape(Integer.parseInt(f[i++]), Integer.parseInt(f[i++]), Integer.parseInt(f[i++]),
                    Integer.parseInt(f[i++]), Integer.parseInt(f[i++]), Integer.parseInt(f[i++]),
                    Integer.parseInt(f[i++]), Integer.parseInt(f[i++]), Integer.parseInt(f[i++]),
                    Integer.parseInt(f[i++]), Integer.parseInt(f[i++]), Integer.parseInt(f[i++]));
            int inputLen = Integer.parseInt(f[i++]);
            Map<Engine, Timing> timings = new EnumMap<>(Engine.class);
            for (Engine e : Engine.values()) {
                timings.put(e, Timing.decode(f, i));
                i += Timing.FIELDS.length;
            }
            return new Row(jvm, family, n, shape, inputLen, Regex101Data.unescape(f[i]), timings);
        }
    }

    /** One compiled pattern with its input, ready to be timed. */
    interface Subject {
        int once();

        /** {@code n} matches, in a loop the JIT compiles as a method of its own; returns a sink. */
        int loop(int n);
    }

    static final class FireSubject implements Subject {
        private final Regex regex;

        FireSubject(Regex regex, String input) {
            this.regex = regex;
            regex.setData(input);
        }

        @Override
        public int once() {
            return regex.exec(0);
        }

        @Override
        public int loop(int n) {
            return loopFire(regex, n);
        }
    }

    static int loopFire(Regex regex, int n) {
        int sink = 0;
        for (int i = 0; i < n; i++) {
            sink += regex.exec(0);
        }
        return sink;
    }

    static final class BricsSubject implements Subject {
        private final RunAutomaton run;
        private final String input;
        private final boolean anchoredEnd;

        BricsSubject(RunAutomaton run, String input, boolean anchoredEnd) {
            this.run = run;
            this.input = input;
            this.anchoredEnd = anchoredEnd;
        }

        static BricsSubject of(String pattern, String input) {
            Preprocessor.Processed p = BricsPreprocessor.getInstance().process(pattern);
            Automaton automaton = new RegExp(p.expression()).toAutomaton();
            automaton.determinize();
            return new BricsSubject(new RunAutomaton(automaton), input, p.anchoredEnd());
        }

        @Override
        public int once() {
            int end = run.run(input, 0);
            return anchoredEnd && end != input.length() ? -1 : end;
        }

        @Override
        public int loop(int n) {
            return loopBrics(run, input, anchoredEnd, n);
        }
    }

    static int loopBrics(RunAutomaton run, String input, boolean anchoredEnd, int n) {
        int sink = 0;
        int len = input.length();
        for (int i = 0; i < n; i++) {
            int end = run.run(input, 0);
            sink += anchoredEnd && end != len ? -1 : end;
        }
        return sink;
    }

    static final class JdkSubject implements Subject {
        private final Matcher matcher;

        JdkSubject(Matcher matcher) {
            this.matcher = matcher;
        }

        @Override
        public int once() {
            matcher.reset();
            return matcher.lookingAt() ? matcher.end() : -1;
        }

        @Override
        public int loop(int n) {
            return loopJdk(matcher, n);
        }
    }

    static int loopJdk(Matcher matcher, int n) {
        int sink = 0;
        for (int i = 0; i < n; i++) {
            matcher.reset();
            sink += matcher.lookingAt() ? matcher.end() : -1;
        }
        return sink;
    }

    /** The six engines, each FIRE/J variant with its own generator and the cache off. */
    static final class Bench {
        private final Map<Engine, Firej> firej = new EnumMap<>(Engine.class);

        Bench() {
            for (Engine e : Engine.values()) {
                if (e.kind != null) {
                    firej.put(e, Firej.builder().generator(e.kind).cache(CacheKind.DISABLED).build());
                }
            }
        }

        Subject subject(Engine e, String pattern, String input) {
            return switch (e) {
                case FIRE, RANGES, SWITCH, INTERP -> new FireSubject(firej.get(e).compile(pattern), input);
                case BRICS -> BricsSubject.of(pattern, input);
                case JDK -> new JdkSubject(Pattern.compile(pattern).matcher(input));
            };
        }

        /**
         * Gets the three loop methods JIT-compiled, with several generated
         * classes behind the FIRE/J call site so that its profile is
         * megamorphic from the start, before anything is measured.
         */
        void prime() {
            String[] patterns = {"[0-9]+", "[a-z]+x", "(ab|cd)*", "[^,]*,"};
            List<Subject> subjects = new ArrayList<>();
            for (Engine e : Engine.values()) {
                for (String p : patterns) {
                    subjects.add(subject(e, p, "12345,ab"));
                }
            }
            int sink = 0;
            for (int round = 0; round < PRIME_ROUNDS; round++) {
                for (Subject s : subjects) {
                    sink += s.loop(CHUNK);
                }
            }
            if (sink == Integer.MIN_VALUE) {
                throw new IllegalStateException("unreachable");
            }
        }

        Timing time(Engine e, String pattern, String input, Config config) {
            Subject subject = null;
            long[] compiles = new long[COMPILES];
            try {
                for (int i = 0; i < COMPILES; i++) {
                    long t0 = System.nanoTime();
                    subject = subject(e, pattern, input);
                    compiles[i] = System.nanoTime() - t0;
                }
            } catch (RuntimeException | StackOverflowError ex) {
                return Timing.UNSUPPORTED;
            }
            Arrays.sort(compiles);
            return measure(subject, compiles[COMPILES / 2], config);
        }
    }

    static Timing measure(Subject subject, long compileNs, Config config) {
        int end;
        long firstNs;
        try {
            long t0 = System.nanoTime();
            end = subject.once();
            firstNs = System.nanoTime() - t0;
        } catch (RuntimeException | StackOverflowError e) {
            return Timing.ERROR;
        }
        int sink = 0;
        if (firstNs > config.slowNs()) {
            long t0 = System.nanoTime();
            sink += subject.loop(3);
            double perCall = (System.nanoTime() - t0) / 3.0;
            if (sink == Integer.MIN_VALUE) {
                throw new IllegalStateException("unreachable");
            }
            return new Timing("slow", compileNs, firstNs, 0, 0, false, 3, perCall, end);
        }
        long deadline = System.nanoTime() + config.budgetNs();
        long w0 = System.nanoTime();
        double previous = -1;
        double perCall = 1;
        int steadyRuns = 0;
        long warmed = 0;
        int chunk = CHUNK;
        boolean steady = false;
        long compiled = jitTime();
        while (true) {
            long c0 = System.nanoTime();
            sink += subject.loop(chunk);
            long took = System.nanoTime() - c0;
            perCall = Math.max(0.1, took / (double) chunk);
            warmed += chunk;
            long nowCompiled = jitTime();
            if (nowCompiled != compiled) {
                // Something was JIT-compiled during this chunk: the pace may be about to change.
                steadyRuns = 0;
                compiled = nowCompiled;
            } else if (previous > 0 && Math.abs(perCall - previous) <= STEADY * Math.max(perCall, previous)) {
                steadyRuns++;
            } else {
                steadyRuns = 0;
            }
            previous = perCall;
            long now = System.nanoTime();
            if (steadyRuns >= 3 && warmed >= config.minWarmup()) {
                steady = true;
                break;
            }
            if (now >= deadline) {
                break;
            }
            long wanted = (long) (config.chunkNs() / perCall);
            long left = (long) ((deadline - now) / perCall);
            chunk = (int) Math.max(CHUNK, Math.min(Math.min(wanted, left), Integer.MAX_VALUE / 2));
        }
        long warmupNs = System.nanoTime() - w0;
        long iterations = Math.max(20, Math.min((long) (config.timedNs() / perCall), (long) (config.budgetNs() / perCall)));
        iterations = Math.min(iterations, Integer.MAX_VALUE / 2);
        long t0 = System.nanoTime();
        sink += subject.loop((int) iterations);
        long elapsed = System.nanoTime() - t0;
        if (sink == Integer.MIN_VALUE) {
            throw new IllegalStateException("unreachable");
        }
        return new Timing("ok", compileNs, firstNs, warmupNs, warmed, steady, iterations,
                elapsed / (double) iterations, end);
    }

    private static final CompilationMXBean JIT = ManagementFactory.getCompilationMXBean();

    /** Total JIT compilation time so far, in ms; a change means a compile finished. */
    private static long jitTime() {
        return JIT != null && JIT.isCompilationTimeMonitoringSupported() ? JIT.getTotalCompilationTime() : 0;
    }

    /** Runs every case in this JVM, printing a {@code #row} line per pattern to {@code out}. */
    public static List<Row> measure(Config config, String jvm, PrintStream out, PrintStream log) {
        Bench bench = new Bench();
        bench.prime();
        List<Case> cases = new ArrayList<>(synthetic(config));
        cases.addAll(corpus(config));
        List<Row> rows = new ArrayList<>();
        for (Case c : cases) {
            Row row = measure(bench, jvm, c, config);
            if (row == null) {
                log.println("skip " + c.family() + " " + c.n() + ": does not compile");
                continue;
            }
            rows.add(row);
            out.println(row.encode());
            out.flush();
            log.println(progress(row));
        }
        return rows;
    }

    static Row measure(Bench bench, String jvm, Case c, Config config) {
        Shape shape;
        try {
            shape = shape(c.pattern());
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
        Map<Engine, Timing> timings = new EnumMap<>(Engine.class);
        for (Engine e : Engine.values()) {
            timings.put(e, bench.time(e, c.pattern(), c.input(), config));
        }
        return new Row(jvm, c.family(), c.n(), shape, c.input().length(), c.pattern(), timings);
    }

    private static String progress(Row r) {
        return String.format("%-13s %5d states=%6d bytes=%7d segs=%3d walk=%4d  fire=%9.1f interp=%9.1f brics=%9.1f jdk=%9.1f ns",
                r.family(), r.n(), r.shape().states(), r.shape().bytesMap(), r.shape().segments(),
                r.timings().get(Engine.FIRE).end(),
                r.matchNs(Engine.FIRE), r.matchNs(Engine.INTERP), r.matchNs(Engine.BRICS), r.matchNs(Engine.JDK));
    }

    // ------------------------------------------------------------- shape

    public static Shape shape(String pattern) {
        Preprocessor.Processed p = BricsPreprocessor.getInstance().process(pattern);
        DFA dfa = new ParserAutomaton().getDFA(p.expression());
        FlattenedDfa flat = FlattenedDfa.from(pattern, dfa, p.anchoredStart(), p.anchoredEnd());
        int ranges = 0;
        int maxRanges = 0;
        TreeSet<Integer> cuts = new TreeSet<>();
        for (Range[] edges : flat.transitions()) {
            ranges += edges.length;
            maxRanges = Math.max(maxRanges, edges.length);
            for (Range edge : edges) {
                if (edge.min() > 0) {
                    cuts.add(edge.min());
                }
                if (edge.max() < Character.MAX_VALUE) {
                    cuts.add(edge.max() + 1);
                }
            }
        }
        ExpressionMetrics.Complexity c = ExpressionMetrics.of(pattern);
        Emitted map = emitted(flat, true, true);
        return new Shape(pattern.length(), c.size(), c.length(), flat.stateCount(), ranges, maxRanges,
                cuts.size() + 1, map.total(), emitted(flat, true, false).total(),
                emitted(flat, false, false).total(), map.segments(), map.largest());
    }

    /**
     * The walk's bytecode from the given emitter: {@code walk} plus its
     * segments {@code seg0}, {@code seg1}, … when split; {@link Emitted#FALLBACK}
     * when ASM cannot write the class and the interpreter stands in.
     */
    static Emitted emitted(FlattenedDfa dfa, boolean threaded, boolean classMap) {
        Map<String, Integer> methods;
        try {
            methods = CodeSize.methods(new AsmCodeGenerator(threaded, classMap).emit(dfa));
        } catch (MethodTooLargeException e) {
            return Emitted.FALLBACK;
        }
        int total = 0;
        int segments = 0;
        int largest = 0;
        for (Map.Entry<String, Integer> e : methods.entrySet()) {
            String name = e.getKey();
            if (name.equals("walk") || name.startsWith("seg")) {
                total += e.getValue();
                largest = Math.max(largest, e.getValue());
                if (name.startsWith("seg")) {
                    segments++;
                }
            }
        }
        return new Emitted(total, Math.max(1, segments), largest);
    }

    // ------------------------------------------------------------- cases

    static List<Case> synthetic(Config config) {
        List<Case> cases = new ArrayList<>();
        Random random = new Random(20260908);
        for (int n : config.sizes()) {
            String literal = pick(random, LETTERS, n);
            cases.add(new Case("literal", n, literal, literal));
            cases.add(new Case("digits", n, "[0-9]{" + n + "}", pick(random, DIGITS, n)));
            cases.add(new Case("dot", n, ".{" + n + "}", pick(random, LETTERS, n)));
            cases.add(new Case("ident", n, "[a-zA-Z0-9_]{" + n + "}", pick(random, IDENT, n)));
            cases.add(new Case("vowels", n, "[aeiouAEIOU0-9]{" + n + "}", "u".repeat(n)));
        }
        for (int k : config.words()) {
            Set<String> words = new LinkedHashSet<>();
            while (words.size() < k) {
                words.add(pick(random, LETTERS, 8));
            }
            List<String> list = List.copyOf(words);
            cases.add(new Case("words", k, "(" + String.join("|", list) + ")", list.getLast()));
        }
        for (int n : config.blowup()) {
            cases.add(new Case("blowup", n, "(a|b)*a(a|b){" + n + "}", "b".repeat(32) + "a" + "b".repeat(n)));
        }
        for (int n : config.loops()) {
            cases.add(new Case("loop", n, "[a-z]+", pick(random, LETTERS, n)));
        }
        return cases;
    }

    private static String pick(Random random, String alphabet, int n) {
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            b.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return b.toString();
    }

    static List<Case> corpus(Config config) {
        List<Case> cases = new ArrayList<>();
        for (String name : config.corpora()) {
            boolean escaped = CORPUS_101.equals(name);
            int taken = 0;
            for (String[] row : readRows(name, escaped)) {
                cases.add(new Case(name, Integer.parseInt(row[0]), row[1], row[2]));
                if (config.corpusLimit() > 0 && ++taken >= config.corpusLimit()) {
                    break;
                }
            }
        }
        return cases;
    }

    /** {@code {line, pattern, input}} per row of a {@code pattern<TAB>input} corpus file. */
    static List<String[]> readRows(String name, boolean escaped) {
        List<String[]> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(open(name), StandardCharsets.UTF_8))) {
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
                String pattern = line.substring(0, tab);
                String input = line.substring(tab + 1);
                if (escaped) {
                    pattern = Regex101Data.unescape(pattern);
                    input = Regex101Data.unescape(input);
                }
                out.add(new String[] {Integer.toString(lineNumber), pattern, input});
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static InputStream open(String name) throws IOException {
        InputStream in = SizeBenchmark.class.getResourceAsStream("/" + name);
        if (in != null) {
            return in;
        }
        Path file = Path.of("src/test/resources", name);
        if (Files.isRegularFile(file)) {
            return Files.newInputStream(file);
        }
        throw new IllegalStateException(name + " not found on the classpath or at " + file);
    }

    static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\t' -> b.append("\\t");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\\' -> b.append("\\\\");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    // ----------------------------------------------------------- summary

    public static String summary(List<Row> rows) {
        StringBuilder b = new StringBuilder();
        b.append("FIRE/J size benchmark: how the race moves with the expression\n");
        b.append("fire = BYTECODE (goto-threaded, class map), ranges = BYTECODE_RANGES, switch = BYTECODE_SWITCH,\n");
        b.append("interp = FIRE/J interpreter, brics = dk.brics RunAutomaton on the same expression, jdk = java.util.regex lookingAt\n");
        b.append("b.map / b.rng / b.sw = bytecode of the generated walk per emitter (all its methods); -1 = over 64 KiB, interpreter fallback\n");
        b.append("segs / max = methods the class-map walk is split across, and the largest of them\n");
        b.append("HotSpot does not JIT-compile a method over ").append(HUGE_METHOD_LIMIT)
                .append(" bytes unless -XX:-DontCompileHugeMethods\n");
        List<String> jvms = rows.stream().map(Row::jvm).distinct().toList();
        for (String jvm : jvms) {
            b.append("\n========== JVM: ").append(jvm).append(" ==========\n");
            for (String family : FAMILIES) {
                List<Row> fam = rows.stream()
                        .filter(r -> r.jvm().equals(jvm) && r.family().equals(family))
                        .sorted(Comparator.comparingInt(Row::n))
                        .toList();
                if (!fam.isEmpty()) {
                    syntheticTable(b, family, fam);
                }
            }
            List<Row> corpus = rows.stream().filter(r -> r.jvm().equals(jvm) && r.corpus()).toList();
            if (!corpus.isEmpty()) {
                corpusTables(b, corpus);
            }
        }
        return b.toString();
    }

    static String describe(String family) {
        return switch (family) {
            case "literal" -> "n random letters, input the literal itself (n+1 states in a chain, one range each)";
            case "digits" -> "[0-9]{n}, input n digits (one range per state)";
            case "dot" -> ".{n}, input n letters (two ranges per state, compare chain)";
            case "ident" -> "[a-zA-Z0-9_]{n}, input n identifier characters (four ranges per state, class map)";
            case "vowels" -> "[aeiouAEIOU0-9]{n}, input n times 'u' (eleven ranges per state; the interpreter scans all)";
            case "words" -> "(w1|...|wk), k random 8-letter words, input the last one (a trie; the walk is 8 characters)";
            case "blowup" -> "(a|b)*a(a|b){n}, input b^32 a b^n (2^(n+1) states, two ranges each)";
            case "loop" -> "[a-z]+ over n letters (two states: only the walk grows)";
            default -> family;
        };
    }

    private static void syntheticTable(StringBuilder b, String family, List<Row> fam) {
        b.append('\n').append(family).append(": ").append(describe(family)).append("; ns per character walked\n");
        b.append(String.format("%6s %6s %5s %6s %7s %7s %5s %7s %4s %6s %7s %7s %5s |%8s %8s %8s %8s %8s %9s |%8s %8s%n",
                "n", "chars", "size", "length", "states", "ranges", "cls", "b.map", "segs", "max", "b.rng", "b.sw",
                "walk", "fire", "ranges", "switch", "interp", "brics", "jdk", "brics/f", "interp/f"));
        for (Row r : fam) {
            Shape s = r.shape();
            b.append(String.format("%6d %6d %5d %6d %7d %7d %5d %7d %4d %6d %7d %7d %5d |%8s %8s %8s %8s %8s %9s |%8s %8s%n",
                    r.n(), s.chars(), s.size(), s.length(), s.states(), s.ranges(), s.classes(),
                    s.bytesMap(), s.segments(), s.largest(), s.bytesRanges(), s.bytesSwitch(),
                    r.timings().get(Engine.FIRE).end(),
                    fmt(r.nsPerChar(Engine.FIRE)), fmt(r.nsPerChar(Engine.RANGES)), fmt(r.nsPerChar(Engine.SWITCH)),
                    fmt(r.nsPerChar(Engine.INTERP)), fmt(r.nsPerChar(Engine.BRICS)), fmt(r.nsPerChar(Engine.JDK)),
                    fmt(r.ratio(Engine.BRICS, Engine.FIRE)), fmt(r.ratio(Engine.INTERP, Engine.FIRE))));
        }
    }

    private static void corpusTables(StringBuilder b, List<Row> corpus) {
        long over = corpus.stream().filter(r -> r.shape().bytesMap() > HUGE_METHOD_LIMIT || r.shape().bytesMap() < 0).count();
        long fallback = corpus.stream().filter(r -> r.shape().bytesMap() < 0).count();
        long rangesOver = corpus.stream()
                .filter(r -> r.shape().bytesRanges() > HUGE_METHOD_LIMIT || r.shape().bytesRanges() < 0).count();
        long split = corpus.stream().filter(r -> r.shape().segments() > 1).count();
        long largestOver = corpus.stream().filter(r -> r.shape().largest() > HUGE_METHOD_LIMIT).count();
        b.append(String.format("%ncorpus: %d rows; walk over %d bytes with the class map: %d (with compare chains: %d), "
                + "split across methods: %d, largest method still over the limit: %d; over 64 KiB, interpreter fallback: %d%n",
                corpus.size(), HUGE_METHOD_LIMIT, over, rangesOver, split, largestOver, fallback));
        b.append("ns per call, medians per bucket; the ratio columns are medians of per-row ratios (above 1 = fire ahead)\n");
        bucketTable(b, "by walk bytes (class-map emitter)", corpus,
                r -> r.shape().bytesMap() < 0 ? 1L << 40 : r.shape().bytesMap(),
                new long[] {0, 500, 1000, 2000, 4000, 8000, 16000, 32000, 65536, Long.MAX_VALUE}, "fallback");
        bucketTable(b, "by DFA states", corpus, r -> r.shape().states(),
                new long[] {1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 4096, Long.MAX_VALUE}, null);
        bucketTable(b, "by EZ size (letters)", corpus, r -> r.shape().size(),
                new long[] {0, 2, 4, 8, 16, 32, 64, 128, 256, Long.MAX_VALUE}, null);
        bucketTable(b, "by EZ length", corpus, r -> r.shape().length(),
                new long[] {0, 2, 4, 8, 16, 32, 64, 128, 256, Long.MAX_VALUE}, null);
        bucketTable(b, "by pattern characters", corpus, r -> r.shape().chars(),
                new long[] {0, 8, 16, 32, 64, 128, 256, 512, Long.MAX_VALUE}, null);
        jitTable(b, corpus);
    }

    private static void bucketTable(StringBuilder b, String title, List<Row> rows, ToLongFunction<Row> key,
            long[] edges, String lastLabel) {
        b.append('\n').append(title).append('\n');
        b.append(String.format("%14s %5s |%8s %8s %8s %8s %8s %9s |%8s %8s %8s %8s%n", "bucket", "n",
                "fire", "ranges", "switch", "interp", "brics", "jdk", "brics/f", "interp/f", "switch/f", "jdk/f"));
        for (int i = 0; i + 1 < edges.length; i++) {
            long lo = edges[i];
            long hi = edges[i + 1];
            List<Row> in = rows.stream().filter(r -> key.applyAsLong(r) >= lo && key.applyAsLong(r) < hi).toList();
            if (in.isEmpty()) {
                continue;
            }
            String label = i == edges.length - 2 && lastLabel != null ? lastLabel
                    : hi == Long.MAX_VALUE ? lo + "+" : lo + "-" + (hi - 1);
            b.append(String.format("%14s %5d |%8s %8s %8s %8s %8s %9s |%8s %8s %8s %8s%n", label, in.size(),
                    fmt(median(in, r -> r.matchNs(Engine.FIRE))), fmt(median(in, r -> r.matchNs(Engine.RANGES))),
                    fmt(median(in, r -> r.matchNs(Engine.SWITCH))), fmt(median(in, r -> r.matchNs(Engine.INTERP))),
                    fmt(median(in, r -> r.matchNs(Engine.BRICS))), fmt(median(in, r -> r.matchNs(Engine.JDK))),
                    fmt(median(in, r -> r.ratio(Engine.BRICS, Engine.FIRE))),
                    fmt(median(in, r -> r.ratio(Engine.INTERP, Engine.FIRE))),
                    fmt(median(in, r -> r.ratio(Engine.SWITCH, Engine.FIRE))),
                    fmt(median(in, r -> r.ratio(Engine.JDK, Engine.FIRE)))));
        }
    }

    /** What the generative approach pays per pattern before it is fast: compile, cold call, warm-up. */
    private static void jitTable(StringBuilder b, List<Row> rows) {
        b.append("\nJIT cost by walk bytes (class-map emitter): compile µs, cold first call µs, warm-up ms until steady, "
                + "share of rows that reached a steady pace\n");
        b.append(String.format("%14s %5s |%9s %9s %9s %7s |%9s %9s %9s |%9s %9s %9s%n", "bucket", "n",
                "fire cmp", "fire 1st", "fire wrm", "steady", "brics cmp", "brics 1st", "brics wrm",
                "interp cmp", "interp 1st", "interp wrm"));
        long[] edges = {0, 500, 1000, 2000, 4000, 8000, 16000, 32000, 65536, Long.MAX_VALUE};
        for (int i = 0; i + 1 < edges.length; i++) {
            long lo = edges[i];
            long hi = edges[i + 1];
            List<Row> in = rows.stream().filter(r -> {
                long k = r.shape().bytesMap() < 0 ? 1L << 40 : r.shape().bytesMap();
                return k >= lo && k < hi;
            }).toList();
            if (in.isEmpty()) {
                continue;
            }
            String label = i == edges.length - 2 ? "fallback" : lo + "-" + (hi - 1);
            long steady = in.stream().filter(r -> r.timings().get(Engine.FIRE).steady()).count();
            b.append(String.format("%14s %5d |%9s %9s %9s %6.0f%% |%9s %9s %9s |%9s %9s %9s%n", label, in.size(),
                    fmt(median(in, r -> r.timings().get(Engine.FIRE).compileNs() / 1e3)),
                    fmt(median(in, r -> r.timings().get(Engine.FIRE).firstNs() / 1e3)),
                    fmt(median(in, r -> r.timings().get(Engine.FIRE).warmupNs() / 1e6)),
                    100.0 * steady / in.size(),
                    fmt(median(in, r -> r.timings().get(Engine.BRICS).compileNs() / 1e3)),
                    fmt(median(in, r -> r.timings().get(Engine.BRICS).firstNs() / 1e3)),
                    fmt(median(in, r -> r.timings().get(Engine.BRICS).warmupNs() / 1e6)),
                    fmt(median(in, r -> r.timings().get(Engine.INTERP).compileNs() / 1e3)),
                    fmt(median(in, r -> r.timings().get(Engine.INTERP).firstNs() / 1e3)),
                    fmt(median(in, r -> r.timings().get(Engine.INTERP).warmupNs() / 1e6))));
        }
    }

    static double median(List<Row> rows, ToDoubleFunction<Row> f) {
        double[] xs = rows.stream().mapToDouble(f).filter(x -> !Double.isNaN(x)).sorted().toArray();
        if (xs.length == 0) {
            return Double.NaN;
        }
        return xs.length % 2 == 1 ? xs[xs.length / 2] : (xs[xs.length / 2 - 1] + xs[xs.length / 2]) / 2;
    }

    static String fmt(double x) {
        if (Double.isNaN(x)) {
            return "-";
        }
        return x >= 1000 ? String.format("%.0f", x) : String.format("%.2f", x);
    }
}
