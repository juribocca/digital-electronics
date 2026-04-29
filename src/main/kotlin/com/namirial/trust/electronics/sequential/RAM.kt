package com.namirial.trust.electronics.sequential

import com.namirial.trust.electronics.core.*

/**
 * Behavioral RAM — ByteArray-backed, scalable to 64KB.
 *
 * Same Signal-based interface as [GateRAM] but uses a byte array internally.
 * - [address]: n-bit address (LSB first), addresses 2^n words
 * - [dataIn]: 8-bit data input (LSB first)
 * - [writeEnable]: when high, the addressed word is written on [clock]
 * - [dataOut]: 8-bit data output, always reflects the addressed word
 *
 * @param addressBits number of address bits (e.g., 16 for 64KB)
 */
open class RAM @JvmOverloads constructor(
    override val address: List<Signal>,
    override val dataIn: List<Signal>,
    override val writeEnable: Signal,
    addressBits: Int = address.size
) : Memory {

    init {
        require(address.size == addressBits) { "Expected $addressBits address lines" }
        require(dataIn.size == 8) { "RAM requires 8 data lines" }
    }

    private val memory = ByteArray(1 shl addressBits)

    private fun addressValue(): Int = address.foldIndexed(0) { i, acc, s ->
        acc or (if (s.value) (1 shl i) else 0)
    }

    /** 8-bit output, always reflects the addressed word. */
    override val dataOut: List<Signal> = (0 until 8).map { bit ->
        Output { (memory[addressValue()].toInt() shr bit) and 1 == 1 }
    }

    override val q: Signal = dataOut[0]
    override val qNot: Signal = Output { !q.value }

    override fun clock() {
        if (writeEnable.value) {
            val data = dataIn.foldIndexed(0) { i, acc, s ->
                acc or (if (s.value) (1 shl i) else 0)
            }
            memory[addressValue()] = data.toByte()
        }
    }
}