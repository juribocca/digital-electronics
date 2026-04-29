package com.namirial.trust.electronics.combinational

import com.namirial.trust.electronics.core.*
import com.namirial.trust.electronics.gate.*

class HalfAdder(a: Signal, b: Signal) {
    private val xor = XorGate(a, b)
    private val and = AndGate(a, b)

    val sum: Signal = xor.output
    val carryOut: Signal = and.output
}

class FullAdder(a: Signal, b: Signal, carryIn: Signal) {
    private val abXor = XorGate(a, b)
    private val abAnd = AndGate(a, b)
    private val sumGate = XorGate(abXor.output, carryIn)
    private val carryAnd = AndGate(abXor.output, carryIn)
    private val carryOr = OrGate(abAnd.output, carryAnd.output)

    val sum: Signal = sumGate.output
    val carryOut: Signal = carryOr.output
}

class Multiplexer(i0: Signal, i1: Signal, sel: Signal) {
    private val notSel = NotGate(sel)
    private val and0 = AndGate(i0, notSel.output)
    private val and1 = AndGate(i1, sel)
    private val or = OrGate(and0.output, and1.output)

    val output: Signal = or.output
}

class Demultiplexer(input: Signal, sel: Signal) {
    private val notSel = NotGate(sel)
    private val and0 = AndGate(input, notSel.output)
    private val and1 = AndGate(input, sel)

    val output0: Signal = and0.output
    val output1: Signal = and1.output
}

class Encoder4to2(i0: Signal, i1: Signal, i2: Signal, i3: Signal) {
    private val or0 = OrGate(i1, i3)
    private val or1 = OrGate(i2, i3)

    val output0: Signal = or0.output
    val output1: Signal = or1.output
}

class PriorityEncoder4to2(i0: Signal, i1: Signal, i2: Signal, i3: Signal) {
    private val or1 = OrGate(i2, i3)
    private val notI2 = NotGate(i2)
    private val i1AndNotI2 = AndGate(i1, notI2.output)
    private val or0 = OrGate(i3, i1AndNotI2.output)
    private val or01 = OrGate(i0, i1)
    private val or23 = OrGate(i2, i3)
    private val orValid = OrGate(or01.output, or23.output)

    val output0: Signal = or0.output
    val output1: Signal = or1.output
    val valid: Signal = orValid.output
}

class Decoder2to4(a: Signal, b: Signal) {
    private val notA = NotGate(a)
    private val notB = NotGate(b)

    val output0: Signal = AndGate(notA.output, notB.output).output
    val output1: Signal = AndGate(a, notB.output).output
    val output2: Signal = AndGate(notA.output, b).output
    val output3: Signal = AndGate(a, b).output
}
