package helpers

import com.alchitry.labs.parsers.lucidv2.ErrorCollector
import com.alchitry.labs.parsers.lucidv2.context.LucidGlobalContext
import com.alchitry.labs.parsers.lucidv2.context.LucidModuleTypeContext
import com.alchitry.labs.parsers.lucidv2.context.ProjectContext
import com.alchitry.labs.parsers.lucidv2.grammar.LucidLexer
import com.alchitry.labs.parsers.lucidv2.grammar.LucidParser
import com.alchitry.labs.parsers.lucidv2.grammar.LucidParser.SourceContext
import com.alchitry.labs.parsers.lucidv2.types.Module
import com.alchitry.labs.parsers.lucidv2.types.ModuleInstance
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream

class LucidTester(vararg val files: String) {
    val project = ProjectContext()

    fun parseText(errorCollector: ErrorCollector): List<SourceContext> {
        return files.map {
            val parser = LucidParser(
                CommonTokenStream(
                    LucidLexer(
                        CharStreams.fromString(it)
                    ).also { it.removeErrorListeners() })
            ).apply {
                (tokenStream.tokenSource as LucidLexer).addErrorListener(errorCollector)
                removeErrorListeners()
                addErrorListener(errorCollector)
            }

            parser.source()
        }
    }

    fun globalParse(
        errorCollector: ErrorCollector = ErrorCollector(),
        tree: List<SourceContext> = parseText(errorCollector)
    ) {
        assert(errorCollector.hasNoIssues)

        tree.forEach {
            val globalContext = LucidGlobalContext(project, errorCollector)
            globalContext.walk(it)

            assert(errorCollector.hasNoIssues)
        }
    }

    fun moduleTypeParse(
        errorCollector: ErrorCollector = ErrorCollector(),
        tree: List<SourceContext> = parseText(errorCollector)
    ): List<Module> {
        assert(errorCollector.hasNoIssues)

        return tree.mapNotNull {
            val moduleTypeContext = LucidModuleTypeContext(project, errorCollector)
            val module = moduleTypeContext.extract(it)

            assert(errorCollector.hasNoIssues)
            module
        }
    }

    /**
     * Performs a full parse on the file.
     * @param errorCollector if null, the function will automatically check for errors. If provided, you should check
     * for errors after calling this function.
     */
    fun fullParse(errorCollector: ErrorCollector? = null): ModuleInstance {
        val errors = errorCollector ?: ErrorCollector()
        val tree = parseText(errors)

        globalParse(errors, tree)
        val module = moduleTypeParse(errors, tree)

        val moduleInstance = ModuleInstance("top", project, null, module.first(), mapOf(), errors)

        moduleInstance.initialWalk()
        if (errorCollector == null) {
            assert(errors.hasNoIssues)
        }

        return moduleInstance
    }
}