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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.firej.Firej;
import org.firej.Regex;
import org.firej.cache.CacheKind;
import org.firej.codegen.GeneratorKind;
import org.firej.tools.Regex101Data.Sample;

/**
 * SPE 2008 §6 protocol: compile ({@code t_comp}) and amortized match
 * ({@code t_match}) against {@code java.util.regex}. Cache off, JIT warmup,
 * match via {@code exec(0)} / {@code lookingAt()} (no capture fill). The CLI
 * forks isolated JVMs, as the paper did.
 */
public final class Regex101Benchmark {
    static final int DEFAULT_ITERATIONS = 50;
    private static final int DEFAULT_REPS = 5;
    private static final int CORPUS_WARMUP = 2_000;
    private static final int CORPUS_ITERS = 20_000;
    private static final int HAND_WARMUP = 100_000;
    private static final int HAND_ITERS = 1_000_000;
    private static final long JDK_SLOW_NS = TimeUnit.MILLISECONDS.toNanos(10);

    private static final String IPV4 =
            "(([01]?[0-9][0-9]?|2[0-4][0-9]|25[0-5])\\.){3}([01]?[0-9][0-9]?|2[0-4][0-9]|25[0-5])";
    private static final String IPV4_INPUT = "255.255.255.255";

    private static final String APACHE =
            "([0-9]{1,3}[.]){3}[0-9]{1,3} - - \\[[0-9]{1,2}/[A-Za-z]{3}/[0-9]{4}"
                    + ":[0-9]{2}:[0-9]{2}:[0-9]{2} [+][0-9]{4}\\] "
                    + "[\"](GET|POST) [-a-zA-Z/0-9.%]+ HTTP/[1-9][.][0-9][\"] [0-9]+ [0-9]+";
    private static final String APACHE_INPUT =
            "192.168.214.17 - - [10/Oct/2000:13:55:36 +0000] "
                    + "\"GET /apache-pb/access-log-status-page.html"
                    + "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
                    + " HTTP/1.0\" 200 2326";

    private Regex101Benchmark() {
    }

    public static void run() {
        int reps = Integer.getInteger("firej.bench.reps", DEFAULT_REPS);
        if (Boolean.getBoolean("firej.bench.worker") || reps <= 1) {
            runPaper(System.out, Config.paper());
            return;
        }
        try {
            forkIsolated(reps);
        } catch (Exception e) {
            throw new IllegalStateException("isolated benchmark failed", e);
        }
    }

    public static Result run(int iterations, Appendable out) {
        try {
            return runPaper(out, Config.smoke(iterations));
        } catch (Exception e) {
            throw new IllegalStateException("regex101 benchmark failed", e);
        }
    }

