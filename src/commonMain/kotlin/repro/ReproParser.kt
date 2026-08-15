package repro

import org.antlr.v4.kotlinruntime.BaseErrorListener
import org.antlr.v4.kotlinruntime.CharStreams
import org.antlr.v4.kotlinruntime.CommonTokenStream
import org.antlr.v4.kotlinruntime.RecognitionException
import org.antlr.v4.kotlinruntime.Recognizer
import org.antlr.v4.kotlinruntime.atn.PredictionMode
import repro.generated.ReproLexer
import repro.generated.ReproParser as GeneratedReproParser

// Flip this to `true` to apply the JugglingLab workaround (forcing SLL-only
// prediction). With it `false` (the ANTLR4 default), a Kotlin/Native
// *Release* build of this project is expected to crash while parsing. A
// *Debug* build, and the JVM target in either build type, are expected to
// succeed either way.
const val FORCE_SLL_WORKAROUND = false

class ReproParseException(message: String) : Exception(message)

object ReproParser {
    fun parse(input: String): String {
        val stream = CharStreams.fromString(input)
        val lexer = ReproLexer(stream)
        val tokens = CommonTokenStream(lexer)
        val parser = GeneratedReproParser(tokens)

        if (FORCE_SLL_WORKAROUND) {
            parser.interpreter.predictionMode = PredictionMode.SLL
        }
        // else: leave the ANTLR4 default (PredictionMode.LL), which
        // adaptively falls back to full-context prediction on ambiguous
        // decisions like the one in Repro.g4's `stat` rule.

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

        val tree = parser.start()
        if (errors.isNotEmpty()) {
            throw ReproParseException(errors.joinToString("\n"))
        }
        return tree.toStringTree(parser)
    }
}
