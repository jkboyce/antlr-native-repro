# antlr-native-repro

Minimal reproduction of a **Kotlin/Native release-mode crash** in
[`antlr-kotlin-runtime`](https://github.com/Strumenta/antlr-kotlin)'s
full-context ("full LL") ATN prediction path
(`ParserATNSimulator.computeReachSet`).

This is distilled from a real crash hit in
[JugglingLab](https://github.com/jkboyce/jugglinglab) (a Kotlin Multiplatform
app) -- specifically the `pattern parsing non-first brace values` test in
`SiteswapPatternTest.kt` -- fixed in commit
`a647fd56a4f6dec6eec7c85c6bcd399b642d061d` by forcing `PredictionMode.SLL`
on the parser. Versions here are pinned to match that project exactly:
**Kotlin 2.3.21**, **antlr-kotlin 1.0.13**, **Gradle 9.4.1**.

**Status: confirmed reproduced** on macOS arm64 Release (see "Confirmed
results" below) -- no iOS simulator needed, since macOS and iOS share the
same Kotlin/Native LLVM backend and release optimizer.

## The idea

`antlr/Repro.g4` is a **minimal grammar distilled from JugglingLab's real
siteswap grammar** (`composeApp/antlr/JlSiteswap.g4`), reduced down to just
5 lines of rules while still reproducing the exact same crash:

```antlr
pattern : ( groupedpattern | solosequence )+ ;

groupedpattern : '(' pattern ')' ;

solosequence : ( throwvalue )+ ;

throwvalue : '{' DIGIT+ '}' | DIGIT ;

DIGIT : [0-9] ;
```

`ReproParser.kt` parses input at the `pattern` rule, exactly as
JugglingLab's own `SiteswapParser.kt` does, using inputs distilled from the
failing `pattern parsing non-first brace values` test:

```
"{5}{1}"   -- crashes
"5{1}"     -- crashes
"51"       -- succeeds (negative control)
"555"      -- succeeds (negative control)
"5"        -- succeeds (negative control)
```

Every piece of the grammar above is load-bearing -- removing any of them
(verified by direct experiment, see the comment block at the top of
`Repro.g4`) makes the crash go away:

- **`groupedpattern` recursing back into `pattern`.** Real rule recursion
  (not just a flat loop) is what makes the decision genuinely
  context-dependent, which is what forces ANTLR to escalate from SLL to
  full-context ("full LL") prediction -- exactly the code path
  (`computeReachSet`) that breaks under Kotlin/Native's Release optimizer.
  A version with the recursion removed still crashed, but stopped
  respecting the `PredictionMode.SLL` workaround below -- i.e. it was
  hitting a *different*, looser trigger for the same underlying
  miscompilation, not the exact mechanism from the original bug.
- **`solosequence` as a separate rule with its own `+` loop**, nested
  inside one alternative of `pattern`'s own `+` loop. Collapsing the two
  loops into one (inlining `solosequence` directly into `pattern`) makes
  the crash go away entirely.
- **`throwvalue`'s two-token-vs-one-token shapes** (`'{' DIGIT+ '}'` vs a
  bare `DIGIT`). Plain digit sequences never trigger the bug; only a
  brace-notation throw in non-first position does -- matching the original
  bug ("non-first brace values") exactly.

Passing notation, `WILDCARD`, `SWITCHREVERSE`, paired throws, hand
specifiers, modifiers, multi-throw brackets, letter/x/p throw values, the
`number` sub-rule, and whitespace handling were all removed and the crash
persisted, so none of that is necessary to reproduce this bug.

In a *Debug* build (no `-opt`) this all works fine; in a *Release* build it
crashes with a Kotlin exception (not a hard segfault):

```
RuntimeException: Unexpected receiver type: kotlin.collections.ArrayList
```

`src/commonMain/kotlin/repro/ReproParser.kt` has a single toggle,
`FORCE_SLL_WORKAROUND`, mirroring the JugglingLab fix. Left at `false`
(default), the parser uses ANTLR4's normal adaptive mode and hits the
crash on Release/Native. Flip it to `true` and it forces SLL-only
prediction (never escalates), which makes the crash go away on every
target/build type.

## Running it

### 1. JVM baseline (always succeeds)

```
./gradlew runJvm
```

Parses all inputs successfully regardless of `FORCE_SLL_WORKAROUND`, since
the JVM has no separate "release optimizer" the way Kotlin/Native does.
This is the control case showing the bug is Native-specific.

### 2. macOS native (Release crashes, Debug succeeds)

This is the easiest way to reproduce the actual bug -- same Kotlin/Native
LLVM backend and release optimizer as iOS, but runs directly on your Mac
with no simulator or signing needed.

```
./gradlew runDebugExecutableMacosArm64      # succeeds, prints parse results
./gradlew runReleaseExecutableMacosArm64    # crashes with RuntimeException
```

(Apple Silicon only -- `macosX64` was dropped from `build.gradle.kts` since
it's deprecated as of Kotlin 2.3.20.)

### 3. iOS (closer to the original failure environment)

Not needed to reproduce (confirmed on macOS already), but if you want to
double check on iOS specifically:

```
./gradlew linkReleaseExecutableIosSimulatorArm64
./gradlew linkDebugExecutableIosSimulatorArm64
```

The built binaries land at:

```
build/bin/iosSimulatorArm64/releaseExecutable/antlr-native-repro.kexe
build/bin/iosSimulatorArm64/debugExecutable/antlr-native-repro.kexe
```

Since this is a bare console binary (no UIKit), it's usually runnable
directly on an Apple Silicon Mac without booting a simulator:

```
build/bin/iosSimulatorArm64/releaseExecutable/antlr-native-repro.kexe
```

If that doesn't run directly (missing simulator frameworks in the
environment), fall back to running it inside a booted simulator:

```
xcrun simctl boot "iPhone 15" 2>/dev/null || true
xcrun simctl spawn booted build/bin/iosSimulatorArm64/releaseExecutable/antlr-native-repro.kexe
```

`iosArm64` (physical device) also builds via
`./gradlew linkReleaseExecutableIosArm64`, but running it requires
packaging into a signed `.app` and deploying to a device -- only worth
doing if you want to nail down that it's not simulator-specific.

### 4. Confirming the workaround

Edit `src/commonMain/kotlin/repro/ReproParser.kt`, flip:

```kotlin
const val FORCE_SLL_WORKAROUND = false
```

to `true`, then re-run `runReleaseExecutableMacosArm64` (or the iOS
equivalent). Result: it now succeeds, same as Debug -- confirming the same
fix that worked in JugglingLab also fixes it here.

## Confirmed results

| Target             | Build type | `FORCE_SLL_WORKAROUND=false`                                    | `FORCE_SLL_WORKAROUND=true` |
|---------------------|------------|-------------------------------------------------------------------|-------------------------------|
| JVM                  | n/a        | succeeds                                                           | succeeds                      |
| macOS arm64          | Debug      | succeeds                                                           | succeeds                      |
| macOS arm64          | Release    | **crashes** -- `RuntimeException: Unexpected receiver type: kotlin.collections.ArrayList` (brace inputs only; plain-digit inputs still succeed) | succeeds |
| iOS simulator (arm64)| Debug      | not tested (macOS confirms it; same Native backend)                | --                             |
| iOS simulator (arm64)| Release    | not tested (macOS confirms it; same Native backend)                | --                             |

## Filing this upstream

Recommended path: file against
[Strumenta/antlr-kotlin](https://github.com/Strumenta/antlr-kotlin/issues)
first, since they can either identify a runtime-side fix or redirect to
JetBrains' YouTrack (Kotlin project "KT") with a more surgical
compiler-only reproduction if it turns out to be true Kotlin/Native
miscompilation rather than a library issue. Include:

- This repo: https://github.com/jkboyce/antlr-native-repro
- Exact toolchain versions: Kotlin 2.3.21, antlr-kotlin 1.0.13, Gradle
  9.4.1, plus your Xcode/macOS version.
- The confirmed-results table above.
- The crash output: `RuntimeException: Unexpected receiver type:
  kotlin.collections.ArrayList`.
- A one-line pointer to the workaround: forcing
  `parser.interpreter.predictionMode = PredictionMode.SLL` avoids the
  crash, which narrows it to the full-context prediction path
  (`ParserATNSimulator.computeReachSet`).
