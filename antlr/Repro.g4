//
// Repro.g4
//
// Minimal grammar distilled from JugglingLab's real siteswap grammar
// (composeApp/antlr/JlSiteswap.g4), reduced to the smallest structure that
// still reproduces the Kotlin/Native release-mode crash originally hit by
// its `pattern parsing non-first brace values` test.
//
// Every piece below is load-bearing -- removing any of them (verified by
// direct experiment) makes the crash go away:
//
//   - `pattern`'s alternation between `groupedpattern` and `solosequence`,
//     repeated with `+`, where `groupedpattern` recurses back into
//     `pattern`. Real rule recursion (not just a flat loop) is what makes
//     the decision genuinely context-dependent, which is what's needed to
//     force ANTLR to escalate from SLL to full-context ("full LL")
//     prediction -- and it's that escalation path (through
//     ParserATNSimulator.computeReachSet) that crashes under Kotlin/Native's
//     Release optimizer. A version of this grammar with the recursion
//     removed still crashed, but no longer respected the
//     `PredictionMode.SLL` workaround -- i.e. it was hitting a different
//     (looser) trigger for the same underlying miscompilation, not the
//     exact mechanism from the original bug.
//   - `solosequence` as a separate rule with its own `+` loop nested inside
//     one alternative of `pattern`'s own `+` loop. Inlining it directly
//     into `pattern` (collapsing the two loops into one) makes the crash go
//     away entirely.
//   - `throwvalue`'s two-token-vs-one-token shapes (`'{' DIGIT+ '}'` vs a
//     bare `DIGIT`). Plain digit sequences like "51" or "555" parse fine
//     even in Release; only inputs with a brace-notation throw in
//     non-first position (e.g. "5{1}") trigger the crash. This matches the
//     original bug exactly ("pattern parsing non-first brace values").
//
// Everything else in the real grammar -- passing notation, WILDCARD,
// SWITCHREVERSE, paired throws, hand specifiers, modifiers, multi-throw
// brackets, letter/x/p throw values, the `number` sub-rule, whitespace
// handling -- was removed and the crash persisted, so none of it is
// necessary to reproduce this bug.
//

grammar Repro;

pattern : ( groupedpattern | solosequence )+ ;

groupedpattern : '(' pattern ')' ;

solosequence : ( throwvalue )+ ;

throwvalue : '{' DIGIT+ '}' | DIGIT ;

DIGIT : [0-9] ;
