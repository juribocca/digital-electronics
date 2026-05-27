package com.namirial.trust.electronics.amiga500

/**
 * Custom chip register space ($DFF000–$DFF1FF).
 *
 * Shared between Agnus, Denise, and Paula. Each chip owns specific register offsets.
 */
class CustomRegisters(
    val agnus: Agnus,
    val denise: Denise,
    val paula: Paula,
    var floppy: FloppyController? = null,
    var input: InputController? = null
) {
    fun readByte(offset: Int): Int {
        val word = readWord(offset and 0x1FE)
        return if (offset and 1 == 0) word shr 8 else word and 0xFF
    }

    fun readWord(offset: Int): Int = when {
        isInputReg(offset) -> readInputReg(offset)
        isDiskReg(offset) -> floppy?.readReg(offset) ?: 0
        isPaulaReg(offset) -> paula.readReg(offset)
        isDeniseReg(offset) -> denise.readReg(offset)
        else -> agnus.readReg(offset)
    }

    fun writeByte(offset: Int, value: Int) {
        writeWord(offset and 0x1FE, value and 0xFF)
    }

    fun writeWord(offset: Int, value: Int) {
        when {
            isDiskReg(offset) -> floppy?.writeReg(offset, value)
            isPaulaReg(offset) -> paula.writeReg(offset, value)
            isDeniseReg(offset) -> denise.writeReg(offset, value)
            else -> agnus.writeReg(offset, value)
        }
    }

    private fun isDiskReg(offset: Int): Boolean = when (offset) {
        0x010,            // ADKCONR
        0x01A,            // DSKBYTR
        0x020, 0x022,     // DSKPTH, DSKPTL
        0x024,            // DSKLEN
        0x07E,            // DSKSYNC
        0x09E             // ADKCON
        -> true
        else -> false
    }

    private fun isPaulaReg(offset: Int): Boolean = when (offset) {
        in 0x09A..0x09D,  // INTENA, INTREQ (not 0x09E which is ADKCON)
        in 0x0A0..0x0DF,  // Audio channels
        in 0x01C..0x01F   // INTENAR, INTREQR
        -> true
        else -> false
    }

    private fun isDeniseReg(offset: Int): Boolean = when (offset) {
        in 0x100..0x1BF,  // Color registers, BPLCON, sprite data
        in 0x180..0x1BF   // COLOR00–COLOR31
        -> true
        else -> false
    }

    private fun isInputReg(offset: Int): Boolean = offset == 0x00A || offset == 0x00C

    private fun readInputReg(offset: Int): Int = when (offset) {
        0x00A -> input?.joy0dat ?: 0  // JOY0DAT
        0x00C -> input?.joy1dat ?: 0  // JOY1DAT
        else -> 0
    }
}
