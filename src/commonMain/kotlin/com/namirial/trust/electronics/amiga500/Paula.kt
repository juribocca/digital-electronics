package com.namirial.trust.electronics.amiga500

/**
 * Paula (8364) — Amiga custom chip handling:
 * - Interrupt controller (14 interrupt sources, directly drives 68000 IPL lines)
 * - 4-channel DMA audio
 * - Floppy disk DMA
 * - UART (serial port)
 *
 * Key registers (offsets within $DFF000):
 * - INTREQR ($01E): Interrupt request read
 * - INTREQ  ($09C): Interrupt request write (set/clear)
 * - INTENAR ($01C): Interrupt enable read
 * - INTENA  ($09A): Interrupt enable write (set/clear)
 * - AUDxLCH/LCL/LEN/PER/VOL/DAT ($0A0–$0DF): Audio channels 0–3
 */
class Paula {

    // --- Interrupt controller ---
    // 14 interrupt sources mapped to 68000 IPL levels 1–6
    var intena: Int = 0   // Interrupt enable (bit 14 = master enable)
    var intreq: Int = 0   // Interrupt request flags

    // Interrupt bit assignments
    companion object {
        const val INT_TBE = 0      // Serial transmit buffer empty
        const val INT_DSKBLK = 1   // Disk block finished
        const val INT_SOFT = 2     // Software interrupt
        const val INT_PORTS = 3    // CIA-A (I/O ports, timers)
        const val INT_COPER = 4    // Copper
        const val INT_VERTB = 5    // Vertical blank
        const val INT_BLIT = 6     // Blitter finished
        const val INT_AUD0 = 7     // Audio channel 0
        const val INT_AUD1 = 8     // Audio channel 1
        const val INT_AUD2 = 9     // Audio channel 2
        const val INT_AUD3 = 10    // Audio channel 3
        const val INT_RBF = 11     // Serial receive buffer full
        const val INT_DSKSYN = 12  // Disk sync found
        const val INT_EXTER = 13   // CIA-B (external)
    }

    // --- Audio channels with DMA state machine ---
    data class AudioChannel(
        var locationHi: Int = 0,   // AUDxLCH
        var locationLo: Int = 0,   // AUDxLCL
        var length: Int = 0,       // AUDxLEN (words)
        var period: Int = 0,       // AUDxPER
        var volume: Int = 0,       // AUDxVOL (0–64)
        var data: Int = 0,         // AUDxDAT
        // DMA state
        var dmaPtr: Int = 0,       // Current DMA fetch pointer
        var lenCounter: Int = 0,   // Words remaining in current buffer
        var periodCounter: Int = 0,// Countdown to next sample output
        var currentSample: Int = 0,// Current 8-bit sample being output
        var highByte: Boolean = true // Outputting high or low byte of data word
    ) {
        val location: Int get() = ((locationHi and 0x1F) shl 16) or (locationLo and 0xFFFE)
    }

    val audio = Array(4) { AudioChannel() }

    /** Current output sample per channel (signed 8-bit × volume, for external use). */
    val audioOutput = IntArray(4)

    /**
     * Tick audio DMA — called once per DMA slot (every color clock).
     * Each channel counts down its period; when it reaches 0, outputs next sample byte.
     * When the data register is exhausted, fetches next word from DMA pointer.
     * When length counter reaches 0, reloads pointer/length and fires interrupt.
     */
    fun tickAudio(bus: AddressBus, dmacon: Int) {
        for (ch in 0 until 4) {
            val dmaEnabled = (dmacon and (1 shl ch)) != 0 && (dmacon and 0x200) != 0
            val a = audio[ch]

            if (a.period == 0) continue
            a.periodCounter--
            if (a.periodCounter > 0) continue

            // Period expired — output next sample byte
            a.periodCounter = a.period

            if (a.highByte) {
                a.currentSample = (a.data shr 8) and 0xFF
                a.highByte = false
            } else {
                a.currentSample = a.data and 0xFF
                a.highByte = true

                // Need next word
                if (dmaEnabled) {
                    a.lenCounter--
                    if (a.lenCounter <= 0) {
                        // Buffer empty — reload and interrupt
                        a.dmaPtr = a.location
                        a.lenCounter = if (a.length == 0) 65536 else a.length
                        requestInterrupt(INT_AUD0 + ch)
                    }
                    a.data = bus.readWord(a.dmaPtr and 0x1FFFFE)
                    a.dmaPtr += 2
                }
            }

            // Output: signed sample × volume (result is 14-bit signed)
            val signed = a.currentSample.toByte().toInt() // sign-extend to int
            audioOutput[ch] = signed * a.volume
        }
    }

