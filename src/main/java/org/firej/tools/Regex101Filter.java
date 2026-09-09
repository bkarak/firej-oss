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
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.firej.Firej;
import org.firej.cache.CacheKind;
import org.firej.codegen.GeneratorKind;

/**
 * Rebuilds {@code regex101.data} from a bounded Hugging Face TSV. Each compile
 * runs in a child JVM so a Brics blow-up or {@code OutOfMemoryError} only
 * kills that process.
 */
public final class Regex101Filter {
    private static final Pattern PCRE = Pattern.compile(
            "\\(\\?|"
                    + "\\*[?+]|\\+[?+]|\\?[?+]|"
                    + "\\\\[1-9AbBzZGkHPQRE]|"
                    + "\\[\\[:|"
                    + "<\\w+>");
    private static final int MAX_PATTERN_CHARS = 400;
    private static final long COMPILE_TIMEOUT_MS = 800;
    private static final int RESTART_EVERY = 200;
    private static final String WORKER_HEAP = "256m";

    private Regex101Filter() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--worker".equals(args[0])) {
            worker();
            return;
        }
        if (args.length != 2) {
            System.err.println("usage: org.firej.tools.Regex101Filter <in.tsv> <out.data>");
            System.exit(2);
            return;
        }
        filter(Path.of(args[0]), Path.of(args[1]));
    }

    static void filter(Path in, Path out) throws Exception {
        int kept = 0;
        int failed = 0;
        int timed = 0;
        int oom = 0;
        int skipped = 0;
        int seen = 0;
        Worker worker = new Worker();
        try (BufferedReader reader = Files.newBufferedReader(in, StandardCharsets.UTF_8);
                BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            writer.write("# Filtered subset of https://huggingface.co/datasets/innovatorved/regex_dataset (MIT).\n");
            writer.write("# Origin: regex101.com. Rows FIRE/J compiles; haystacks truncated to 512 chars.\n");
            String line;
            while ((line = reader.readLine()) != null) {
                seen++;
                int tab = line.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                String escapedPattern = line.substring(0, tab);
                String pattern = Regex101Data.unescape(escapedPattern);
                if (pattern.length() > MAX_PATTERN_CHARS || PCRE.matcher(pattern).find()) {
                    skipped++;
                    continue;
                }
                Verdict verdict = worker.compile(escapedPattern);
                switch (verdict) {
                    case OK -> {
                        writer.write(line);
                        writer.write('\n');
                        kept++;
                    }
                    case FAIL -> failed++;
                    case TIMEOUT -> timed++;
                    case OOM -> oom++;
                }
                if (seen % 500 == 0) {
                    System.out.printf("seen=%d kept=%d failed=%d skip=%d timeout=%d oom=%d%n",
                            seen, kept, failed, skipped, timed, oom);
                    writer.flush();
                }
            }
        } finally {
            worker.close();
        }
        System.out.printf("done kept=%d failed=%d skipped=%d timed_out=%d oom=%d seen=%d%n",
                kept, failed, skipped, timed, oom, seen);
    }

    private static void worker() throws IOException {
        Firej firej = Firej.builder()
                .generator(GeneratorKind.INTERPRETER)
                .cache(CacheKind.DISABLED)
                .build();
        firej.compile("a");
        System.out.println("ready");
        System.out.flush();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            try {
                firej.compile(Regex101Data.unescape(line));
                System.out.println("ok");
            } catch (OutOfMemoryError e) {
                throw e;
            } catch (Throwable e) {
                System.out.println("fail");
            }
            System.out.flush();
        }
    }

    private enum Verdict {
        OK, FAIL, TIMEOUT, OOM
    }

    private static final class Worker implements AutoCloseable {
        private Process process;
        private BufferedWriter toChild;
        private BufferedReader fromChild;
        private int compilesSinceRestart;
        private boolean lastReadTimedOut;

        Worker() throws IOException, InterruptedException {
            start();
        }

        Verdict compile(String escapedPattern) throws IOException, InterruptedException {
            if (compilesSinceRestart >= RESTART_EVERY) {
                restart();
            }
            ensureAlive();
            toChild.write(escapedPattern);
            toChild.write('\n');
            toChild.flush();
            String reply = readLine(COMPILE_TIMEOUT_MS);
            if (reply == null) {
                Verdict verdict = lastReadTimedOut ? Verdict.TIMEOUT
                        : harvest() == 1 ? Verdict.OOM : Verdict.FAIL;
                restart();
                return verdict;
            }
            compilesSinceRestart++;
            return switch (reply) {
                case "ok" -> Verdict.OK;
                case "fail" -> Verdict.FAIL;
                default -> Verdict.FAIL;
            };
        }

        private String readLine(long timeoutMs) throws IOException, InterruptedException {
            lastReadTimedOut = false;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (System.nanoTime() < deadline) {
                if (fromChild.ready()) {
                    return fromChild.readLine();
                }
                if (!process.isAlive()) {
                    return null;
                }
                Thread.sleep(5);
            }
            if (fromChild.ready()) {
                return fromChild.readLine();
            }
            lastReadTimedOut = true;
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
            return null;
        }

        private int harvest() throws InterruptedException {
            process.waitFor(2, TimeUnit.SECONDS);
            return process.exitValue();
        }

        private void ensureAlive() throws IOException, InterruptedException {
            if (process == null || !process.isAlive()) {
                start();
            }
        }

        private void restart() throws IOException, InterruptedException {
            close();
            start();
        }

        private void start() throws IOException, InterruptedException {
            ProcessBuilder pb = new ProcessBuilder(List.of(
                    ChildJvm.java(),
                    "-Xmx" + WORKER_HEAP,
                    "-Xms32m",
                    "-XX:+ExitOnOutOfMemoryError",
                    "-cp",
                    ChildJvm.classpath(),
                    Regex101Filter.class.getName(),
                    "--worker"));
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = pb.start();
            toChild = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            fromChild = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            compilesSinceRestart = 0;
            String ready = readLine(10_000);
            if (!"ready".equals(ready)) {
                close();
                throw new IOException("compile worker failed to start");
            }
        }

        @Override
        public void close() {
            if (process != null) {
                process.destroyForcibly();
                try {
                    process.waitFor(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            process = null;
            toChild = null;
            fromChild = null;
        }
    }
}
