package com.namirial.trust.electronics.gate

import com.namirial.trust.electronics.core.*

interface UnaryGate {
    val input: Signal
    val output: Output
}

interface BinaryGate {
    val a: Signal
    val b: Signal
    val output: Output
}

class NotGate(override val input: Signal) : UnaryGate {
    override val output = Output { !input.value }
}

class AndGate(override val a: Signal, override val b: Signal) : BinaryGate {
    override val output = Output { a.value && b.value }
}

class OrGate(override val a: Signal, override val b: Signal) : BinaryGate {
    override val output = Output { a.value || b.value }
}

class NandGate(override val a: Signal, override val b: Signal) : BinaryGate {
    override val output = Output { !(a.value && b.value) }
}

class NorGate(override val a: Signal, override val b: Signal) : BinaryGate {
    override val output = Output { !(a.value || b.value) }
}

class XorGate(override val a: Signal, override val b: Signal) : BinaryGate {
    override val output = Output { a.value xor b.value }
}

class XnorGate(override val a: Signal, override val b: Signal) : BinaryGate {
    override val output = Output { !(a.value xor b.value) }
}
