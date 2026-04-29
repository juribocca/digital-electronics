package com.namirial.trust.electronics.combinational

import com.namirial.trust.electronics.core.*
import com.namirial.trust.electronics.gate.*

/**
 * 8-bit ALU (Arithmetic Logic Unit).
 *
 * Performs addition or subtraction using a chain of [FullAdder]s.
 * - [a], [b]: 8-bit inputs (LSB first)
 * - [subtract]: when high, computes A - B (via two's complement: A + NOT(B) + 1)
 * - [result]: 8-bit output (LSB first)
 * - [carryOut]: carry/borrow out of the MSB
 * - [zero]: high when result is all zeros
 */
class ALU(
    val a: List<Signal>,
    val b: List<Signal>,
    val subtract: Signal
) {
    init {
        require(a.size == 8 && b.size == 8)
    }

    // XOR each B bit with subtract flag: when subtract=1, this inverts B
    private val bXored = b.map { XorGate(it, subtract).output as Signal }

    // Chain of 8 FullAdders; carry-in of first adder = subtract (adds the +1 for two's complement)
    private val adders: List<FullAdder> = buildList {
        var carry: Signal = subtract
        for (i in 0 until 8) {
            val fa = FullAdder(a[i], bXored[i], carry)
            add(fa)
            carry = fa.carryOut
        }
    }

    val result: List<Signal> = adders.map { it.sum }
    val carryOut: Signal = adders.last().carryOut

    val zero: Signal = Output {
        result.none { it.value }
    }
}
