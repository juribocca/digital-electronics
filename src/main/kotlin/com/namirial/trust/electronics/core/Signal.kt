package com.namirial.trust.electronics.core

interface Signal {
    val value: Boolean
}

class Input(override var value: Boolean = false) : Signal

class Output(private val evaluate: () -> Boolean) : Signal {
    override val value: Boolean get() = evaluate()
}

class LatchSignal(override var value: Boolean = false) : Signal

@JvmInline
value class InputByte(val bits: List<Input>) {
    init { require(bits.size == 8) { "InputByte requires exactly 8 bits" } }
    constructor() : this(List(8) { Input() })
}

fun Byte.applyTo(inputByte: InputByte) {
    inputByte.bits.forEachIndexed { i, input -> input.value = (toInt() shr i) and 1 == 1 }
}

@JvmInline
value class InputAddress(val bits: List<Input>) {
    init { require(bits.isNotEmpty() && bits.size % 8 == 0) { "InputAddress size must be a positive multiple of 8" } }
    constructor(bytes: Int) : this(List(bytes * 8) { Input() })
}

fun List<Input>.toInputByte(): InputByte = InputByte(this)

@JvmInline
value class OutputByte(val bits: List<Signal>) {
    init { require(bits.size == 8) { "OutputByte requires exactly 8 bits" } }

    fun toByte(): Byte = bits.foldIndexed(0) { i, acc, s ->
        acc or (if (s.value) (1 shl i) else 0)
    }.toByte()
}

fun List<Signal>.toOutputByte(): OutputByte = OutputByte(this)