//
// Repro.g4
//
// Minimal ANTLR4 grammar with a deliberate ambiguity: both alternatives of
// the `stat` rule accept exactly the same input. ANTLR4's adaptive LL(*)
// algorithm cannot resolve that decision using single-token-lookahead SLL
// prediction alone -- it must escalate to full-context ("full LL")
// prediction to confirm the ambiguity, which is what exercises
// ParserATNSimulator.computeReachSet(). That escalation path is what
// triggers the Kotlin/Native release-mode crash this project reproduces.
//

grammar Repro;

start : stat EOF ;

stat : ID ';'   // alt 1
     | ID ';'   // alt 2 -- identical to alt 1, so this decision is a real
                // ambiguity, not just a lookahead-resolvable choice
     ;

ID : [a-zA-Z]+ ;
WS : [ \t\r\n]+ -> skip ;
