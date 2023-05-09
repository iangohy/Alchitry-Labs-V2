import com.alchitry.labs.parsers.lucidv2.signals.SignalSelector
import com.alchitry.labs.parsers.lucidv2.values.ArrayValue
import com.alchitry.labs.parsers.lucidv2.values.Bit
import com.alchitry.labs.parsers.lucidv2.values.BitListValue
import com.alchitry.labs.parsers.lucidv2.values.BitValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SignalWriteTests {
    @Test
    fun singleBitWriteTest() {
        val signal = BitListValue("0000", 2, 4, true, false)
        val newSig = signal.write(listOf(SignalSelector.Bits(1..1)), BitValue(Bit.B1, true, false))
        assertEquals(BitListValue("0010", 2, 4, true, false), newSig)
    }

    @Test
    fun multiBitWriteTest() {
        val signal = BitListValue("0000", 2, 4, true, false)
        val newSig = signal.write(listOf(SignalSelector.Bits(1..2)), BitListValue("11", 2, 2, true, false))
        assertEquals(BitListValue("0110", 2, 4, true, false), newSig)
    }

    @Test
    fun arrayWriteTest() {
        val baseValue = BitListValue("0000", 2, 4, true, false)
        val arrayValue = ArrayValue(listOf(baseValue, baseValue, baseValue, baseValue))
        val newValue = BitListValue("0110", 2, 4, true, false)
        val newArray = arrayValue.write(listOf(SignalSelector.Bits(1..1)), newValue)
        assertEquals(ArrayValue(listOf(baseValue, newValue, baseValue, baseValue)), newArray)
    }
}