    private static void forkIsolated(int reps) throws Exception {
        String java = ChildJvm.java();
        List<Result> runs = new ArrayList<>();
        System.out.printf("SPE 2008 §6: %d isolated JVM runs (cache off, warmup, t_comp / t_match)%n",
                reps);
        for (int i = 1; i <= reps; i++) {
            System.out.printf("%n===== isolated JVM %d/%d =====%n", i, reps);
            Process process = new ProcessBuilder(List.of(
                    java,
                    "-Dfirej.bench.worker=true",
                    "-cp",
                    ChildJvm.classpath(),
                    Main.class.getName(),
                    "benchmark-regex101"))
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            StringBuilder captured = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                    captured.append(line).append('\n');
                }
            }
            if (process.waitFor() != 0) {
                throw new IllegalStateException("worker JVM " + i + " exited " + process.exitValue());
            }
            runs.add(Result.parse(captured.toString()));
        }
        System.out.println();
        System.out.println(average(runs).summary());
    }

    static Result runPaper(Appendable out, Config config) {
        try {
            return runChecked(out, config);
        } catch (Exception e) {
            throw new IllegalStateException("regex101 benchmark failed", e);
        }
    }

    private static Result runChecked(Appendable out, Config config) throws Exception {
        Pair ipv4 = timeHandcrafted(IPV4, IPV4_INPUT, config.handIters(), config.handWarmup());
        Pair apache = timeHandcrafted(APACHE, APACHE_INPUT, config.handIters(), config.handWarmup());

        List<Sample> raw = Regex101Data.load();
        List<Work> work = probe(raw, config.corpusLimit());
        double[] fireComp = new double[work.size()];
        double[] jdkComp = new double[work.size()];
        double[] fireMatch = new double[work.size()];
        double[] jdkMatch = new double[work.size()];
        long fireCompNs = 0;
        long jdkCompNs = 0;
        long fireMatchNs = 0;
        long jdkMatchNs = 0;

        Firej firej = bytecode();
        for (int i = 0; i < work.size(); i++) {
            Work w = work.get(i);
            Timed fireC = timeFireCompile(firej, w.pattern());
            Timed jdkC = timeJdkCompile(w.pattern());
            fireComp[i] = fireC.nanos() / 1_000.0;
            jdkComp[i] = jdkC.nanos() / 1_000.0;
            fireCompNs += fireC.nanos();
            jdkCompNs += jdkC.nanos();

            Regex fire = fireC.regex();
            fire.setData(w.input());
            Matcher jdk = jdkC.pattern().matcher(w.input());
            for (int k = 0; k < config.corpusWarmup(); k++) {
                fire.exec(0);
                jdk.reset();
                jdk.lookingAt();
            }
            long fa = System.nanoTime();
            long sink = 0;
            for (int k = 0; k < config.corpusIters(); k++) {
                sink += fire.exec(0);
            }
            long fMatch = System.nanoTime() - fa;
            long ja = System.nanoTime();
            for (int k = 0; k < config.corpusIters(); k++) {
                jdk.reset();
                sink += jdk.lookingAt() ? jdk.end() : -1;
            }
            long jMatch = System.nanoTime() - ja;
            if (sink == Long.MIN_VALUE) {
                out.append('\n');
            }
            fireMatch[i] = fMatch / (double) config.corpusIters() / 1_000.0;
            jdkMatch[i] = jMatch / (double) config.corpusIters() / 1_000.0;
            fireMatchNs += fMatch;
            jdkMatchNs += jMatch;
        }

        Stats fireCompS = Stats.of(fireComp);
        Stats jdkCompS = Stats.of(jdkComp);
        Stats fireMatchS = Stats.of(fireMatch);
        Stats jdkMatchS = Stats.of(jdkMatch);

        Result result = new Result(
                raw.size(),
                work.size(),
                config.corpusIters(),
                fireCompNs,
                jdkCompNs,
                fireMatchNs,
                jdkMatchNs,
                ipv4,
                apache,
                fireCompS,
                jdkCompS,
                fireMatchS,
                jdkMatchS);
        out.append(result.summary());
        out.append('\n');
        out.append(result.machineLine());
        out.append('\n');
        return result;
    }

    private static Pair timeHandcrafted(String regex, String input, int iters, int warmup) {
        Firej firej = bytecode();
        Timed fireC = timeFireCompile(firej, regex);
        Timed jdkC = timeJdkCompile(regex);
        Regex fire = fireC.regex();
        fire.setData(input);
        Matcher jdk = jdkC.pattern().matcher(input);
        int expect = input.length();
        for (int i = 0; i < warmup; i++) {
            if (fire.exec(0) != expect) {
                throw new IllegalStateException("FIRE/J failed hand-crafted match: " + regex);
            }
            jdk.reset();
            if (!jdk.matches()) {
                throw new IllegalStateException("java.util.regex failed hand-crafted match: " + regex);
            }
        }
        long sink = 0;
        long fa = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            sink += fire.exec(0);
        }
        long fMatch = System.nanoTime() - fa;
        long ja = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            jdk.reset();
            sink += jdk.matches() ? 1 : 0;
        }
        long jMatch = System.nanoTime() - ja;
        if (sink == Long.MIN_VALUE) {
            throw new IllegalStateException("unreachable");
        }
        return new Pair(fireC.nanos(), jdkC.nanos(), fMatch / (double) iters, jMatch / (double) iters);
    }

    private static Timed timeFireCompile(Firej firej, String pattern) {
        long t0 = System.nanoTime();
        Regex regex = firej.compile(pattern);
        return new Timed(System.nanoTime() - t0, regex, null);
    }

    private static Timed timeJdkCompile(String pattern) {
        long t0 = System.nanoTime();
        Pattern compiled = Pattern.compile(pattern);
        return new Timed(System.nanoTime() - t0, null, compiled);
    }

    private static List<Work> probe(List<Sample> raw, int limit) {
        Firej firej = Firej.builder()
                .generator(GeneratorKind.INTERPRETER)
                .cache(CacheKind.DISABLED)
                .build();
        List<Work> work = new ArrayList<>();
        for (Sample sample : raw) {
            String input = sample.input();
            try {
                firej.compile(sample.pattern());
            } catch (RuntimeException e) {
                continue;
            }
            Pattern jdkPattern;
            try {
                jdkPattern = Pattern.compile(sample.pattern());
            } catch (PatternSyntaxException e) {
                continue;
            }
            Matcher matcher = jdkPattern.matcher(input);
            long t0 = System.nanoTime();
            matcher.lookingAt();
            if (System.nanoTime() - t0 > JDK_SLOW_NS) {
                continue;
            }
            work.add(new Work(sample.pattern(), input));
            if (limit > 0 && work.size() >= limit) {
                break;
            }
        }
        return work;
    }

    private static Firej bytecode() {
        return Firej.builder()
                .generator(GeneratorKind.BYTECODE)
                .cache(CacheKind.DISABLED)
                .build();
    }

    private static Result average(List<Result> runs) {
        Result a = runs.getFirst();
        return new Result(
                a.loaded(),
                a.comparable(),
                a.iterations(),
                mean(runs, Result::fireCompileNs),
                mean(runs, Result::jdkCompileNs),
                mean(runs, Result::fireExecNs),
                mean(runs, Result::jdkExecNs),
                Pair.mean(runs.stream().map(Result::ipv4).toList()),
                Pair.mean(runs.stream().map(Result::apache).toList()),
                Stats.mean(runs.stream().map(Result::fireCompile).toList()),
                Stats.mean(runs.stream().map(Result::jdkCompile).toList()),
                Stats.mean(runs.stream().map(Result::fireMatch).toList()),
                Stats.mean(runs.stream().map(Result::jdkMatch).toList()));
    }

    private static long mean(List<Result> runs, java.util.function.ToLongFunction<Result> fn) {
        return Math.round(runs.stream().mapToLong(fn).average().orElse(0));
    }

    record Config(int handIters, int handWarmup, int corpusIters, int corpusWarmup, int corpusLimit) {
        static Config paper() {
            return new Config(HAND_ITERS, HAND_WARMUP, CORPUS_ITERS, CORPUS_WARMUP, 0);
        }

        static Config smoke(int iterations) {
            int n = Math.max(iterations, 1);
            return new Config(n * 1_000, n * 100, n * 40, n * 10, 80);
        }
    }

    private record Work(String pattern, String input) {
    }

    private record Timed(long nanos, Regex regex, Pattern pattern) {
    }

    public record Pair(long fireCompileNs, long jdkCompileNs, double fireMatchNs, double jdkMatchNs) {
        static Pair mean(List<Pair> xs) {
            return new Pair(
                    Math.round(xs.stream().mapToLong(Pair::fireCompileNs).average().orElse(0)),
                    Math.round(xs.stream().mapToLong(Pair::jdkCompileNs).average().orElse(0)),
                    xs.stream().mapToDouble(Pair::fireMatchNs).average().orElse(0),
                    xs.stream().mapToDouble(Pair::jdkMatchNs).average().orElse(0));
        }

        double fireCompileUs() {
            return fireCompileNs / 1_000.0;
        }

        double jdkCompileUs() {
            return jdkCompileNs / 1_000.0;
        }

        double fireMatchUs() {
            return fireMatchNs / 1_000.0;
        }

        double jdkMatchUs() {
            return jdkMatchNs / 1_000.0;
        }

        String breakEven() {
            double dMatch = jdkMatchNs - fireMatchNs;
            if (dMatch <= 0) {
                return "n/a (FIRE/J not faster per match)";
            }
            return String.format("%.0f matches", (fireCompileNs - jdkCompileNs) / dMatch);
        }
    }

    public record Stats(double q1, double avg, double median, double q3, double max) {
        static Stats of(double[] xs) {
            if (xs.length == 0) {
                return new Stats(0, 0, 0, 0, 0);
            }
            double[] c = xs.clone();
            Arrays.sort(c);
            double sum = 0;
            for (double x : c) {
                sum += x;
            }
            return new Stats(
                    c[c.length / 4],
                    sum / c.length,
                    c[c.length / 2],
                    c[(c.length * 3) / 4],
                    c[c.length - 1]);
        }

        static Stats mean(List<Stats> xs) {
            return new Stats(
                    xs.stream().mapToDouble(Stats::q1).average().orElse(0),
                    xs.stream().mapToDouble(Stats::avg).average().orElse(0),
                    xs.stream().mapToDouble(Stats::median).average().orElse(0),
                    xs.stream().mapToDouble(Stats::q3).average().orElse(0),
                    xs.stream().mapToDouble(Stats::max).average().orElse(0));
        }

        String row() {
            return String.format("%8.2f %8.2f %8.2f %8.2f %8.2f", q1, avg, median, q3, max);
        }

        /** Comma-separated {@code q1,avg,median,q3,max}, for the {@code #result} line. */
        String machine() {
            return String.format("%.4f,%.4f,%.4f,%.4f,%.4f", q1, avg, median, q3, max);
        }

        static Stats fromMachine(String csv) {
            String[] parts = csv.split(",");
            if (parts.length != 5) {
                throw new IllegalStateException("expected five numbers, got " + csv);
            }
            return new Stats(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]), Double.parseDouble(parts[3]), Double.parseDouble(parts[4]));
        }
    }

    public record Result(int loaded, int comparable, int iterations,
            long fireCompileNs, long jdkCompileNs, long fireExecNs, long jdkExecNs,
            Pair ipv4, Pair apache, Stats fireCompile, Stats jdkCompile,
            Stats fireMatch, Stats jdkMatch) {
        public long fireCompileMs() {
            return TimeUnit.NANOSECONDS.toMillis(fireCompileNs);
        }

        public long jdkCompileMs() {
            return TimeUnit.NANOSECONDS.toMillis(jdkCompileNs);
        }

        public long fireExecMs() {
            return TimeUnit.NANOSECONDS.toMillis(fireExecNs);
        }

        public long jdkExecMs() {
            return TimeUnit.NANOSECONDS.toMillis(jdkExecNs);
        }

        public String machineLine() {
            return ("#result loaded=%d comparable=%d iters=%d fc_ns=%d jc_ns=%d fe_ns=%d je_ns=%d "
                    + "ipv4_fc=%.3f ipv4_jc=%.3f ipv4_fm=%.4f ipv4_jm=%.4f "
                    + "apache_fc=%.3f apache_jc=%.3f apache_fm=%.4f apache_jm=%.4f "
                    + "corpus_fm=%s corpus_jm=%s corpus_fc=%s corpus_jc=%s")
                    .formatted(
                            loaded, comparable, iterations,
                            fireCompileNs, jdkCompileNs, fireExecNs, jdkExecNs,
                            ipv4.fireCompileUs(), ipv4.jdkCompileUs(), ipv4.fireMatchUs(), ipv4.jdkMatchUs(),
                            apache.fireCompileUs(), apache.jdkCompileUs(), apache.fireMatchUs(), apache.jdkMatchUs(),
                            fireMatch.machine(), jdkMatch.machine(),
                            fireCompile.machine(), jdkCompile.machine());
        }

        public static Result parse(String text) {
            for (String line : text.split("\n")) {
                if (line.startsWith("#result ")) {
                    return fromMachine(line);
                }
            }
            throw new IllegalStateException("no #result line in worker output");
        }

        private static Result fromMachine(String line) {
            return new Result(
                    (int) num(line, "loaded"),
                    (int) num(line, "comparable"),
                    (int) num(line, "iters"),
                    (long) num(line, "fc_ns"),
                    (long) num(line, "jc_ns"),
                    (long) num(line, "fe_ns"),
                    (long) num(line, "je_ns"),
                    new Pair(usToNs(num(line, "ipv4_fc")), usToNs(num(line, "ipv4_jc")),
                            num(line, "ipv4_fm") * 1_000, num(line, "ipv4_jm") * 1_000),
                    new Pair(usToNs(num(line, "apache_fc")), usToNs(num(line, "apache_jc")),
                            num(line, "apache_fm") * 1_000, num(line, "apache_jm") * 1_000),
                    Stats.fromMachine(token(line, "corpus_fc")),
                    Stats.fromMachine(token(line, "corpus_jc")),
                    Stats.fromMachine(token(line, "corpus_fm")),
                    Stats.fromMachine(token(line, "corpus_jm")));
        }

        private static long usToNs(double us) {
            return Math.round(us * 1_000);
        }

        private static double num(String line, String key) {
            return Double.parseDouble(token(line, key));
        }

        private static String token(String line, String key) {
            String token = " " + key + "=";
            int at = line.indexOf(token);
            if (at < 0) {
                throw new IllegalStateException(key + " missing in " + line);
            }
            int start = at + token.length();
            int end = line.indexOf(' ', start);
            return end < 0 ? line.substring(start) : line.substring(start, end);
        }

        public String summary() {
            return """
                    SPE 2008 §6 protocol vs java.util.regex (J2SDK)
                    cache off; warmup; t_comp vs amortized t_match
                    regex101.data rows: %d
                    comparable (both compile): %d

                    Hand-crafted (Table VIII µs/match, Table IX µs compile)
                    IPv4 input %d chars
                      compile fire: %.1f µs   jdk: %.1f µs
                      match   fire: %.4f µs   jdk: %.4f µs   fire/jdk: %s
                      break-even vs jdk: %s
                    Apache log input %d chars
                      compile fire: %.1f µs   jdk: %.1f µs
                      match   fire: %.4f µs   jdk: %.4f µs   fire/jdk: %s
                      break-even vs jdk: %s

                    Real-world corpus (Table VII-style distribution)
                    match µs/match     Q1       avg    median       Q3      max
                    fire         %s
                    jdk          %s
                    compile µs         Q1       avg    median       Q3      max
                    fire         %s
                    jdk          %s
                    exec iterations/pattern: %d
                    compile fire: %s
                    compile jdk:  %s
                    exec fire: %s
                    exec jdk:  %s
                    """.formatted(
                    loaded,
                    comparable,
                    IPV4_INPUT.length(),
                    ipv4.fireCompileUs(), ipv4.jdkCompileUs(),
                    ipv4.fireMatchUs(), ipv4.jdkMatchUs(), ratio(ipv4.fireMatchNs, ipv4.jdkMatchNs),
                    ipv4.breakEven(),
                    APACHE_INPUT.length(),
                    apache.fireCompileUs(), apache.jdkCompileUs(),
                    apache.fireMatchUs(), apache.jdkMatchUs(), ratio(apache.fireMatchNs, apache.jdkMatchNs),
                    apache.breakEven(),
                    fireMatch.row(),
                    jdkMatch.row(),
                    fireCompile.row(),
                    jdkCompile.row(),
                    iterations,
                    fmt(fireCompileNs),
                    fmt(jdkCompileNs),
                    fmt(fireExecNs),
                    fmt(jdkExecNs)).strip();
        }

        private static String fmt(long nanos) {
            return String.format("%.2f ms", nanos / 1_000_000.0);
        }

        private static String ratio(double fire, double jdk) {
            return jdk == 0 ? "n/a" : String.format("%.2f", fire / jdk);
        }
    }
}
