package com.namirial.trust.electronics.amiga500

/**
 * Denise (8362) — Amiga video output chip.
 *
 * Responsibilities:
 * - Bitplane data → pixel color index conversion (planar to chunky)
 * - Color palette (32 × 12-bit RGB entries)
 * - Sprite multiplexing (8 sprites, 3 colors each + transparent)
 * - Playfield priority and collision detection
 * - HAM (Hold-And-Modify) and EHB (Extra Half-Brite) modes
 *
 * Key registers:
 * - BPLCON0 ($100): Bitplane control (number of planes, resolution, HAM)
 * - BPLCON1 ($102): Horizontal scroll
 * - BPLCON2 ($104): Playfield priority
 * - BPLxDAT ($110–$11A): Bitplane data (shift registers)
 * - COLOR00–COLOR31 ($180–$1BE): Color palette
 * - SPRxPOS/CTL/DATA/DATB ($140–$17E): Sprite registers
 */
class Denise {

    // --- Bitplane control ---
    var bplcon0: Int = 0   // Number of bitplanes, resolution, HAM/EHB
    var bplcon1: Int = 0   // Scroll values
    var bplcon2: Int = 0   // Priority control

    val numBitplanes: Int get() = (bplcon0 shr 12) and 0x7
    val hires: Boolean get() = bplcon0 and 0x8000 != 0
    val ham: Boolean get() = bplcon0 and 0x0800 != 0

    // --- Bitplane shift registers ---
    val bpldat = IntArray(6)

    // --- Color palette: 32 entries, 12-bit RGB (4 bits per channel) ---
    val color = IntArray(32)

    // --- Sprites (8 sprites) ---
    data class Sprite(
        var pos: Int = 0,
        var ctl: Int = 0,
        var data: Int = 0,
        var datb: Int = 0
    ) {
        val hstart: Int get() = ((pos shr 8) and 0xFF) or ((ctl and 0x01) shl 8)
        val vstart: Int get() = (pos and 0xFF) or ((ctl and 0x04) shl 6)
        val vstop: Int get() = ((ctl shr 8) and 0xFF) or ((ctl and 0x02) shl 7)
    }

    val sprites = Array(8) { Sprite() }

    /** Read a Denise register. */
    fun readReg(offset: Int): Int = when (offset) {
        0x100 -> bplcon0
        0x102 -> bplcon1
        0x104 -> bplcon2
        in 0x180..0x1BE -> color[(offset - 0x180) / 2]
        else -> 0
    }

    /** Write a Denise register. */
    fun writeReg(offset: Int, value: Int) {
        when (offset) {
            0x100 -> bplcon0 = value
            0x102 -> bplcon1 = value
            0x104 -> bplcon2 = value
            in 0x110..0x11A -> bpldat[(offset - 0x110) / 2] = value
            in 0x180..0x1BE -> color[(offset - 0x180) / 2] = value and 0xFFF
            in 0x140..0x17E -> writeSpriteReg(offset, value)
        }
    }

    private fun writeSpriteReg(offset: Int, value: Int) {
        val sprIdx = (offset - 0x140) / 8
        if (sprIdx > 7) return
        when ((offset - 0x140) % 8) {
            0 -> sprites[sprIdx].pos = value
            2 -> sprites[sprIdx].ctl = value
            4 -> sprites[sprIdx].data = value
            6 -> sprites[sprIdx].datb = value
        }
    }

    /**
     * Convert bitplane data at current pixel position to a color index (0–31).
     * Reads from the shift registers (bpldat), extracting bit [bitPos] from each plane.
     */
    fun pixelColor(bitPos: Int): Int {
        var idx = 0
        for (plane in 0 until numBitplanes) {
            if (bpldat[plane] and (1 shl (15 - bitPos)) != 0) {
                idx = idx or (1 shl plane)
            }
        }
        return idx
    }

    /**
     * Get the 12-bit RGB value for a pixel at the given bit position.
     * Returns 0xRGB (4 bits each).
     */
    fun pixelRGB(bitPos: Int): Int = color[pixelColor(bitPos)]

    /**
     * Extract R, G, B components (0–15 each) from a 12-bit color value.
     */
    fun colorToRGB(c: Int): Triple<Int, Int, Int> =
        Triple((c shr 8) and 0xF, (c shr 4) and 0xF, c and 0xF)
}
