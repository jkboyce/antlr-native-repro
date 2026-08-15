//
// build.gradle.kts
//
// Minimal reproduction of a Kotlin/Native release-mode crash in
// antlr-kotlin-runtime's full-context ("full LL") ATN prediction path
// (ParserATNSimulator.computeReachSet), first hit in JugglingLab
// (see commit a647fd56a4f6dec6eec7c85c6bcd399b642d061d).
//
// Versions below are pinned to match the JugglingLab project exactly
// (Kotlin 2.3.21, antlr-kotlin 1.0.10) so this reproduces under the same
// toolchain that hit the original bug.
//
// Useful commands:
// - `./gradlew runReleaseExecutableMacosArm64`  -- expected to CRASH
// - `./gradlew runDebugExecutableMacosArm64`    -- expected to SUCCEED
// - `./gradlew runJvm`                          -- expected to SUCCEED (JVM is unaffected)
// - `./gradlew linkReleaseExecutableIosSimulatorArm64` -- builds the iOS-simulator binary; see README for how to run it
//

import com.strumenta.antlrkotlin.gradle.AntlrKotlinTask

plugins {
    kotlin("multiplatform") version "2.3.21"
    id("com.strumenta.antlr-kotlin") version "1.0.10"
}

repositories {
    mavenCentral()
}

// Custom task to generate the Kotlin lexer/parser from the ambiguous
// grammar under antlr/. Mirrors JugglingLab's own
// `generateKotlinGrammarSource` task.
val generateGrammarSource = tasks.register<AntlrKotlinTask>("generateGrammarSource") {
    description = "Generates the Kotlin parser/lexer for the ambiguous repro grammar"

    source = fileTree(layout.projectDirectory.dir("antlr")) {
        include("**/*.g4")
    }

    val pkgName = "repro.generated"
    packageName = pkgName

    // we want visitors alongside listeners, matching JugglingLab's setup
    // (grammar source is antlr/Repro.g4, a minimal grammar distilled from
    // JugglingLab's composeApp/antlr/JlSiteswap.g4 -- see README)
    arguments = listOf("-visitor")

    val outDir = "generatedAntlr/${pkgName.replace(".", "/")}"
    outputDirectory = layout.buildDirectory.dir(outDir).get().asFile
}

kotlin {
    jvm()

    // Primary repro target: builds/runs directly on the host Mac with no
    // simulator or signing setup required. Uses the exact same
    // Kotlin/Native LLVM backend and release optimizer as the iOS targets
    // below.
    macosArm64 {
        binaries {
            executable {
                entryPoint = "repro.main"
            }
        }
    }
    macosX64 {
        binaries {
            executable {
                entryPoint = "repro.main"
            }
        }
    }

    // Closer to the original failure environment (iOS). Gradle won't
    // generate an automatic `run...` task for these since they don't match
    // the host's own architecture -- see README for how to run the built
    // binaries.
    iosArm64 {
        binaries {
            executable {
                entryPoint = "repro.main"
            }
        }
    }
    iosSimulatorArm64 {
        binaries {
            executable {
                entryPoint = "repro.main"
            }
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(provider { generateGrammarSource.get() })
            dependencies {
                implementation("com.strumenta:antlr-kotlin-runtime:1.0.10")
            }
        }
    }
}

// Simple JVM run task (no `application` plugin in play). This is the
// baseline: expected to succeed regardless of FORCE_SLL_WORKAROUND, since
// the bug is specific to Kotlin/Native's release-mode optimizer.
val jvmMainCompilation = kotlin.targets.getByName("jvm").compilations.getByName("main")

tasks.register<JavaExec>("runJvm") {
    group = "application"
    description = "Runs the repro on the JVM target (expected to always succeed)"
    dependsOn(jvmMainCompilation.compileAllTaskName)
    mainClass.set("repro.MainKt")
    classpath = files(jvmMainCompilation.output.allOutputs) + configurations.getByName("jvmRuntimeClasspath")
}
