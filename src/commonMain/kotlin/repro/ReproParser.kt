package repro

import org.antlr.v4.kotlinruntime.BaseErrorListener
import org.antlr.v4.kotlinruntime.CharStreams
import org.antlr.v4.kotlinruntime.CommonTokenStream
import org.antlr.v4.kotlinruntime.RecognitionException
import org.antlr.v4.kotlinruntime.Recognizer
import org.antlr.v4.kotlinruntime.atn.PredictionMode
import repro.generated.JlSiteswapLexer
import repro.generated.JlSiteswapParser

// Flip this to `true` to apply the JugglingLab workaround (forcing SLL-only
// prediction, matching commit a647fd56a4f6dec6eec7c85c6bcd399b642d061d in
// the JugglingLab repo). With it `false` (the ANTLR4 default), a
// Kotlin/Native *Release* build of this project is expected to crash while
// parsing inputs like "5{1}" (non-first brace-notation throws). A *Debug*
// build, and the JVM target in either build type, are expected to succeed
// either way.
const val FORCE_SLL_WORKAROUND = false

class ReproParseException(message: String) : Exception(message)

object ReproParser {
    fun parse(input: String): String {
        val stream = CharStreams.fromString(input)
        val lexer = JlSiteswapLexer(stream)
        val tokens = CommonTokenStream(lexer)
        val parser = JlSiteswapParser(tokens)

        if (FORCE_SLL_WORKAROUND) {
            parser.interpreter.predictionMode = PredictionMode.SLL
        }
        // else: leave the ANTLR4 default (PredictionMode.LL), which
        // adaptively falls back to full-context prediction on ambiguous
        // decisions -- this is the path that exercises computeReachSet().

        val errors = mutableListOf<String>()
        val listener = object : BaseErrorListener() {
            override fun syntaxError(
                recognizer: Recognizer<*, *>,
                offendingSymbol: Any?,
                line: Int,
                charPositionInLine: Int,
                msg: String,
                e: RecognitionException?
            ) {
                errors.add("line $line:$charPositionInLine $msg")
            }
        }
        lexer.removeErrorListeners()
        lexer.addErrorListener(listener)
        parser.removeErrorListeners()
        parser.addErrorListener(listener)

        // matches how SiteswapParser.kt in JugglingLab enters the grammar
        val tree = parser.pattern()
        if (errors.isNotEmpty()) {
            throw ReproParseException(errors.joinToString("\n"))
        }
        return tree.toStringTree(parser)
    }
}
