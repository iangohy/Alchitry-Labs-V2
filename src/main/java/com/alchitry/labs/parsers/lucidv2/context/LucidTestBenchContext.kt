package com.alchitry.labs.parsers.lucidv2.context

import com.alchitry.labs.parsers.errors.ErrorListener
import com.alchitry.labs.parsers.lucidv2.ErrorCollector
import com.alchitry.labs.parsers.lucidv2.grammar.LucidParser.SourceContext
import com.alchitry.labs.parsers.lucidv2.parsers.ParseTreeMultiWalker
import com.alchitry.labs.parsers.lucidv2.parsers.TestBenchParser
import com.alchitry.labs.parsers.lucidv2.parsers.WalkerFilter

class LucidTestBenchContext(
    val project: ProjectContext,
    val errorCollector: ErrorCollector = ErrorCollector()
) : ErrorListener by errorCollector {

    val testBench = TestBenchParser(this)

    fun walk(t: SourceContext) = ParseTreeMultiWalker.walk(
        listOf(testBench),
        t,
        WalkerFilter.join(WalkerFilter.TestBenchesOnly, WalkerFilter.SkipModuleBodies)
    )
}