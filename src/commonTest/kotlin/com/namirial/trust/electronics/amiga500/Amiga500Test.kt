package com.namirial.trust.electronics.amiga500

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Example usage of the Amiga500 simulation.
 */
class Amiga500Test {

    /**
     * Basic example: load a small 68000 program into ROM, boot, and verify execution.
     * The program loads a value into D0, adds to it, and stores the result in Chip RAM.
     */
    @Test
    fun exampleBasicProgram() {
        val amiga = Amiga500()

        // Build a minimal "Kickstart ROM" — just enough to boot:
        // Address $FC0000: Initial SSP (stack pointer) = $080000 (top of Chip RAM)
        // Address $FC0004: Initial PC = $FC0008 (start of our code)
        // Address $FC0008: Our program starts here
        val rom = ByteArray(256 * 1024)

        // Write initial SSP at offset 0: $00080000
        rom.writeLong(0, 0x0007_FFF0)
        // Write initial PC at offset 4: $FC0008
        rom.writeLong(4, 0x00FC_0008)

        // Program at offset 8 ($FC0008):
        //   MOVEQ #42, D0        -> $7028
        //   MOVEQ #8, D1         -> $7208
        //   ADD.L D1, D0         -> $D081
        //   MOVE.L D0, $000100   -> $23C0 0000 0100
        //   STOP (we use illegal to halt) -> $4AFC (or just let it halt)
        var pc = 8
        pc = rom.writeWord(pc, 0x702A)       // MOVEQ #42, D0
        pc = rom.writeWord(pc, 0x7208)       // MOVEQ #8, D1
        pc = rom.writeWord(pc, 0xD081)       // ADD.L D1, D0
        pc = rom.writeWord(pc, 0x23C0)       // MOVE.L D0, (xxx).L
        pc = rom.writeWord(pc, 0x0000)       //   address high word
        pc = rom.writeWord(pc, 0x0100)       //   address low word = $000100
        pc = rom.writeWord(pc, 0xFFFF)       // Line-F — halts the CPU (illegal)

        amiga.loadKickstart(rom)

        // Run until halted
        amiga.cpu.run(100)

        // Verify: D0 should be 42 + 8 = 50
        assertEquals(50, amiga.cpu.d[0])

        // Verify: Chip RAM at $000100 should contain 50 (as a long)
        assertEquals(50, amiga.bus.readLong(0x000100))
    }

    /**
     * Example: set up a Copper list that writes to color registers.
     */
    @Test
    fun exampleCopperList() {
        val amiga = Amiga500()

        // First verify direct write to color register works
        amiga.bus.writeWord(0xDFF180, 0x0F00)
        assertEquals(0xF00, amiga.denise.color[0], "Direct write to COLOR00 failed")

        // Reset color
        amiga.denise.color[0] = 0

        // Write a Copper list into Chip RAM at address $1000:
        // MOVE $0F00 → COLOR00 ($180)  — set background to red
        // WAIT for end of frame ($FFFF $FFFE)
        var addr = 0x1000
        addr = amiga.bus.writeWordAt(addr, 0x0180) // register offset for COLOR00
        addr = amiga.bus.writeWordAt(addr, 0x0F00) // value: red ($F00)
        addr = amiga.bus.writeWordAt(addr, 0xFFFF) // WAIT end
        addr = amiga.bus.writeWordAt(addr, 0xFFFE)

        // Point Copper list 1 to $1000
        amiga.agnus.cop1lc = 0x1000
        amiga.agnus.coppc = 0x1000

        // Enable Copper DMA + master DMA
        amiga.agnus.dmacon = Agnus.DMA_MASTER or Agnus.DMA_COPPER

        // Run exactly 2 Copper cycles (fetch instruction word + fetch operand & execute)
        amiga.agnus.copperCycle(amiga.bus, amiga.paula)
        amiga.agnus.copperCycle(amiga.bus, amiga.paula)

        // Verify: COLOR00 should now be $F00 (red, 12-bit RGB)
        assertEquals(0xF00, amiga.denise.color[0])
    }

    /**
     * Example: CIA timer countdown.
     */
    @Test
    fun exampleCIATimer() {
        val amiga = Amiga500()

        // Set CIA-A Timer A to count down from 10
        amiga.ciaA.write(CIA8520.TALO, 10)  // low byte = 10
        amiga.ciaA.write(CIA8520.TAHI, 0)   // high byte = 0
        amiga.ciaA.write(CIA8520.CRA, 0x01) // start timer (continuous mode)

        // Tick 5 times
        repeat(5) { amiga.ciaA.tick() }
        assertEquals(5, amiga.ciaA.timerACounter)

        // Tick 5 more — should underflow and reload
        repeat(5) { amiga.ciaA.tick() }
        assertEquals(10, amiga.ciaA.timerACounter) // reloaded from latch

        // Interrupt flag should be set
        assertFalse(amiga.ciaA.icrData == 0)
    }

    // --- Helper extensions ---

    private fun ByteArray.writeLong(offset: Int, value: Int) {
        this[offset] = (value ushr 24).toByte()
        this[offset + 1] = (value ushr 16).toByte()
        this[offset + 2] = (value ushr 8).toByte()
        this[offset + 3] = value.toByte()
    }

    private fun ByteArray.writeWord(offset: Int, value: Int): Int {
        this[offset] = (value ushr 8).toByte()
        this[offset + 1] = value.toByte()
        return offset + 2
    }

    private fun AddressBus.writeWordAt(addr: Int, value: Int): Int {
        writeWord(addr, value)
        return addr + 2
    }
}
