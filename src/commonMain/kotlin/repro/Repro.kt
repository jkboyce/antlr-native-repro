package repro

private const val INPUT = "x;"

fun runRepro() {
    println("=== antlr-kotlin / Kotlin-Native full-context prediction repro ===")
    println("FORCE_SLL_WORKAROUND = $FORCE_SLL_WORKAROUND")
    println("Parsing input: \"$INPUT\"")
    val result = ReproParser.parse(INPUT)
    println("Parsed OK: $result")
    println("(No crash on this target/build type.)")
}
