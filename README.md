# FIRE/J

Fast Implementation of Regular Expressions for Java. Each pattern is compiled
to a minimal DFA and then to a tailor-made class (via [ASM](https://asm.ow2.io/)),
so matching is a straight walk in JVM bytecode rather than an interpreted
automaton or a backtracking search.

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
import org.firej.Pattern;
import org.firej.Matcher;
import org.firej.Regex;

boolean ok = Regex.compile("[0-9]{3}-[0-9]{2}-[0-9]{4}").matches("333-22-4444");

Matcher m = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})").matcher("2026-10-07");
if (m.matches()) {
    m.group(1);   // "2026"
    m.start(2);   // 5
}

Matcher words = Pattern.compile("[a-z]+").matcher("one two  three");
while (words.find()) {
    System.out.println(words.group());   // one, two, three
}
```

`Pattern` and `Matcher` follow `java.util.regex`: a `Pattern` is immutable
and thread-safe, a `Matcher` (like a `Regex`) belongs to one thread.
`matches()`, `lookingAt()` and `find()` set `group`, `start` and `end`; a
group that took no part in the match is `null` with offsets `-1`. Any
`CharSequence` is accepted as input.

When only the span matters, skip group recovery and call `exec` directly. It
returns the exclusive end of the longest match from `start`, or `-1`:

```java
Regex r = Regex.compile("[0-9]+");
r.setData("abc123");
int end = r.exec(3);   // 6
```

Group recovery runs after the DFA has fixed the span, and on short inputs it
costs hundreds of times the walk.

### Matching semantics

FIRE/J is a DFA engine, so it reports the **leftmost-longest** match. It is
not the first match a backtracking engine finds. Both kinds of engine
recognise the same language, so `matches()` answers as `java.util.regex`
does. `lookingAt()` and `find()` can stop later:

| pattern | input | FIRE/J | `java.util.regex` |
|---|---|---|---|
| `a\|ab\|abc` | `abcd`, `lookingAt` | `abc` | `a` |
| `a\|ab` | `abab`, `find` × 2 | `ab`, `ab` | `a`, `a` |

Groups follow the POSIX rule: left to right, every quantifier and
alternative takes as much of the matched span as still lets the rest match.
Where the JDK's first parse is also that one, the two agree. They differ
when it is not:

| pattern | input | FIRE/J groups | `java.util.regex` groups |
|---|---|---|---|
| `(a\|ab)(c\|bcd)(d*)` | `abcd` | `ab`, `c`, `d` | `a`, `bcd`, (empty) |
| `(.*?)(\d+)` | `abc123` | `abc12`, `3` | `abc`, `123` |
| `(a*)+` | `aaa` | `aaa` | (empty) |

A lazy or possessive quantifier denotes the same language as the greedy one,
so FIRE/J accepts it and matches as if it were greedy. A repeated group keeps
its last iteration.

### Dialect

The default dialect, `EXTENDED`, is the Perl/Java syntax as far as a DFA can
take it:

- classes, ranges, negation, and `\d \w \s` with their negations
- the ASCII `\p{...}` / `\P{...}` classes and POSIX `[[:alpha:]]` classes
- escapes that name a character: `\t \n \xHH \x{...} \uHHHH \0oo \cX`, and `\Q...\E`
- `(?:...)`, named groups `(?<n>...)` / `(?P<n>...)`, and comments `(?#...)`
- `.`, which excludes newline
- a leading `^` (or `\A`) and a trailing `$` (or `\z`)

Anything a DFA cannot express is **rejected at compile time with
`RegexCompilationException`** instead of being misread. That covers word
boundaries, backreferences, lookaround, inline flags, conditionals, PCRE
verbs, code points above the BMP, an anchor anywhere but the two ends or next
to a top-level `|`, a quantifier with nothing to repeat, and reversed ranges
like `[z-a]` or `a{2,1}`.

`Firej.builder().dialect(Preprocessor.Dialect.POSIX)` restricts the syntax to
POSIX ERE, where a digit is `[[:digit:]]` and `\d` is an error.

### Configuring an engine

`Firej.standard()` is the default engine. `Firej.builder()` assembles another
one from four stages:

```java
import org.firej.Firej;
import org.firej.cache.CacheKind;
import org.firej.codegen.GeneratorKind;
import org.firej.parser.Pipeline;

Firej engine = Firej.builder()
        .pipeline(Pipeline.CLASSIC)              // preprocessor + parser
        .generator(GeneratorKind.INTERPRETER)
        .cache(CacheKind.DISABLED)
        .build();
engine.toString();   // Firej[BRICS > AUTOMATON > INTERPRETER, cache DISABLED]
Pattern p = Pattern.compile("a+", engine);
```

- **Pipeline** (preprocessor and parser). `NATIVE`, the default, parses the
  dialect directly into a minimal DFA: Thompson construction, subset
  construction and Hopcroft minimisation, all over character ranges.
  `CLASSIC` rewrites the pattern into the dialect of
  [dk.brics.automaton](https://www.brics.dk/automaton/) and lets that library
  build the DFA, as the 2007 engine did.
- **Generator.**
  - `BYTECODE` (the default) is one block of code per DFA state, jumping
    straight to the next state's block, as in the 2008 paper, with a
    character-class map so that a state with many ranges resolves a
    character by one table lookup and a jump table.
  - `BYTECODE_RANGES` is the same threading with a compare chain per state.
  - `BYTECODE_SWITCH` is the loop-and-`tableswitch` form.
  - `INTERPRETER` walks the tables.

  A threaded walk whose bytecode would exceed 8,000 bytes, the size past which
  HotSpot never JIT-compiles a method, is split into several methods that
  hand the state number between them, so large automata keep running compiled
  code.
- **Cache.** `MEMORY` (the default) keeps every compiled template,
  `LAST_INSTANCE` only the last one, and `DISABLED` none. `engine.evict(pattern)`
  and `engine.clearCache()` drop templates.

### Inspecting what a pattern compiles to

```java
Firej engine = Firej.standard();
FlattenedDfa dfa = engine.automaton("^[0-9]+$");   // states, accept flags, ranges, anchors
String dot = engine.toDot("(a|b)*abb");            // Graphviz: dot -Tsvg
String java = engine.toJavaSource("[0-9]+");       // equivalent, compilable Java source
```

Any `DFA` also renders itself with `toDot()`.

### Extension points

Each stage is an interface, and the builder accepts an instance in place of a
kind:

| stage | interface | builder method |
|---|---|---|
| rewrite the pattern, lift the anchors | `org.firej.dfa.Preprocessor` | `preprocessor(...)` |
| build the automaton | `org.firej.dfa.Parser` returning a `DFA` of `State`s and `Transition`s | `parser(...)` |
| turn the automaton into matchers | `org.firej.codegen.CodeGenerator` | `generator(...)` |
| keep compiled templates | `org.firej.cache.RegexCache` | `cache(...)` |

For example, a parser can hand over an automaton built some other way, and
every generator will compile it. A preprocessor can add a free-spacing mode in
front of the native one. A generator can wrap another to count or time
compiles, and a cache can be bounded or shared. `LibraryOutletsTest` has a
working example of each.

The builder refuses one pairing: an un-rewriting preprocessor with the
dk.brics parser. That parser has no character classes, so `\d` would silently
become the letter `d`.

## How a match runs

`Firej.template(pattern)` asks the cache for a template. On a miss, the
pattern goes through four steps:

1. The preprocessor lifts a leading `^` and a trailing `$` out as flags. In
   the `NATIVE` pipeline that is all it does. In `CLASSIC` it also rewrites
   shorthands, escapes and classes into the dk.brics dialect.
2. The parser builds a minimal DFA.
3. The DFA is flattened to dense state numbers, assigned along the paths a
   walk takes.
4. The generator turns it into a template, together with the pattern's
   capture plan. The plan is built once here, so a pattern whose groups
   cannot be recovered fails before it is cached.

`compile()` returns a fresh `Regex` from that template. A template may be
shared between threads; a matcher may not.

`Regex.exec(start)` walks the input from `start` and returns the exclusive
end of the longest match, or `-1`. An empty match at an accepting start
state returns `start`. `Regex.run(start)` does the same. Then, only if the
pattern has groups and the match succeeded, it fills them by re-parsing
exactly the span the DFA chose, memoized per node and span. Group recovery
costs far more than the walk, so `exec` is the call for span-only matching.
`Matcher.find()` tries every start position from the current one in turn;
there is no separate search automaton.

## Layout

- `org.firej`: the public API.
  - `Regex` is a compiled matcher, one per thread.
  - `Firej` is a configured engine (preprocessor, parser, generator and
    cache) built with `Firej.builder()`. `template(pattern)` hands out the
    cached `RegexTemplate` and `compile(pattern)` a fresh matcher from it.
    `automaton`, `toDot` and `toJavaSource` show what a pattern compiles to.
  - `Pattern` and `Matcher` are the `java.util.regex`-shaped facade, and
    `RegexFactory` a facade over `Firej.standard()`.
  - `RegexCompilationException` is what a pattern the engine cannot compile
    throws.
- `org.firej.dfa`: the pipeline's contracts.
  - `Preprocessor` (with its `Dialect`), `Parser`, and the read-only `DFA`,
    `State` and `Transition` model a parser returns.
  - `FlattenedDfa` is the dense form the generators consume, which carries
    the anchor flags, and `DotRenderer` draws it.
  - `BricsPreprocessor` is one left-to-right scan that translates the
    Perl/Java dialect into the dk.brics one.
- `org.firej.dfa.fire`: the native engine. `RegexParser` is a recursive
  descent over character sets, with no rewriting. `DfaCompiler` does the
  Thompson construction, the subset construction and Hopcroft minimisation,
  all over ranges. `ParserFire` is the `Parser`, and `IdentityPreprocessor`
  the first stage that goes with it.
- `org.firej.dfa.automaton`: the adapter over
  [dk.brics.automaton](https://www.brics.dk/automaton/), the `CLASSIC` engine.
- `org.firej.parser`: the stages as selectable kinds. `PreprocessorKind` is
  `IDENTITY` or `BRICS`, `ParserKind` is `FIRE` or `AUTOMATON`, and
  `Pipeline` names the two sound pairings, `NATIVE` and `CLASSIC`.
- `org.firej.codegen`:
  - `AsmCodeGenerator`, the three bytecode emitters.
  - `InterpreterCodeGenerator`.
  - `JavaSourceRenderer`, which renders the switch form as compilable Java
    source for inspection (`Firej.toJavaSource`).
- `org.firej.runtime`:
  - `CharArrayRegex`, the base of every matcher (the windowed input copy and
    `exec`).
  - `InterpreterRegex` and `GeneratedClassLoader`.
  - `ClassMap`, which builds a generated class's character-to-class table
    from its string constant.
- `org.firej.capture`: `ExprParser` and `CapturePlan`, which recover
  capturing groups over the span the DFA has already fixed. The generated
  code never knows about groups.
- `org.firej.cache`: `RegexCache` and the implementations behind `CacheKind`
  (`MEMORY`, `LAST_INSTANCE`, `DISABLED`). They store templates, not matcher
  instances.
- `org.firej.tools`:
  - `Main` and `MiniTest`.
  - `Regex101Data`, the corpus loader.
  - `Regex101Filter`, the corpus builder, which compiles each candidate in a
    child JVM so that a blow-up only kills that process.
  - `CodeSize`, the bytecode size of every method of a class file, read
    without loading it.
  - The benchmarks of the paper: `MiniBenchmark`; `Regex101Benchmark` (the
    SPE protocol against `java.util.regex`); `SizeBenchmark` (matching cost
    against the size of the generated code, with `-Dfirej.nosplit=true` to
    switch the split walk off); and `ExpressionMetrics` (Ehrenfeucht–Zeiger
    size and length).

## What changed from 0.71

| 2007 | Now |
|---|---|
| Ant + jars in `lib/` | Maven, Java 21, dependencies from Maven Central |
| Jasmin + `jsr`/`ret` written under `~/.firej` | ASM, in-memory class loading |
| dk.brics.automaton only; the native parser a stub | A native DFA engine by default, dk.brics as the `CLASSIC` pipeline |
| Apache Velocity templates | ASM + a Java source renderer for inspection |
| Commons Lang / Collections (unused) | removed |
| Compile errors returned `NullRegex` | `RegexCompilationException` |
| Global mutable plugin enums | `Firej.builder()` with pluggable stages |
| No tests | JUnit 5 |

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

`mvn -q test` runs about 4,200 tests. Most of them run against every engine
configuration (both pipelines × four generators):

- `RegexCasesTest` holds tables of whole-input matches with every group,
  longest prefixes, `find` sequences, patterns that must be rejected rather
  than misread, and real-world shapes such as IPv4 addresses, e-mail
  addresses, dates, URLs, version numbers and an Apache log line.
- `JdkOracleTest` uses `java.util.regex` as an exact oracle for the longest
  match from every offset of random inputs. The two engines report different
  matches but recognise the same language: the longest match from `start` is
  the largest `end` for which the JDK matches the region in full.
- `CaptureSemanticsTest` pins the POSIX group rule and exactly where it
  departs from the JDK.
- `LibraryOutletsTest` covers the extension points and inspection outlets.
  It compiles and runs the rendered Java source, and plugs in a custom
  parser, preprocessor, generator and cache.
- `PreProcessorTest`, `DialectTest` and `AnchorTest` pin the dialect,
  comparing every shorthand with the JDK's own character classes.
  `NativeEngineTest` covers the native engine's construction and
  minimisation.
- `SplitWalkTest` checks that every method of a split class stays under the
  JIT limit, and that a split walk agrees with the interpreter and with Brics
  across segment hops, anchors and the copy window.

The two corpora in `src/test/resources` are compared row by row against
Brics's `RunAutomaton`, and the native pipeline against the classic one:

- `regex.data` is the 2007 corpus of `pattern<TAB>input` rows.
- `regex101.data` is the FIRE/J-compilable slice of the
  [innovatorved/regex_dataset](https://huggingface.co/datasets/innovatorved/regex_dataset)
  dump of regex101.com (MIT), built with `Regex101Filter`. Rows whose sample
  text carried credential-shaped strings were removed.

Rows FIRE/J cannot compile are skipped.

## License

Apache License 2.0. See [License](License).

## The paper

The benchmarks and corpora here are those of *FIRE/J: Compiling Regular
Expressions to JIT-Compilable JVM Bytecode* (manuscript, 2026), which
supersedes Karakoidas & Spinellis, SPE 38(6):557–573, 2008. The ten-engine
benchmark the paper reports is [bkarak/regex-benchmark](https://github.com/bkarak/regex-benchmark),
which builds this library from a sibling checkout.
