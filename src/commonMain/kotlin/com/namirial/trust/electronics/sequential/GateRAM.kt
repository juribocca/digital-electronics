package com.namirial.trust.electronics.sequential

import com.namirial.trust.electronics.core.*
import com.namirial.trust.electronics.gate.*
import com.namirial.trust.electronics.combinational.*

/**
 * 16×8 gate-level RAM.
 *
 * 16 words of 8 bits each, fully wired with gates, registers, decoder, and mux trees.
 * - [address]: 4-bit address (list of 4 Signals, LSB first)
 * - [dataIn]: 8-bit data input (list of 8 Signals, LSB first)
 * - [writeEnable]: when high, the addressed word is written on [clock]
 * - [dataOut]: 8-bit data output, always reflects the addressed word
 */
class GateRAM(
    override val address: List<Signal>,
    override val dataIn: List<Signal>,
    override val writeEnable: Signal
) : Memory {

    init {
        require(address.size == 4) { "GateRAM requires 4 address lines" }
        require(dataIn.size == 8) { "GateRAM requires 8 data lines" }
    }

    // Decode address into 16 select lines
    private val decLow = Decoder2to4(address[0], address[1])
    private val decHigh = Decoder2to4(address[2], address[3])
    private val selectLines: List<Signal> = buildList {
        val low = listOf(decLow.output0, decLow.output1, decLow.output2, decLow.output3)
        val high = listOf(decHigh.output0, decHigh.output1, decHigh.output2, decHigh.output3)
        for (h in high) for (l in low) add(AndGate(h, l).output)
    }

    // 16 registers, each enabled by selectLine AND writeEnable
    private val registers: List<Register> = selectLines.map { sel ->
        Register(dataIn, AndGate(sel, writeEnable).output)
    }

    // Mux tree per bit: 16-to-1 built from 2-to-1 muxes
    private fun muxTree(inputs: List<Signal>, selBits: List<Signal>): Signal {
        var level = inputs
        for (sel in selBits) {
            level = level.chunked(2).map { (a, b) -> Multiplexer(a, b, sel).output }
        }
        return level.single()
    }

    /** 8-bit output, always reflects the addressed word. */
    override val dataOut: List<Signal> = (0 until 8).map { bit ->
        muxTree(registers.map { it.bit(bit) }, address)
    }

    override val q: Signal = dataOut[0]
    override val qNot: Signal = Output { !q.value }

    override fun clock() {
        registers.forEach { it.clock() }
    }
}
