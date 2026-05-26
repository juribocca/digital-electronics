package com.namirial.trust.electronics.amiga500

/**
 * Agnus (8370/8372) — Amiga DMA controller and address generator.
 *
 * Responsibilities:
 * - DMA scheduling (bitplane, sprite, audio, disk, Copper, Blitter)
 * - Copper coprocessor (display-synchronized instruction list)
 * - Blitter (area fill, line draw, block copy with logic ops)
 * - Beam counter (VHPOSR/VHPOSW)
 *
 * Key registers:
 * - DMACON/DMACONR ($096/$002): DMA enable control
 * - VPOSR/VHPOSR ($004/$006): Beam position
 * - COP1LCH/L ($080/$082): Copper list 1 pointer
 * - COP2LCH/L ($084/$086): Copper list 2 pointer
 * - COPJMP1/2 ($088/$08A): Copper jump strobe
 * - BLTxPTH/L, BLTCON0/1, BLTSIZE: Blitter registers
 * - BPLxPTH/L ($0E0–$0FE): Bitplane pointers
 * - DIWSTRT/DIWSTOP ($08E/$090): Display window
 * - DDFSTRT/DDFSTOP ($092/$094): Data fetch
 */
class Agnus {

    // --- DMA control ---
    var dmacon: Int = 0  // bit 15 = master enable, bits 0–9 = channel enables

    companion object {
        const val DMA_AUD0 = 0x01
        const val DMA_AUD1 = 0x02
        const val DMA_AUD2 = 0x04
        const val DMA_AUD3 = 0x08
        const val DMA_DISK = 0x10
        const val DMA_SPRITE = 0x20
        const val DMA_BLITTER = 0x40
        const val DMA_COPPER = 0x80
        const val DMA_BITPLANE = 0x100
        const val DMA_MASTER = 0x200
        const val DMA_BLITTER_PRIORITY = 0x400
    }

    fun dmaEnabled(channel: Int): Boolean =
        (dmacon and DMA_MASTER) != 0 && (dmacon and channel) != 0

    // --- Beam position ---
    var vpos: Int = 0    // Vertical position (0–312 PAL)
    var hpos: Int = 0    // Horizontal position (0–227 color clocks)

    // --- Copper ---
    var cop1lc: Int = 0  // Copper list 1 location
    var cop2lc: Int = 0  // Copper list 2 location
    var coppc: Int = 0   // Copper program counter
    private var copState = CopperState.FETCH_INST

    private enum class CopperState { FETCH_INST, FETCH_OPERAND, WAIT }

    private var copIR1: Int = 0  // First word of current instruction
    private var copIR2: Int = 0  // Second word

    // --- Blitter ---
    var bltcon0: Int = 0
    var bltcon1: Int = 0
    var bltsize: Int = 0
    var bltapt: Int = 0
    var bltbpt: Int = 0
    var bltcpt: Int = 0
    var bltdpt: Int = 0
    var bltamod: Int = 0
    var bltbmod: Int = 0
    var bltcmod: Int = 0
    var bltdmod: Int = 0
    var bltafwm: Int = 0xFFFF
    var bltalwm: Int = 0xFFFF
    var blitBusy: Boolean = false

    // --- Bitplane pointers ---
    val bplpt = IntArray(6)  // 6 bitplane pointers

    // --- Display window ---
    var diwstrt: Int = 0
    var diwstop: Int = 0
    var ddfstrt: Int = 0
    var ddfstop: Int = 0

    /** Read an Agnus register. */
    fun readReg(offset: Int): Int = when (offset) {
        0x002 -> dmacon  // DMACONR
        0x004 -> (vpos shr 8) and 0x01  // VPOSR (high bit of vpos)
        0x006 -> ((vpos and 0xFF) shl 8) or (hpos and 0xFF)  // VHPOSR
        else -> 0
    }

