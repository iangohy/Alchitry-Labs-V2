import com.alchitry.labs.com.alchitry.labs.parsers.lucidv2.ErrorCollector
import com.alchitry.labs.parsers.lucidv2.parsers.BitSelectionParser
import com.alchitry.labs.parsers.lucidv2.parsers.ExprParser
import com.alchitry.labs.parsers.lucidv2.parsers.SignalParser
import com.alchitry.labs.parsers.lucidv2.resolvers.LucidParseContext
import com.alchitry.labs.parsers.lucidv2.grammar.LucidLexer
import com.alchitry.labs.parsers.lucidv2.grammar.LucidParser
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream

class LucidTester (text: String) :
    LucidParser(
        CommonTokenStream(
            LucidLexer(
                CharStreams.fromString(text)
            ).also { it.removeErrorListeners() })
    ) {

    val errorCollector = ErrorCollector()
    val parseContext: LucidParseContext

    init {
        (tokenStream.tokenSource as LucidLexer).addErrorListener(errorCollector)
        removeErrorListeners()
        addErrorListener(errorCollector)

        parseContext = LucidParseContext(errorCollector)
        parseContext.addToParser(this)
    }

    val hasNoIssues: Boolean get() = hasNoErrors && hasNoWarnings && hasNoSyntaxIssues

    val hasNoErrors: Boolean get() {
        errorCollector.errors.forEach {
            println(it)
        }
        return errorCollector.errors.isEmpty()
    }
    val hasNoWarnings: Boolean get() {
        errorCollector.warnings.forEach {
            println(it)
        }
        return errorCollector.warnings.isEmpty()
    }
    val hasNoSyntaxIssues: Boolean get() {
        errorCollector.syntaxIssues.forEach {
            println(it)
        }
        return errorCollector.syntaxIssues.isEmpty()
    }
    val hasErrors: Boolean get() = errorCollector.errors.isNotEmpty()
    val hasWarnings: Boolean get() = errorCollector.warnings.isNotEmpty()
    val hasSyntaxIssues: Boolean get() = errorCollector.syntaxIssues.isNotEmpty()
}