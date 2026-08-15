package repro

// Inputs taken directly from JugglingLab's `pattern parsing non-first brace
// values` test (SiteswapPatternTest.kt), which is what exposed the crash.
private val INPUTS = listOf("{5}{1}", "5{1}", "{5}1{5}1")

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
