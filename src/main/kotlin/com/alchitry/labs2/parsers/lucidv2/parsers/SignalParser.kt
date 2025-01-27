package com.alchitry.labs2.parsers.lucidv2.parsers

import com.alchitry.labs2.parsers.grammar.LucidBaseListener
import com.alchitry.labs2.parsers.grammar.LucidParser.*
import com.alchitry.labs2.parsers.lucidv2.context.LucidBlockContext
import com.alchitry.labs2.parsers.lucidv2.context.LucidExprContext
import com.alchitry.labs2.parsers.lucidv2.types.*
import com.alchitry.labs2.parsers.lucidv2.types.Function
import com.alchitry.labs2.parsers.lucidv2.values.*
import org.antlr.v4.kotlinruntime.ParserRuleContext

fun ParserRuleContext.firstParentOrNull(condition: (ParserRuleContext) -> Boolean): ParserRuleContext? {
    var context = this.parent as? ParserRuleContext ?: return null
    while (!condition(context)) {
        context = context.parent as? ParserRuleContext ?: return null
    }
    return context
}

data class SignalParser(
    private val context: LucidExprContext,
    private val signals: MutableMap<SignalContext, SignalOrSubSignal> = mutableMapOf(),
    private val usedChildrenCache: MutableMap<SignalContext, Int> = mutableMapOf(),
    private val signalWidths: MutableMap<SignalWidthContext, SignalWidth> = mutableMapOf()
) : LucidBaseListener() {
    fun withContext(context: LucidExprContext) = copy(context = context)

    fun resolve(sigCtx: SignalContext): SignalOrSubSignal? = signals[sigCtx]
    fun resolve(ctx: SignalWidthContext): SignalWidth? = signalWidths[ctx]

    override fun enterRepeatBlock(ctx: RepeatBlockContext) {
        if (context !is LucidBlockContext || context.stage == ParseStage.Evaluation)
            return

        val repCtx = ctx.parent as? RepeatStatContext ?: error("RepeatBlockContext parent wasn't a RepeatStatContext!")

        val repeatSignals = context.blockParser.repeatSignals
        val newSig = repeatSignals[repCtx] ?: return
        context.localSignals[newSig.name] = newSig
    }

    override fun exitRepeatBlock(ctx: RepeatBlockContext) {
        if (context !is LucidBlockContext || context.stage == ParseStage.Evaluation)
            return

        val repCtx = ctx.parent as? RepeatStatContext ?: error("RepeatBlockContext parent wasn't a RepeatStatContext!")
        context.localSignals.remove(repCtx.name()?.text)
    }

    override fun enterFunctionBody(ctx: FunctionBodyContext) {
        if (context !is LucidBlockContext || context.stage == ParseStage.Evaluation)
            return

        val funcCtx =
            ctx.parent as? FunctionBlockContext ?: error("FunctionBodyContext parent wasn't a FunctionBlockContext!")

        val function = context.resolveFunction(funcCtx.name()?.text ?: return) as? Function.Custom ?: return
        function.args.forEach {
            context.localSignals[it.name] =
                Signal(it.name, SignalDirection.Read, null, it.width.filledWith(Bit.B0, false, it.signed))
        }
    }

    override fun exitFunctionBody(ctx: FunctionBodyContext) {
        if (context !is LucidBlockContext || context.stage == ParseStage.Evaluation)
            return

        val funcCtx =
            ctx.parent as? FunctionBlockContext ?: error("FunctionBodyContext parent wasn't a FunctionBlockContext!")

        val function = context.resolveFunction(funcCtx.name()?.text ?: return) as? Function.Custom ?: return
        function.args.forEach {
            context.localSignals.remove(it.name)
        }
    }

    override fun exitSignal(ctx: SignalContext) {
        val cachedUsedChildren = usedChildrenCache[ctx]
        val cachedSignalOrSubSignal = signals[ctx]

        val children = ctx.children?.filter { it is NameContext || it is BitSelectionContext } ?: emptyList()

        var usedChildren: Int
        var currentSignalOrParent: SignalOrParent
        if (cachedUsedChildren == null || cachedSignalOrSubSignal == null) {
            val nameCtx = ctx.name()
            val firstName = nameCtx.firstOrNull() ?: return
            val signalOrParent = context.resolveSignal(firstName.text)

            if (signalOrParent == null) {
                context.reportError(nameCtx.first(), "Failed to resolve signal \"${firstName.text}\"")
                return
            }

            usedChildren = 1
            currentSignalOrParent = signalOrParent

            while (currentSignalOrParent is SignalParent) {
                if (children.size < usedChildren + 1) {
                    context.reportError(
                        nameCtx[usedChildren - 1],
                        "${nameCtx[usedChildren - 1].text} is not a signal and can't be accessed directly."
                    )
                    return
                }
                if (children[usedChildren] is BitSelectionContext) {
                    context.reportError(
                        children[usedChildren] as ParserRuleContext,
                        "${nameCtx[usedChildren].text} is not an array."
                    )
                    return
                }
                val sig = currentSignalOrParent.getSignal(children[usedChildren].text)
                if (sig == null) {
                    context.reportError(
                        children[usedChildren] as ParserRuleContext,
                        "Failed to resolve signal ${firstName.text}.${children[usedChildren].text}"
                    )
                    return
                }
                usedChildren += 1
                currentSignalOrParent = sig
            }

            //usedChildrenCache[ctx] = usedChildren
        } else {
            usedChildren = cachedUsedChildren
            currentSignalOrParent = cachedSignalOrSubSignal.getSignal()
        }

        val signal = currentSignalOrParent as Signal

        val selectionChildren = children.subList(usedChildren, children.size)
        val selectionMap = selectionChildren.flatMap { child ->
            when (child) {
                is NameContext -> listOf(SignalSelector.Struct(child.text) to child)
                is BitSelectionContext -> context.resolve(child)
                    .map { SignalSelector.Bits(it.range, it.selectionCtx) to it.ctx }

                else -> error("Signal child was not a NameContext or BitSelectionContext")
            }
        }.toMap()

        // don't skip local signals as they change
        if (context !is LucidBlockContext || !context.localSignals.contains(signal.name))
            ctx.skip = selectionChildren.all {
                when (it) {
                    is NameContext -> true
                    is BitSelectionContext -> it.skip
                    else -> error("Signal child was not a NameContext or BitSelectionContext")
                }
            }

        val sigSelection = selectionMap.keys.toList()

        val selectedSignal = if (sigSelection.isEmpty()) {
            signal
        } else {
            try {
                signal.select(sigSelection)
            } catch (e: SignalSelectionException) {
                context.reportError(selectionMap[e.selector]!!, e.message!!)
                return
            }
        }

        signals[ctx] = selectedSignal
    }

    override fun exitSignalWidth(ctx: SignalWidthContext) {
        val dims = ctx.arraySize()
            .asReversed()
            .map {
                (it.expr()?.let { it1 -> context.resolve(it1) } as? SimpleValue)?.toBigInt()?.intValueExact()
            }

        val structType = ctx.structType()?.let {
            context.resolve(it).also { v ->
                if (v == null)
                    context.reportError(
                        ctx.structType() ?: ctx,
                        "Failed to resolve struct type ${it.text}!"
                    )
            }
        }

        if (dims.contains(null)) {
            signalWidths[ctx] = ResolvableWidth(ctx)
            return
        }
        @Suppress("UNCHECKED_CAST")
        dims as List<Int>

        if (dims.isEmpty()) {
            signalWidths[ctx] = structType?.let { StructWidth(it) } ?: BitWidth
            return
        }

        val base = structType?.let { StructWidth(it) } ?: BitListWidth(dims[0])

        if (dims.size == 1 && structType == null) {
            signalWidths[ctx] = base
            return
        }

        var lastWidth: SignalWidth = base

        val offset = if (structType == null) 1 else 0

        repeat(dims.size - offset) {
            lastWidth = ArrayWidth(dims[it + offset], lastWidth)
        }

        signalWidths[ctx] = lastWidth
    }
}