# antlr-native-repro

Minimal reproduction of a **Kotlin/Native release-mode crash** in
[`antlr-kotlin-runtime`](https://github.com/Strumenta/antlr-kotlin)'s
full-context ("full LL") ATN prediction path
(`ParserATNSimulator.computeReachSet`).

This is distilled from a real crash hit in
[JugglingLab](https://github.com/jkboyce/jugglinglab) (a Kotlin Multiplatform
app), fixed in commit `a647fd56a4f6dec6eec7c85c6bcd399b642d061d` by forcing
`PredictionMode.SLL` on the parser. Versions here are pinned to match that
project exactly: **Kotlin 2.3.21**, **antlr-kotlin 1.0.10**, **Gradle
9.4.1**.

## The idea

`Repro.g4` (under `antlr/`) has one deliberately ambiguous rule:

```antlr
stat : ID ';'   // alt 1
     | ID ';'   // alt 2 -- identical to alt 1
     ;
```

Because both alternatives accept exactly the same input, ANTLR4's adaptive
LL(*) algorithm can't resolve that decision with single-token-lookahead
(SLL) prediction alone. It has to escalate to full-context ("full LL")
prediction to confirm the ambiguity -- which is exactly the code path
(`computeReachSet`) that appears to break under Kotlin/Native's Release
optimizer. In a *Debug* build (no `-opt`) this all works fine; in a
*Release* build it's expected to crash.

`src/commonMain/kotlin/repro/ReproParser.kt` has a single toggle,
`FORCE_SLL_WORKAROUND`, mirroring the JugglingLab fix. Left at `false`
(default), the parser uses ANTLR4's normal adaptive mode and should hit the
crash on Release/Native. Flip it to `true` and it forces SLL-only
prediction (never escalates), which is expected to make the crash go away
on every target/build type.

## ⚠️ Note on how far I could validate this myself

I generated and hand-checked all of this code, but I could not actually run
a full Gradle build in my own sandbox -- it doesn't have network access to
Maven Central, only to a handful of registries (npm, PyPI, etc.), so
dependency resolution for Kotlin/antlr-kotlin fails there. I also can't
build Apple targets (macOS/iOS) from Linux at all -- Kotlin/Native only
cross-compiles Apple targets from a macOS host.

So: the grammar, Kotlin syntax, and Gradle wiring below are careful, but
**your first build on your Mac is the first real test.** If anything fails
at the `generateGrammarSource` or compile step (as opposed to actually
running and crashing/succeeding), paste me the output and I'll fix it fast
-- that would be a mistake in this scaffolding, not something informative
about the underlying bug.

## Running it

### 1. JVM baseline (expected: always succeeds)

```
./gradlew runJvm
```

This should print a successful parse regardless of `FORCE_SLL_WORKAROUND`,
since the JVM has no separate "release optimizer" the way Kotlin/Native
does. This is the control case showing the bug is Native-specific.

### 2. macOS native (expected: Release crashes, Debug succeeds)

This is the easiest way to reproduce the actual bug -- same Kotlin/Native
LLVM backend and release optimizer as iOS, but runs directly on your Mac
with no simulator or signing needed.

```
./gradlew runDebugExecutableMacosArm64      # expected: succeeds, prints parse result
./gradlew runReleaseExecutableMacosArm64    # expected: crashes
```

(Use `MacosX64` instead of `MacosArm64` if you're on an Intel Mac.)

### 3. iOS (closer to the original failure environment)

Gradle only auto-generates a `run...` task for targets matching your host
architecture, so for iOS you build and then run the binary yourself:

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
equivalent). Expected result: it now succeeds, same as Debug -- confirming
the same fix that worked in JugglingLab also fixes it here.

## Expected results summary

| Target                  | Build type | `FORCE_SLL_WORKAROUND=false` | `FORCE_SLL_WORKAROUND=true` |
|--------------------------|------------|-------------------------------|-------------------------------|
| JVM                      | n/a        | succeeds                      | succeeds                      |
| macOS (arm64/x64)        | Debug      | succeeds                      | succeeds                      |
| macOS (arm64/x64)        | Release    | **crashes**                   | succeeds                      |
| iOS simulator (arm64)    | Debug      | succeeds                      | succeeds                      |
| iOS simulator (arm64)    | Release    | **crashes**                   | succeeds                      |

If your results differ from this table -- e.g. it doesn't crash anywhere,
or it crashes even with the workaround -- that's actually useful
information too (maybe the bug is narrower or wider than we think); let me
know what you see either way before we write up the issue.

## Filing this upstream

Once confirmed, the recommended path is to file against
[Strumenta/antlr-kotlin](https://github.com/Strumenta/antlr-kotlin/issues)
first, since they can either identify a runtime-side fix or redirect to
JetBrains' YouTrack (Kotlin project "KT") with a more surgical
compiler-only reproduction if it turns out to be true Kotlin/Native
miscompilation rather than a library issue. Include:

- This repo (push it to its own public GitHub repo first).
- Exact toolchain versions: Kotlin 2.3.21, antlr-kotlin 1.0.10, Gradle
  9.4.1, plus your Xcode/macOS version.
- The results table above, filled in with what you actually observed.
- The crash output itself (exception text, or for a native trap, the crash
  log -- symbolicate with the `.dSYM` if you can get one).
- A one-line pointer to the workaround: forcing
  `parser.interpreter.predictionMode = PredictionMode.SLL` avoids the
  crash, which narrows it to the full-context prediction path.
