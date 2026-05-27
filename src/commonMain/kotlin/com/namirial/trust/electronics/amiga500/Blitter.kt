package com.namirial.trust.electronics.amiga500

/**
 * Amiga Blitter — hardware block transfer and line drawing engine.
 *
 * Capabilities:
 * - Copy rectangular regions with 4-source minterm logic (A, B, C → D)
 * - Barrel shift sources A and B (0–15 bits)
 * - First/last word masks on source A
 * - Area fill (inclusive or exclusive)
 * - Line drawing (Bresenham algorithm in hardware)
 * - Descending mode (for overlapping copies)
 *
 * Registers (managed by Agnus, passed here for execution):
 * - BLTCON0: Use flags (A/B/C/D), shift A, minterm
 * - BLTCON1: Shift B, fill mode, line mode, direction
 * - BLTAFWM/BLTALWM: First/last word masks for source A
 * - BLTxPT: DMA pointers for channels A, B, C, D
 * - BLTxMOD: Modulo (bytes to skip at end of each line)
 * - BLTSIZE: Height (bits 15–6) × Width (bits 5–0, in words) — triggers blit
 */
class Blitter(private val bus: AddressBus) {

    // --- Registers (set by Agnus before execute) ---
    var con0: Int = 0       // BLTCON0: ASH[15:12], USE[11:8], MINTERM[7:0]
    var con1: Int = 0       // BLTCON1: BSH[15:12], flags[3:0]
    var afwm: Int = 0xFFFF  // First word mask
    var alwm: Int = 0xFFFF  // Last word mask
    var apt: Int = 0        // Channel A pointer
    var bpt: Int = 0        // Channel B pointer
    var cpt: Int = 0        // Channel C pointer
    var dpt: Int = 0        // Channel D pointer
    var amod: Int = 0       // Channel A modulo
    var bmod: Int = 0       // Channel B modulo
    var cmod: Int = 0       // Channel C modulo
    var dmod: Int = 0       // Channel D modulo
    var adat: Int = 0       // Channel A data hold
    var bdat: Int = 0       // Channel B data hold

    var busy: Boolean = false
    var bzero: Boolean = true  // BLTZERO flag: all D output was zero

    /**
     * Execute a blit operation. Called when BLTSIZE is written.
     * @param size BLTSIZE value: height[15:6], width[5:0] in words
     */
    fun execute(size: Int) {
        busy = true
        bzero = true

        val height = (size shr 6) and 0x3FF
        val width = size and 0x3F
        val h = if (height == 0) 1024 else height
        val w = if (width == 0) 64 else width

        if ((con1 and 0x01) != 0) {
            executeLine(h, w)
        } else {
            executeArea(h, w)
        }

        busy = false
    }

    // --- Area blit ---
    private fun executeArea(height: Int, width: Int) {
        val useA = (con0 and 0x0800) != 0
        val useB = (con0 and 0x0400) != 0
        val useC = (con0 and 0x0200) != 0
        val useD = (con0 and 0x0100) != 0
        val ashft = (con0 shr 12) and 0xF
        val bshft = (con1 shr 12) and 0xF
        val minterm = con0 and 0xFF
        val desc = (con1 and 0x02) != 0  // Descending mode
        val fillMode = (con1 shr 2) and 0x3 // 0=none, 1=inclusive, 2=exclusive

        // Fill carry state (one per line, starts from right)
        var fillCarry: Boolean

        for (y in 0 until height) {
            fillCarry = false
            var prevA = 0

            for (x in 0 until width) {
                // Read sources
                var a = if (useA) bus.readWord(apt) else 0
                var b = if (useB) bus.readWord(bpt) else bdat
                val c = if (useC) bus.readWord(cpt) else 0

                // Apply first/last word mask to A
                if (x == 0) a = a and afwm
                if (x == width - 1) a = a and alwm

                // Barrel shift A
                if (ashft != 0) {
                    val combined = (prevA shl 16) or (a and 0xFFFF)
                    a = if (desc) (combined ushr (16 - ashft)) and 0xFFFF
                        else (combined ushr ashft) and 0xFFFF
                }
                prevA = if (useA) bus.readWord(apt) else 0 // re-read for shift pipeline

                // Barrel shift B
                if (bshft != 0) {
                    val combined = (bdat shl 16) or (b and 0xFFFF)
                    b = if (desc) (combined ushr (16 - bshft)) and 0xFFFF
                        else (combined ushr bshft) and 0xFFFF
                }
                if (useB) bdat = bus.readWord(bpt)

                // Minterm logic: combine A, B, C → D
                var d = applyMinterm(a and 0xFFFF, b and 0xFFFF, c and 0xFFFF, minterm)

                // Area fill
                if (fillMode != 0) {
                    d = applyFill(d, fillCarry, fillMode == 2)
                    fillCarry = (d and 0x0001) != 0 // carry from bit 0 (rightmost)
                }

                if (d != 0) bzero = false

                // Write D
                if (useD) bus.writeWord(dpt, d and 0xFFFF)

                // Advance pointers
                val step = if (desc) -2 else 2
                if (useA) apt += step
                if (useB) bpt += step
                if (useC) cpt += step
                if (useD) dpt += step
            }

            // End of line: add modulo
            val modSign = if (desc) -1 else 1
            if (useA) apt += amod * modSign
            if (useB) bpt += bmod * modSign
            if (useC) cpt += cmod * modSign
            if (useD) dpt += dmod * modSign
        }
    }

