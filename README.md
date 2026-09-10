# FIRE/J

Fast Implementation of Regular Expressions for Java. Each pattern is compiled
to a tailor-made class (via [ASM](https://asm.ow2.io/)), so matching is a
straight DFA walk in JVM bytecode rather than an interpreted automaton.

This is a modernization of the 2007/2008 engine described in:

> Vassilios Karakoidas and Diomidis Spinellis. FIRE/J — optimizing regular
> expression searches with generative programming. *Software: Practice and
> Experience*, 38(6):557–573, May 2008.
> [doi:10.1002/spe.841](https://doi.org/10.1002/spe.841)

## Requirements

- JDK 21 or later
- Maven 3.9+

## Build

```bash
mvn verify
```

## Usage

```java
import org.firej.Regex;
import org.firej.Pattern;

boolean ok = Regex.compile("[0-9]{3}-[0-9]{2}-[0-9]{4}")
        .matches("333-22-4444");

var matcher = Pattern.compile("[0-9]+").matcher("ab12cd34");
while (matcher.find()) {
    System.out.println(matcher.group()); // 12, then 34
}
```

`Firej.builder()` selects the parser, code generator, and cache:

```java
import org.firej.Firej;
import org.firej.codegen.GeneratorKind;

var interpreted = Firej.builder()
        .generator(GeneratorKind.INTERPRETER)
        .build();
interpreted.compile("a+").matches("aaa");
```

The default generator is `BYTECODE` (ASM, in-memory `defineClass`): one block
of code per DFA state, jumping straight to the next state's block, as in the
2008 paper, with a per-automaton character class map so that a state with
several ranges resolves its character by one table lookup and a jump table.
`BYTECODE_RANGES` is the same threading with a compare chain per state, and
`BYTECODE_SWITCH` the loop-and-`tableswitch` form; both are kept for
comparison. The input is copied into the matcher's buffer, a `String` only
64 characters at a time, the rest on the rare walk that runs past them. A
walk whose bytecode would exceed 8,000 bytes, the size past which HotSpot
never JIT-compiles a method, is split into several methods that hand the
state number between them, so large automata keep running compiled code;
only the loop-and-switch form falls back to the interpreter past the JVM's
64 KiB method cap. Tests check the four backends agree on every compilable
pattern.

Syntax is the Perl/Java dialect as far as a DFA can take it: `\d \w \s` and
their negations, the ASCII `\p{...}` / `\P{...}` classes, character escapes
(`\t \xHH \uHHHH \0oo \cX`, `\Q...\E`), `(?:...)` and named groups, lazy and
possessive quantifiers (same language), `.` excluding newline. A leading `^`
(or `\A`) and a trailing `$` (or `\z`) are enforced by the generated code;
anchors anywhere else, or next to a top-level `|`, are rejected. Word
boundaries, backreferences, lookaround and inline flags are rejected at
compile time rather than misread. This is a DFA engine: longest leftmost
match.

## How a match runs

`Firej.template(pattern)` asks the cache for a template. On a miss the
pattern is preprocessed (shorthands expanded, escapes resolved, a leading
`^` and a trailing `$` lifted out as flags), parsed to a Brics automaton,
determinized and minimized, flattened to dense state numbers assigned along
the paths a walk takes, and handed to the generator. `compile()` returns a
fresh `Regex` from that template: a template may be shared between threads,
a matcher may not.

`Regex.exec(start)` walks the input from `start` and returns the exclusive
end of the longest match, or `-1`. An empty match at an accepting start
state returns `start`. `Regex.run(start)` does the same and then, only if
the pattern has groups and the match succeeded, fills them by re-parsing
exactly the span the DFA chose, memoized per node and span. Group recovery
costs far more than the walk, so `exec` is the call for span-only matching.
`Matcher.find()` tries every start position from the current one in turn;
there is no separate search automaton.

## Layout

- `org.firej`: the public API. `Regex` is a compiled matcher, one per
  thread. `Firej` is a configured engine (parser, generator and cache) built
  with `Firej.builder()`; `template(pattern)` hands out the cached
  `RegexTemplate` and `compile(pattern)` a fresh matcher from it. `Pattern`
  and `Matcher` are the `java.util.regex`-shaped facade, `RegexFactory` a
  facade over `Firej.standard()`, and `RegexCompilationException` is what a
  pattern the engine cannot compile throws.
- `org.firej.dfa`: the `DFA`, `State` and `Transition` model; `FlattenedDfa`,
  the dense form the generators consume, which carries the anchor flags; and
  `PreProcessor`, one left-to-right scan that translates the Perl/Java
  dialect into the Brics one.
- `org.firej.dfa.automaton`: the adapter over
  [dk.brics.automaton](https://www.brics.dk/automaton/), which parses the
  processed expression and builds the minimized DFA. `org.firej.dfa.fire`
  is the unfinished native parser; `ParserKind.FIRE` fails fast.
- `org.firej.codegen`: `AsmCodeGenerator` (the three bytecode emitters),
  `InterpreterCodeGenerator`, and `JavaSourceRenderer`, which renders the
  switch form as Java source for inspection (`Firej.toJavaSource`).
- `org.firej.runtime`: `CharArrayRegex`, the base of every matcher (the
  windowed input copy and `exec`), `InterpreterRegex`,
  `GeneratedClassLoader`, and `ClassMap`, which builds a generated class's
  character-to-class table from its string constant.
- `org.firej.capture`: `ExprParser` and `CapturePlan`, which recover
  capturing groups over the span the DFA has already fixed. The generated
  code never knows about groups.
- `org.firej.cache`: the `RegexCache` implementations behind `CacheKind`
  (`MEMORY`, `LAST_INSTANCE`, `DISABLED`). They store templates, not matcher
  instances.
- `org.firej.tools`: `Main` and `MiniTest`, `Regex101Data` (the corpus
  loader), `Regex101Filter` (the corpus builder, which compiles each
  candidate in a child JVM so that a blow-up only kills that process),
  `CodeSize` (the bytecode size of every method of a class file, read without
  loading it), and the benchmarks of the paper: `MiniBenchmark`,
  `Regex101Benchmark` (the SPE protocol against `java.util.regex`),
  `SizeBenchmark` (matching cost against the size of the generated code, with
  `-Dfirej.nosplit=true` to switch the split walk off) and
  `ExpressionMetrics` (Ehrenfeucht–Zeiger size and length).

## What changed from 0.71

| 2007 | Now |
|---|---|
| Ant + jars in `lib/` | Maven, Java 21, dependencies from Maven Central |
| Jasmin + `jsr`/`ret` written under `~/.firej` | ASM, in-memory class loading |
| Apache Velocity templates | ASM + a Java source renderer for inspection |
| Commons Lang / Collections (unused) | removed |
| Compile errors returned `NullRegex` | `RegexCompilationException` |
| Global mutable plugin enums | `Firej.builder()` |
| No tests | JUnit 5 |

The unfinished native POSIX parser (`ParserKind.FIRE`) now fails fast instead
of compiling every pattern to an empty DFA.

## Command line

```bash
mvn -q test
mvn -q exec:java -Dexec.args=test
mvn -q exec:java -Dexec.args=benchmark            # IPv4 and two more vs Brics and java.util.regex, ~1 s
mvn -q exec:java -Dexec.args=benchmark-regex101   # SPE-protocol compile and match times, forked JVMs
mvn -q exec:java -Dexec.args=benchmark-size       # the size study: six walkers over synthetic families and both corpora
```

The first runs the JUnit suite. The second prints the MiniTest cases of the
2007 release against the current engine.

## Tests

`PreProcessorTest` and `AnchorTest` pin the dialect, comparing every
shorthand with the JDK's own character classes. `CapturingGroupTest` pins the
`java.util.regex` conventions for groups. `SplitWalkTest` checks that every
method of a split class stays under the JIT limit and that a split walk
agrees with the interpreter and with Brics across segment hops, anchors and
the copy window. The parity and corpus tests require all four back-ends to
agree.

The two corpora in `src/test/resources` are compared row by row against
Brics's `RunAutomaton`, which is fed the same preprocessed expression:
`regex.data` is the 2007 corpus of `pattern<TAB>input` rows, and
`regex101.data` the FIRE/J-compilable slice of the
[innovatorved/regex_dataset](https://huggingface.co/datasets/innovatorved/regex_dataset)
dump of regex101.com (MIT), built with `Regex101Filter`; rows whose sample
text carried credential-shaped strings were removed. Rows FIRE/J cannot
compile are skipped.

## License

Apache License 2.0. See [License](License).

## The paper

The benchmarks and corpora here are those of *FIRE/J: Compiling Regular
Expressions to JIT-Compilable JVM Bytecode* (manuscript, 2026), which
supersedes Karakoidas & Spinellis, SPE 38(6):557–573, 2008. The ten-engine
benchmark the paper reports is [bkarak/regex-benchmark](https://github.com/bkarak/regex-benchmark),
which builds this library from a sibling checkout.
