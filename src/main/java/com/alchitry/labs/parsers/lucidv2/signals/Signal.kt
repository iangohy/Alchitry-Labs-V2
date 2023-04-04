package com.alchitry.labs.parsers.lucidv2.signals

import com.alchitry.labs.parsers.lucidv2.values.Value

enum class SignalDirection {
    Read,
    Write,
    Both;

    val canRead: Boolean get() = this != Write
    val canWrite: Boolean get() = this != Read
}

data class Signal(
    val fullName: String, // includes namespace or module name
    val type: SignalType,
    val direction: SignalDirection,
    val value: Value
) {
    val name: String = fullName.split(".").last()
}