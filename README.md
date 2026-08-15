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
**Kotlin 2.3.21**, **antlr-kotlin 1.0.10**, **Gradle 9.4.1**.

**Status: confirmed reproduced** on macOS arm64 Release (see "Confirmed
results" below) -- no iOS simulator needed, since macOS and iOS share the
same Kotlin/Native LLVM backend and release optimizer.

## The idea

`antlr/JlSiteswap.g4` is JugglingLab's actual siteswap-notation grammar,
copied verbatim from
`composeApp/antlr/JlSiteswap.g4` in that repo. `ReproParser.kt` parses
input at the `pattern` rule, exactly as JugglingLab's own
`SiteswapParser.kt` does, using these inputs (taken directly from the
failing test):

```
"{5}{1}"
"5{1}"
"{5}1{5}1"
```

These all use `{...}` brace-notation throws in non-first position. That
shape drives the parser into an ambiguous decision that ANTLR4's adaptive
LL(*) algorithm can't resolve with single-token-lookahead (SLL) prediction
alone, forcing escalation to full-context ("full LL") prediction --
exactly the code path (`computeReachSet`) that breaks under Kotlin/Native's
Release optimizer. In a *Debug* build (no `-opt`) this all works fine; in a
*Release* build it crashes with a Kotlin exception (not a hard segfault):

```
RuntimeException: Unexpected receiver type: kotlin.collections.ArrayList
```

(An earlier version of this repro used a small synthetic grammar with a
single locally-ambiguous rule. That did **not** reproduce the crash --
likely because that decision was resolvable within SLL itself, since it
wasn't context-dependent across multiple call sites the way `pattern`'s
recursion through `groupedpattern` is. Switching to the real grammar and
real failing inputs above is what made this reproduce.)

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

Parses all three inputs successfully regardless of `FORCE_SLL_WORKAROUND`,
since the JVM has no separate "release optimizer" the way Kotlin/Native
does. This is the control case showing the bug is Native-specific.

### 2. macOS native (Release crashes, Debug succeeds)

This is the easiest way to reproduce the actual bug -- same Kotlin/Native
LLVM backend and release optimizer as iOS, but runs directly on your Mac
with no simulator or signing needed.

```
./gradlew runDebugExecutableMacosArm64      # succeeds, prints parse results
./gradlew runReleaseExecutableMacosArm64    # crashes with RuntimeException
```

(Use `MacosX64` instead of `MacosArm64` if you're on an Intel Mac.)

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
| macOS arm64          | Release    | **crashes** -- `RuntimeException: Unexpected receiver type: kotlin.collections.ArrayList` (all 3 inputs) | succeeds |
| iOS simulator (arm64)| Debug      | not tested (macOS confirms it; same Native backend)                | --                             |
| iOS simulator (arm64)| Release    | not tested (macOS confirms it; same Native backend)                | --                             |

## Filing this upstream

Recommended path: file against
[Strumenta/antlr-kotlin](https://github.com/Strumenta/antlr-kotlin/issues)
first, since they can either identify a runtime-side fix or redirect to
JetBrains' YouTrack (Kotlin project "KT") with a more surgical
compiler-only reproduction if it turns out to be true Kotlin/Native
miscompilation rather than a library issue. Include:

- This repo (push it to its own public GitHub repo first).
- Exact toolchain versions: Kotlin 2.3.21, antlr-kotlin 1.0.10, Gradle
  9.4.1, plus your Xcode/macOS version.
- The confirmed-results table above.
- The crash output: `RuntimeException: Unexpected receiver type:
  kotlin.collections.ArrayList`.
- A one-line pointer to the workaround: forcing
  `parser.interpreter.predictionMode = PredictionMode.SLL` avoids the
  crash, which narrows it to the full-context prediction path
  (`ParserATNSimulator.computeReachSet`).
