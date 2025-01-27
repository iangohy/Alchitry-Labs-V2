package com.alchitry.labs2.parsers.lucidv2.values

import java.math.BigInteger
import java.util.*
import kotlin.experimental.and
import kotlin.experimental.or
import kotlin.math.ceil
import kotlin.math.pow

sealed class SimpleValue(
    override val constant: Boolean,
    open val signed: Boolean
) : Value() {
    abstract val size: Int
    abstract val msb: Bit
    abstract val lsb: Bit
    abstract val bits: List<Bit>
    fun asBitListValue() = if (this is BitListValue) this else BitListValue(bits, constant, signed)

    /**
     * Converts this value into a [BigInteger] or returns null if this isn't possible.
     * @param signed sets the signedness of the resulting [BigInteger]
     */
    fun toBigInt(signed: Boolean = this.signed): BigInteger? {
        if (!isNumber())
            return null
        val bytes =
            ByteArray(ceil((size + if (signed) 0 else 1).toDouble() / 8.0).toInt()) // if not signed need extra 0 sign bit
        if (signed && msb == Bit.B1) // sign extension
            Arrays.fill(bytes, 255.toByte()) else Arrays.fill(bytes, 0.toByte())
        repeat(size) { i ->
            val idx: Int = i / 8
            bytes[bytes.size - 1 - idx] = if (bits[i] == Bit.B1)
                bytes[bytes.size - 1 - idx] or (1 shl i % 8).toByte()
            else
                bytes[bytes.size - 1 - idx] and (1 shl i % 8).inv().toByte()
        }
        return BigInteger(bytes)
    }

    operator fun not(): BitValue {
        return BitValue(isTrue().bit.not(), constant, false)
    }

    override fun isTrue(): BitValue {
        var hasX = false
        bits.forEach {
            if (it == Bit.B1)
                return BitValue(Bit.B1, constant, false)
            else if (it == Bit.Bx || it == Bit.Bz)
                hasX = true
        }
        return BitValue(if (hasX) Bit.Bx else Bit.B0, constant, false)
    }

    /** returns true if the value is made up of only 1s and 0s */
    override fun isNumber(): Boolean = bits.none { !it.isNumber() }

    fun isNegative(): Boolean = signed && msb == Bit.B1

    infix fun isLessThan(other: SimpleValue): BitValue {
        val longest = size.coerceAtLeast(other.size)
        val signedOp = signed && other.signed
        val constantOp = constant && other.constant
        val op1 = asBitListValue().setSign(signedOp).resize(longest)
        val op2 = other.asBitListValue().setSign(signedOp).resize(longest)

        val neg1 = signedOp && isNegative()
        val neg2 = signedOp && other.isNegative()

        if (neg1 && !neg2) // negative < positive
            return Bit.B1.toBitValue(constantOp)

        if (!neg1 && neg2) // positive !< negative
            return Bit.B0.toBitValue(constantOp)

        // negative to negative or positive to positive can be directly compared
        for (i in op1.indices.reversed()) {
            if (!op1[i].isNumber() || !op2[i].isNumber())
                return Bit.Bx.toBitValue(constantOp)

            if (op1[i] == Bit.B1 && op2[i] == Bit.B0)
                return Bit.B0.toBitValue(constantOp)

            if (op1[i] == Bit.B0 && op2[i] == Bit.B1)
                return Bit.B1.toBitValue(constantOp)
        }
        return Bit.B0.toBitValue(constantOp)
    }

    infix fun isGreaterThan(other: SimpleValue): BitValue {
        return other isLessThan this
    }

    infix fun isLessOrEqualTo(other: SimpleValue): BitValue {
        return (isLessThan(other) or isEqualTo(other))
    }

    infix fun isGreaterOrEqualTo(other: SimpleValue): BitValue {
        return (isGreaterThan(other) or isEqualTo(other))
    }

    override infix fun and(other: Value): Value {
        require(other is SimpleValue) {"And can only be performed with another SimpleValue!"}
        return and(other)
    }

    override infix fun or(other: Value): Value {
        require(other is SimpleValue) {"Or can only be performed with another SimpleValue!"}
        return or(other)
    }

    override infix fun xor(other: Value): Value {
        require(other is SimpleValue) {"Xor can only be performed with another SimpleValue!"}
        return xor(other)
    }

    override fun resizeToMatch(newWidth: SignalWidth): Value = when(newWidth) {
        is ArrayWidth -> error("Cannot resize SimpleValue to fit ArrayValue!")
        is BitListWidth -> asBitListValue().resize(newWidth.size)
        BitWidth -> BitValue(lsb, constant, signed)
        is StructWidth -> error("Cannot resize SimpleValue to fit a StructValue!")
        is UndefinedWidth -> UndefinedValue(constant)
    }

    private inline fun doOp(b: SimpleValue, crossinline op: (Bit, Bit) -> Bit): SimpleValue {
        val size = size.coerceAtLeast(b.size)
        val signedOp = signed && b.signed
        val constant = constant && b.constant
        val op1 = asBitListValue().resize(size, signedOp)
        val op2 = b.asBitListValue().resize(size, signedOp)
        return BitListValue(size, constant, signedOp) { i ->
            op(op1[i], op2[i])
        }
    }

    infix fun or(b: SimpleValue): SimpleValue {
        return doOp(b) { b1, b2 -> b1 or b2 }
    }

    infix fun and(b: SimpleValue): SimpleValue {
        return doOp(b) { b1, b2 -> b1 and b2 }
    }

    infix fun xor(b: SimpleValue): SimpleValue {
        return doOp(b) { b1, b2 -> b1 xor b2 }
    }

    infix fun nor(b: SimpleValue): SimpleValue {
        return doOp(b) { b1, b2 -> b1 nor b2 }
    }

    infix fun nand(b: SimpleValue): SimpleValue {
        return doOp(b) { b1, b2 -> b1 nand b2 }
    }

    infix fun xnor(b: SimpleValue): SimpleValue {
        return doOp(b) { b1, b2 -> b1 xnor b2 }
    }

    infix fun shl(n: Int): BitListValue {
        val newBits = mutableListOf<Bit>()
        repeat(n) { newBits.add(Bit.B0) }
        newBits.addAll(bits)
        return BitListValue(newBits, constant, signed)
    }

    infix fun ushl(n: Int): BitListValue {
        val newBits = mutableListOf<Bit>()
        repeat(n) { newBits.add(Bit.B0) }
        newBits.addAll(bits)
        return BitListValue(newBits, constant, false)
    }

    infix fun shr(n: Int): BitListValue {
        val newBits = mutableListOf<Bit>()
        newBits.addAll(bits.subList(n, size))
        val signBit = if (signed) msb else Bit.B0
        repeat(n) { newBits.add(signBit) }
        return BitListValue(newBits, constant, signed)
    }

    infix fun ushr(n: Int): BitListValue {
        val newBits = mutableListOf<Bit>()
        newBits.addAll(bits.subList(n, size))
        repeat(n) { newBits.add(Bit.B0) }
        return BitListValue(newBits, constant, false)
    }

    fun asVerilog(): String? {
        if (!width.isDefined())
            return null
        val sb = StringBuilder()
        sb.append(width.bitCount)
        sb.append("'")
        if (signed)
            sb.append("s")
        if (isNumber()) {
            sb.append("h")
            sb.append(toBigInt()!!.toString(16))
        } else {
            sb.append("b")
            bits.reversed().forEach {
                sb.append(it.char)
            }
        }
        return sb.toString()
    }

    override fun toString() = buildString {
        if (constant)
            append("const ")
        if (signed)
            append("signed ")
        append('{')
        for (i in bits.indices.reversed()) {
            val bv = bits[i]
            append(bv.char)
        }
        append('}')

        if (isNumber()) {
            append(" : ")
            append(toBigInt().toString())
        }

    }

    fun isPowerOf2() = isNumber() && bits.count { it == Bit.B1 } == 1

    /**
     * Returns the minimum number of bits needed to represent this value
     */
    fun minBits(): Int {
        if (isNegative()) {
            for (i in bits.indices.reversed()) {
                if (bits[i] != Bit.B1)
                    return i + 2
            }
        } else {
            for (i in bits.indices.reversed()) {
                if (bits[i] != Bit.B0)
                    return i + 1 + if (signed) 1 else 0
            }
        }
        return 1
    }

    fun maxValue(): Double =
        if (signed) {
            2.0.pow(size - 1) - 1
        } else {
            2.0.pow(size) - 1
        }

    fun minValue(): Double =
        if (signed) {
            -(2.0.pow(size - 1))
        } else {
            0.0
        }
}