    // --- Line draw (Bresenham) ---
    private fun executeLine(height: Int, @Suppress("UNUSED_PARAMETER") width: Int) {
        // In line mode:
        // - height = number of pixels to draw
        // - width is always 2 (ignored, we use height as pixel count)
        // - BLTCON0[15:12] = starting pixel position within word (ASH)
        // - BLTCON1[3:2] = octant/direction
        // - apt = Bresenham error accumulator (2 words: D value)
        // - bpt = not used
        // - cpt = pointer to first word of line in bitplane
        // - dpt = same as cpt (single-pixel writes)
        // - amod = 4*(dy - dx) when error >= 0 (move diagonal)
        // - bmod = 4*dy (move major axis only)
        // - cmod/dmod = bitplane width in bytes

        val minterm = con0 and 0xFF
        val useA = (con0 and 0x0800) != 0
        val useC = (con0 and 0x0200) != 0
        val useD = (con0 and 0x0100) != 0
        val octant = (con1 shr 2) and 0x7
        val oneDot = (con1 and 0x02) != 0 // single-pixel-per-horizontal-line mode
        var pixelPos = (con0 shr 12) and 0xF
        var error = (bus.readWord(apt) shl 16) or (bus.readWord(apt + 2) and 0xFFFF)
        var ptr = cpt
        val bplWidth = cmod // bytes per bitplane row

        var lastHLine = -1 // for single-dot mode

        for (i in 0 until height) {
            // Draw pixel (set bit at pixelPos in word at ptr)
            if (!oneDot || (ptr / bplWidth.coerceAtLeast(1)) != lastHLine) {
                val c = if (useC) bus.readWord(ptr and 0xFFFFFE.toInt()) else 0
                val a = 0x8000 ushr pixelPos
                val d = applyMinterm(a, 0xFFFF, c, minterm)
                if (useD) bus.writeWord(ptr and 0xFFFFFE.toInt(), d and 0xFFFF)
                if (d != 0) bzero = false
                lastHLine = ptr / bplWidth.coerceAtLeast(1)
            }

            // Bresenham step
            if (error >= 0) {
                // Move along minor axis
                error += amod
                ptr += minorStep(octant, bplWidth)
            } else {
                error += bmod
            }
            // Always move along major axis
            ptr += majorStep(octant, bplWidth, pixelPos).also { pixelPos = newPixelPos(octant, pixelPos) }
        }

        // Write back error term
        apt = apt // pointer unchanged for line mode
        cpt = ptr
        dpt = ptr
    }

    private fun majorStep(octant: Int, bplWidth: Int, pixelPos: Int): Int = when (octant) {
        0, 1 -> if (pixelPos == 15) 2 else 0  // right
        2, 3 -> bplWidth                        // down
        4, 5 -> if (pixelPos == 0) -2 else 0   // left
        6, 7 -> -bplWidth                       // up
        else -> 0
    }

    private fun minorStep(octant: Int, bplWidth: Int): Int = when (octant) {
        0, 7 -> bplWidth    // down
        1, 6 -> -bplWidth   // up
        2, 5 -> 2           // right (approximate)
        3, 4 -> -2          // left (approximate)
        else -> 0
    }

    private fun newPixelPos(octant: Int, pos: Int): Int = when (octant) {
        0, 1 -> (pos + 1) and 0xF
        4, 5 -> (pos - 1) and 0xF
        else -> pos
    }

    // --- Minterm logic ---
    /**
     * Apply the 8-bit minterm function to three 16-bit sources.
     * Each bit of the minterm selects a combination of A, B, C:
     * bit 0: !A & !B & !C    bit 4: A & !B & !C
     * bit 1: !A & !B &  C    bit 5: A & !B &  C
     * bit 2: !A &  B & !C    bit 6: A &  B & !C
     * bit 3: !A &  B &  C    bit 7: A &  B &  C
     */
    private fun applyMinterm(a: Int, b: Int, c: Int, minterm: Int): Int {
        var result = 0
        if ((minterm and 0x01) != 0) result = result or (a.inv() and b.inv() and c.inv())
        if ((minterm and 0x02) != 0) result = result or (a.inv() and b.inv() and c)
        if ((minterm and 0x04) != 0) result = result or (a.inv() and b and c.inv())
        if ((minterm and 0x08) != 0) result = result or (a.inv() and b and c)
        if ((minterm and 0x10) != 0) result = result or (a and b.inv() and c.inv())
        if ((minterm and 0x20) != 0) result = result or (a and b.inv() and c)
        if ((minterm and 0x40) != 0) result = result or (a and b and c.inv())
        if ((minterm and 0x80) != 0) result = result or (a and b and c)
        return result and 0xFFFF
    }

    // --- Area fill ---
    /**
     * Apply fill to a 16-bit word, processing bits from right (bit 0) to left (bit 15).
     * @param exclusive true for exclusive fill (XOR), false for inclusive fill (OR carry)
     */
    private fun applyFill(word: Int, carryIn: Boolean, exclusive: Boolean): Int {
        var carry = carryIn
        var result = 0
        for (bit in 0 until 16) {
            val srcBit = (word shr bit) and 1
            if (exclusive) {
                val outBit = srcBit xor (if (carry) 1 else 0)
                result = result or (outBit shl bit)
                if (srcBit != 0) carry = !carry
            } else {
                if (srcBit != 0) carry = !carry
                val outBit = if (carry) 1 else srcBit
                result = result or (outBit shl bit)
            }
        }
        return result and 0xFFFF
    }
}
