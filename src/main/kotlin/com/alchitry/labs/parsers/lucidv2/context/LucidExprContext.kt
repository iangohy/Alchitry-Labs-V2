package com.alchitry.labs.parsers.lucidv2.context

import com.alchitry.labs.parsers.errors.ErrorListener
import com.alchitry.labs.parsers.grammar.LucidParser.*
import com.alchitry.labs.parsers.lucidv2.parsers.BitSelection
import com.alchitry.labs.parsers.lucidv2.signals.SignalOrParent
import com.alchitry.labs.parsers.lucidv2.signals.SignalOrSubSignal
import com.alchitry.labs.parsers.lucidv2.types.*
import com.alchitry.labs.parsers.lucidv2.types.Function
import com.alchitry.labs.parsers.lucidv2.values.SignalWidth
import com.alchitry.labs.parsers.lucidv2.values.Value

interface LucidExprContext : ErrorListener {
    fun resolve(exprCtx: ExprContext): Value?
    fun resolve(bitSelectionContext: BitSelectionContext): List<BitSelection>
    fun resolve(signalCtx: SignalContext): SignalOrSubSignal?
    fun resolve(signalWidthContext: SignalWidthContext): SignalWidth?
    fun resolve(structTypeContext: StructTypeContext): StructType?
    fun resolve(structDecContext: StructDecContext): StructType?
    fun resolve(enumDecContext: EnumDecContext): EnumType?
    fun resolve(constDecContext: ConstDecContext): Constant?
    fun resolveSignal(name: String): SignalOrParent?
    fun resolveStruct(name: String): StructType?
    fun resolveGlobal(name: String): GlobalNamespace?
    fun resolveFunction(name: String): Function? = Function.builtIn.firstOrNull { it.label == name }
    val evalContext: Evaluable?
    val project: ProjectContext
}