    /** Start audio DMA for a channel (called when DMACON enables a channel). */
    fun startAudioDMA(ch: Int) {
        val a = audio[ch]
        a.dmaPtr = a.location
        a.lenCounter = if (a.length == 0) 65536 else a.length
        a.periodCounter = a.period
        a.highByte = true
    }

    /** Read a Paula register. */
    fun readReg(offset: Int): Int = when (offset) {
        0x01C -> intena   // INTENAR
        0x01E -> intreq   // INTREQR
        else -> 0
    }

    /** Write a Paula register. */
    fun writeReg(offset: Int, value: Int) {
        when (offset) {
            0x09A -> { // INTENA
                intena = if (value and 0x8000 != 0) intena or (value and 0x7FFF)
                else intena and (value and 0x7FFF).inv()
            }
            0x09C -> { // INTREQ
                intreq = if (value and 0x8000 != 0) intreq or (value and 0x7FFF)
                else intreq and (value and 0x7FFF).inv()
            }
            in 0x0A0..0x0DF -> writeAudioReg(offset, value)
        }
    }

    private fun writeAudioReg(offset: Int, value: Int) {
        val ch = (offset - 0x0A0) / 0x10
        if (ch > 3) return
        when ((offset - 0x0A0) % 0x10) {
            0x0 -> audio[ch].locationHi = value
            0x2 -> audio[ch].locationLo = value
            0x4 -> audio[ch].length = value
            0x6 -> audio[ch].period = value
            0x8 -> audio[ch].volume = value
            0xA -> audio[ch].data = value
        }
    }

    /**
     * Request an interrupt (set a bit in INTREQ).
     */
    fun requestInterrupt(bit: Int) {
        intreq = intreq or (1 shl bit)
    }

    /**
     * Returns the highest active IPL level (1–6) for the 68000, or 0 if none.
     * Amiga interrupt priority mapping:
     * - IPL 1: INT_TBE, INT_DSKBLK, INT_SOFT
     * - IPL 2: INT_PORTS (CIA-A)
     * - IPL 3: INT_COPER, INT_VERTB, INT_BLIT
     * - IPL 4: INT_AUD0–3
     * - IPL 5: INT_RBF, INT_DSKSYN
     * - IPL 6: INT_EXTER (CIA-B)
     */
    fun activeIPL(): Int {
        if (intena and 0x4000 == 0) return 0 // master enable off
        val active = intreq and intena and 0x3FFF
        if (active == 0) return 0
        return when {
            active and (1 shl INT_EXTER) != 0 -> 6
            active and ((1 shl INT_RBF) or (1 shl INT_DSKSYN)) != 0 -> 5
            active and ((1 shl INT_AUD0) or (1 shl INT_AUD1) or (1 shl INT_AUD2) or (1 shl INT_AUD3)) != 0 -> 4
            active and ((1 shl INT_COPER) or (1 shl INT_VERTB) or (1 shl INT_BLIT)) != 0 -> 3
            active and (1 shl INT_PORTS) != 0 -> 2
            active and ((1 shl INT_TBE) or (1 shl INT_DSKBLK) or (1 shl INT_SOFT)) != 0 -> 1
            else -> 0
        }
    }
}
