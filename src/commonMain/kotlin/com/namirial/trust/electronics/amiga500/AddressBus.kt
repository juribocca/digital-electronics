package com.namirial.trust.electronics.amiga500

/**
 * Amiga 500 Address Bus with Gary-style address decoding.
 *
 * Memory map (24-bit, 16 MB space):
 * - $000000–$07FFFF: Chip RAM (512 KB)
 * - $BFD000–$BFDFFF: CIA-A (odd bytes)
 * - $BFE000–$BFEFFF: CIA-B (even bytes)
 * - $DFF000–$DFF1FF: Custom chip registers (Agnus, Denise, Paula)
 * - $FC0000–$FFFFFF: Kickstart ROM (256 KB)
 *
 * Gary is the address decode/bus controller gate array in the Amiga 500.
 */
class AddressBus {

    val chipRam = ByteArray(512 * 1024)       // 512 KB Chip RAM
    val kickstartRom = ByteArray(256 * 1024)  // 256 KB ROM

    var ciaA: CIA8520? = null
    var ciaB: CIA8520? = null
    var customRegisters: CustomRegisters? = null

    /**
     * OVL (Overlay) flag — controlled by CIA-A PRA bit 0.
     * When true: Kickstart ROM is mapped at $000000–$03FFFF (overlays Chip RAM).
     * When false: Chip RAM is visible at $000000 (normal operation).
     * At power-on/reset, OVL is high (ROM visible at 0). Kickstart clears it after
     * copying exception vectors to RAM.
     */
    val overlay: Boolean get() = (ciaA?.pra ?: 1) and 0x01 != 0

    private fun isOverlayRead(addr: Int): Boolean =
        overlay && addr < 0x040000

    fun readByte(addr: Int): Int {
        val a = addr and 0xFFFFFF
        return when {
            isOverlayRead(a) -> kickstartRom[a and 0x3FFFF].toInt() and 0xFF
            a < 0x080000 -> chipRam[a].toInt() and 0xFF
            a in 0xBFD000..0xBFDFFF -> ciaA?.read((a shr 8) and 0xF) ?: 0
            a in 0xBFE000..0xBFEFFF -> ciaB?.read((a shr 8) and 0xF) ?: 0
            a in 0xDFF000..0xDFF1FF -> customRegisters?.readByte(a and 0x1FF) ?: 0
            a >= 0xFC0000 -> kickstartRom[(a - 0xFC0000) and 0x3FFFF].toInt() and 0xFF
            else -> 0xFF
        }
    }

    fun writeByte(addr: Int, value: Int) {
        val a = addr and 0xFFFFFF
        when {
            a < 0x080000 -> chipRam[a] = value.toByte() // writes always go to RAM (even with overlay)
            a in 0xBFD000..0xBFDFFF -> ciaA?.write((a shr 8) and 0xF, value and 0xFF)
            a in 0xBFE000..0xBFEFFF -> ciaB?.write((a shr 8) and 0xF, value and 0xFF)
            a in 0xDFF000..0xDFF1FF -> customRegisters?.writeByte(a and 0x1FF, value and 0xFF)
        }
    }

    fun readWord(addr: Int): Int {
        val a = addr and 0xFFFFFE
        return when {
            isOverlayRead(a) -> {
                val off = a and 0x3FFFF
                ((kickstartRom[off].toInt() and 0xFF) shl 8) or (kickstartRom[off + 1].toInt() and 0xFF)
            }
            a < 0x080000 ->
                ((chipRam[a].toInt() and 0xFF) shl 8) or (chipRam[a + 1].toInt() and 0xFF)
            a in 0xDFF000..0xDFF1FF ->
                customRegisters?.readWord(a and 0x1FF) ?: 0
            a >= 0xFC0000 -> {
                val off = (a - 0xFC0000) and 0x3FFFF
                ((kickstartRom[off].toInt() and 0xFF) shl 8) or (kickstartRom[off + 1].toInt() and 0xFF)
            }
            else -> (readByte(a) shl 8) or readByte(a + 1)
        }
    }

    fun writeWord(addr: Int, value: Int) {
        val a = addr and 0xFFFFFE
        when {
            a < 0x080000 -> {
                chipRam[a] = (value shr 8).toByte()
                chipRam[a + 1] = value.toByte()
            }
            a in 0xDFF000..0xDFF1FF ->
                customRegisters?.writeWord(a and 0x1FF, value and 0xFFFF)
            else -> { writeByte(a, value shr 8); writeByte(a + 1, value) }
        }
    }

    fun readLong(addr: Int): Int =
        (readWord(addr) shl 16) or (readWord(addr + 2) and 0xFFFF)

    fun writeLong(addr: Int, value: Int) {
        writeWord(addr, value ushr 16)
        writeWord(addr + 2, value and 0xFFFF)
    }

    fun loadKickstart(data: ByteArray) {
        data.copyInto(kickstartRom, 0, 0, minOf(data.size, kickstartRom.size))
    }
}
