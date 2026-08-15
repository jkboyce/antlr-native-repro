package repro

// "5{1}" and "{5}{1}" are the minimal shape of JugglingLab's original
// crashing inputs (brace-notation throw in non-first position). "51",
// "555", and "5" are included as negative controls: plain digit sequences
// never trigger the bug, matching the original grammar's behavior exactly.
private val INPUTS = listOf("{5}{1}", "5{1}", "51", "555", "5")

fun runRepro() {
    println("=== antlr-kotlin / Kotlin-Native full-context prediction repro ===")
    println("FORCE_SLL_WORKAROUND = $FORCE_SLL_WORKAROUND")
    for (input in INPUTS) {
        println("Parsing input: \"$input\"")
        try {
            val result = ReproParser.parse(input)
            println("Parsed OK: $result")
        } catch (e: Throwable) {
            println("EXCEPTION: ${e::class.simpleName}: ${e.message}")
        }
    }
    println("(Reached end without a hard crash on this target/build type.)")
}
