package com.namirial.trust.electronics.amiga500

/**
 * Custom chip register space ($DFF000–$DFF1FF).
 *
 * Shared between Agnus, Denise, and Paula. Each chip owns specific register offsets.
 */
class CustomRegisters(
    val agnus: Agnus,
    val denise: Denise,
    val paula: Paula
) {
    fun readByte(offset: Int): Int {
        val word = readWord(offset and 0x1FE)
        return if (offset and 1 == 0) word shr 8 else word and 0xFF
    }

    fun readWord(offset: Int): Int = when {
        isPaulaReg(offset) -> paula.readReg(offset)
        isDeniseReg(offset) -> denise.readReg(offset)
        else -> agnus.readReg(offset)
    }

    fun writeByte(offset: Int, value: Int) {
        // Custom registers are word-addressed; byte writes are unusual but handled
        writeWord(offset and 0x1FE, value and 0xFF)
    }

    fun writeWord(offset: Int, value: Int) {
        when {
            isPaulaReg(offset) -> paula.writeReg(offset, value)
            isDeniseReg(offset) -> denise.writeReg(offset, value)
            else -> agnus.writeReg(offset, value)
        }
    }

    private fun isPaulaReg(offset: Int): Boolean = when (offset) {
        in 0x09A..0x09F,  // INTENA, INTREQ
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
}
