package com.namirial.trust.electronics.sequential

import com.namirial.trust.electronics.core.Input
import com.namirial.trust.electronics.core.Signal
import kotlin.test.*

/** Shared test logic for both RAM implementations. */
abstract class AbstractRAMTest {
    abstract val address: List<Input>
    abstract val dataIn: List<Input>
    abstract val writeEnable: Input
    abstract val dataOut: List<Signal>
    abstract fun clock()

    private fun setAddress(addr: Int) {
        address.forEachIndexed { i, input -> input.value = (addr shr i) and 1 == 1 }
    }

    private fun setData(data: Int) {
        dataIn.forEachIndexed { i, input -> input.value = (data shr i) and 1 == 1 }
    }

    private fun readData(): Int = dataOut.foldIndexed(0) { i, acc, s ->
        acc or (if (s.value) (1 shl i) else 0)
    }

    @Test fun `memory starts at zero`() {
        setAddress(0)
        assertEquals(0, readData())
    }

    @Test fun `write and read back`() {
        writeEnable.value = true
        setAddress(0); setData(0xAB); clock()
        writeEnable.value = false
        setAddress(0)
        assertEquals(0xAB, readData())
    }

    @Test fun `different addresses are independent`() {
        writeEnable.value = true
        setAddress(0); setData(0x11); clock()
        setAddress(1); setData(0x22); clock()
        writeEnable.value = false

        setAddress(0); assertEquals(0x11, readData())
        setAddress(1); assertEquals(0x22, readData())
    }

    @Test fun `write disabled does not modify memory`() {
        writeEnable.value = true
        setAddress(0); setData(0xFF); clock()
        writeEnable.value = false
        setData(0x00); clock()
        setAddress(0); assertEquals(0xFF, readData())
    }

    @Test fun `overwrite existing value`() {
        writeEnable.value = true
        setAddress(0); setData(0x11); clock()
        setData(0x22); clock()
        writeEnable.value = false
        assertEquals(0x22, readData())
    }
}

class GateRAMTest : AbstractRAMTest() {
    override val address = List(4) { Input() }
    override val dataIn = List(8) { Input() }
    override val writeEnable = Input()
    private val ram = GateRAM(address, dataIn, writeEnable)
    override val dataOut: List<Signal> = ram.dataOut
    override fun clock() = ram.clock()

    @Test fun `write to all 16 addresses`() {
        writeEnable.value = true
        for (addr in 0 until 16) {
            address.forEachIndexed { i, input -> input.value = (addr shr i) and 1 == 1 }
            dataIn.forEachIndexed { i, input -> input.value = (addr shr i) and 1 == 1 }
            clock()
        }
        writeEnable.value = false
        for (addr in 0 until 16) {
            address.forEachIndexed { i, input -> input.value = (addr shr i) and 1 == 1 }
            val read = dataOut.foldIndexed(0) { i, acc, s -> acc or (if (s.value) (1 shl i) else 0) }
            assertEquals(addr, read, "address $addr")
        }
    }
}

class RAMTest : AbstractRAMTest() {
    override val address = List(16) { Input() }
    override val dataIn = List(8) { Input() }
    override val writeEnable = Input()
    private val ram = RAM(address, dataIn, writeEnable, addressBits = 16)
    override val dataOut: List<Signal> = ram.dataOut
    override fun clock() = ram.clock()

    @Test fun `write to high address`() {
        writeEnable.value = true
        val addr = 0xFFFF
        address.forEachIndexed { i, input -> input.value = (addr shr i) and 1 == 1 }
        dataIn.forEachIndexed { i, input -> input.value = (0xAA shr i) and 1 == 1 }
        clock()
        writeEnable.value = false
        val read = dataOut.foldIndexed(0) { i, acc, s -> acc or (if (s.value) (1 shl i) else 0) }
        assertEquals(0xAA, read)
    }
}
