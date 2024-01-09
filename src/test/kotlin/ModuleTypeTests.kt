import com.alchitry.labs.parsers.lucidv2.parsers.toSourceFile
import com.alchitry.labs.parsers.lucidv2.types.Module
import com.alchitry.labs.parsers.lucidv2.types.Parameter
import com.alchitry.labs.parsers.lucidv2.types.SignalDirection
import com.alchitry.labs.parsers.lucidv2.types.ports.Port
import com.alchitry.labs.parsers.lucidv2.values.BitListValue
import com.alchitry.labs.parsers.lucidv2.values.BitListWidth
import com.alchitry.labs.parsers.lucidv2.values.BitWidth
import helpers.LucidTester
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ModuleTypeTests {

    @Test
    fun basicModuleTest() = runBlocking {
        val test = LucidTester(
            """
                module myModule #(
                    CLK_FREQ ~ 100000000 : CLK_FREQ > 0,
                    MAX_CT = 100,
                    NEG_TEST = -100 : NEG_TEST < ${"\$"}signed(0)
                )(
                    input clk,
                    signed input rst,
                    output count[8],
                    inout a
                ) {
                }
            """.trimIndent().toSourceFile()
        )

        val tree = test.parseText()
        val module = test.moduleTypeParse(tree)

        assertEquals(
            Module(
                "myModule",
                mapOf(
                    "CLK_FREQ" to Parameter(
                        "CLK_FREQ",
                        BitListValue(100000000.toBigInteger(), true),
                        true,
                        tree.first().second.module(0)?.paramList()?.paramDec(0)?.paramConstraint()?.expr()
                    ),
                    "MAX_CT" to Parameter(
                        "MAX_CT",
                        BitListValue(100.toBigInteger(), true),
                        false,
                        null
                    ),
                    "NEG_TEST" to Parameter(
                        "NEG_TEST",
                        BitListValue((-100).toBigInteger(), true),
                        false,
                        tree.first().second.module(0)?.paramList()?.paramDec(2)?.paramConstraint()?.expr()
                    )
                ),
                mapOf(
                    "clk" to Port("clk", SignalDirection.Read, BitWidth, false),
                    "rst" to Port("rst", SignalDirection.Read, BitWidth, true),
                    "count" to Port("count", SignalDirection.Write, BitListWidth(8), false),
                    "a" to Port("a", SignalDirection.Both, BitWidth, false)
                ),
                tree.first().second.module(0)!!,
                test.files.first()
            ),
            module.first()
        )
    }


}