    /** Write an Agnus register. */
    fun writeReg(offset: Int, value: Int) {
        when (offset) {
            0x096 -> { // DMACON
                if (value and 0x8000 != 0) dmacon = dmacon or (value and 0x7FF)
                else dmacon = dmacon and (value and 0x7FF).inv()
            }
            0x080 -> cop1lc = (cop1lc and 0x0000FFFF) or ((value and 0x1F) shl 16) // COP1LCH
            0x082 -> cop1lc = (cop1lc and 0xFFFF0000.toInt()) or (value and 0xFFFE) // COP1LCL
            0x084 -> cop2lc = (cop2lc and 0x0000FFFF) or ((value and 0x1F) shl 16) // COP2LCH
            0x086 -> cop2lc = (cop2lc and 0xFFFF0000.toInt()) or (value and 0xFFFE) // COP2LCL
            0x088 -> { coppc = cop1lc; copState = CopperState.FETCH_INST } // COPJMP1
            0x08A -> { coppc = cop2lc; copState = CopperState.FETCH_INST } // COPJMP2
            0x08E -> diwstrt = value
            0x090 -> diwstop = value
            0x092 -> ddfstrt = value
            0x094 -> ddfstop = value
            // Blitter registers
            0x040 -> bltcon0 = value
            0x042 -> bltcon1 = value
            0x044 -> bltafwm = value
            0x046 -> bltalwm = value
            0x048 -> bltcpt = (bltcpt and 0xFFFF) or ((value and 0x1F) shl 16)
            0x04A -> bltcpt = (bltcpt and 0x1F0000) or (value and 0xFFFE)
            0x04C -> bltbpt = (bltbpt and 0xFFFF) or ((value and 0x1F) shl 16)
            0x04E -> bltbpt = (bltbpt and 0x1F0000) or (value and 0xFFFE)
            0x050 -> bltapt = (bltapt and 0xFFFF) or ((value and 0x1F) shl 16)
            0x052 -> bltapt = (bltapt and 0x1F0000) or (value and 0xFFFE)
            0x054 -> bltdpt = (bltdpt and 0xFFFF) or ((value and 0x1F) shl 16)
            0x056 -> bltdpt = (bltdpt and 0x1F0000) or (value and 0xFFFE)
            0x058 -> { bltsize = value; blitBusy = true } // BLTSIZE triggers blit
            0x060 -> bltcmod = value.toShort().toInt()
            0x062 -> bltbmod = value.toShort().toInt()
            0x064 -> bltamod = value.toShort().toInt()
            0x066 -> bltdmod = value.toShort().toInt()
            // Bitplane pointers
            in 0x0E0..0x0FE step 4 -> {
                val idx = (offset - 0x0E0) / 4
                if (idx < 6) bplpt[idx] = (bplpt[idx] and 0xFFFF) or ((value and 0x1F) shl 16)
            }
            in 0x0E2..0x0FE step 4 -> {
                val idx = (offset - 0x0E2) / 4
                if (idx < 6) bplpt[idx] = (bplpt[idx] and 0x1F0000) or (value and 0xFFFE)
            }
        }
    }

    /**
     * Execute one Copper cycle (called every other DMA slot when Copper DMA is enabled).
     */
    fun copperCycle(bus: AddressBus, paula: Paula) {
        if (!dmaEnabled(DMA_COPPER)) return
        when (copState) {
            CopperState.FETCH_INST -> {
                copIR1 = bus.readWord(coppc); coppc += 2
                copState = CopperState.FETCH_OPERAND
            }
            CopperState.FETCH_OPERAND -> {
                copIR2 = bus.readWord(coppc); coppc += 2
                executeCopperInstruction(bus, paula)
            }
            CopperState.WAIT -> {
                val vp = (copIR1 shr 8) and 0xFF
                val hp = copIR1 and 0xFE
                val ve = (copIR2 shr 8) and 0x7F
                val he = copIR2 and 0xFE
                if ((vpos and ve) >= (vp and ve) && (hpos and he) >= (hp and he)) {
                    copState = CopperState.FETCH_INST
                }
            }
        }
    }

    private fun executeCopperInstruction(bus: AddressBus, paula: Paula) {
        if (copIR1 and 1 == 0) {
            // MOVE: write copIR2 to register copIR1[8:1]
            val reg = copIR1 and 0x1FE
            bus.writeWord(0xDFF000 + reg, copIR2)
        } else {
            // WAIT (or SKIP if copIR2 bit 0 set)
            if (copIR2 and 1 == 0) {
                copState = CopperState.WAIT
            } else {
                // SKIP: skip next instruction if beam past position
                val vp = (copIR1 shr 8) and 0xFF
                val hp = copIR1 and 0xFE
                if (vpos > vp || (vpos == vp && hpos >= hp)) {
                    coppc += 4 // skip next instruction pair
                }
                copState = CopperState.FETCH_INST
            }
        }
    }

    /**
     * Advance beam position by one color clock.
     * Returns true at start of vertical blank (vpos wraps).
     */
    fun advanceBeam(): Boolean {
        hpos++
        if (hpos >= 228) { // PAL: 228 color clocks per line
            hpos = 0
            vpos++
            if (vpos >= 313) { // PAL: 313 lines per frame
                vpos = 0
                coppc = cop1lc
                copState = CopperState.FETCH_INST
                return true // VBLANK
            }
        }
        return false
    }
}
