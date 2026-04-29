package com.namirial.trust.electronics.sequential

import com.namirial.trust.electronics.core.*

/**
 * RAM with typed address and data inputs.
 *
 * - [typedAddress]: [InputAddress] (multiple of 8 bits)
 * - [typedDataIn]: [InputByte] (exactly 8 bits)
 * - [dataOut] wrapped as [OutputByte] via [typedDataOut]
 */
class ByteAddressedRAM(
    val typedAddress: InputAddress,
    val typedDataIn: InputByte,
    writeEnable: Signal
) : RAM(typedAddress.bits, typedDataIn.bits, writeEnable) {

    constructor(): this(InputAddress(4), InputByte(), Input())
    val typedDataOut: OutputByte get() = OutputByte(dataOut)
}
