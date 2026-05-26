package com.namirial.trust.electronics.amiga500

/**
 * MOS 8520 CIA (Complex Interface Adapter) — used as CIA-A and CIA-B in the Amiga 500.
 *
 * Features:
 * - 2 interval timers (Timer A, Timer B) with 16-bit countdown
 * - 8-bit parallel I/O ports (Port A, Port B) with data direction registers
 * - Time-of-Day clock (24-bit, BCD)
 * - Serial shift register
 * - Interrupt control (5 sources: TA, TB, ALARM, SP, FLAG)
 *
 * CIA-A ($BFE001, odd): keyboard, joystick fire, overlay, LED, disk
 * CIA-B ($BFD000, even): parallel port, disk motor/side/step
 */
class CIA8520 {

    // Registers (offsets 0x0–0xF)
    companion object {
        const val PRA = 0x0
        const val PRB = 0x1
        const val DDRA = 0x2
        const val DDRB = 0x3
        const val TALO = 0x4
        const val TAHI = 0x5
        const val TBLO = 0x6
        const val TBHI = 0x7
        const val TOD_LO = 0x8
        const val TOD_MID = 0x9
        const val TOD_HI = 0xA
        const val SDR = 0xC
        const val ICR = 0xD
        const val CRA = 0xE
        const val CRB = 0xF
    }

    // I/O ports
    var pra: Int = 0xFF
    var prb: Int = 0xFF
    var ddra: Int = 0       // 0 = input, 1 = output
    var ddrb: Int = 0

    // Timers
    var timerALatch: Int = 0xFFFF
    var timerACounter: Int = 0xFFFF
    var timerBLatch: Int = 0xFFFF
    var timerBCounter: Int = 0xFFFF
    var cra: Int = 0        // Control Register A
    var crb: Int = 0        // Control Register B

    // TOD
    var todLo: Int = 0
    var todMid: Int = 0
    var todHi: Int = 0

    // Interrupts
    var icrMask: Int = 0    // Interrupt enable mask
    var icrData: Int = 0    // Interrupt flags (pending)

    // Serial
    var sdr: Int = 0

    /** Read a CIA register. */
    fun read(reg: Int): Int = when (reg) {
        PRA -> (pra or ddra.inv()) and 0xFF
        PRB -> (prb or ddrb.inv()) and 0xFF
        DDRA -> ddra
        DDRB -> ddrb
        TALO -> timerACounter and 0xFF
        TAHI -> (timerACounter shr 8) and 0xFF
        TBLO -> timerBCounter and 0xFF
        TBHI -> (timerBCounter shr 8) and 0xFF
        TOD_LO -> todLo
        TOD_MID -> todMid
        TOD_HI -> todHi
        SDR -> sdr
        ICR -> {
            val v = icrData or (if (icrData and icrMask != 0) 0x80 else 0)
            icrData = 0  // reading clears flags
            v
        }
        CRA -> cra
        CRB -> crb
        else -> 0
    }

    /** Write a CIA register. */
    fun write(reg: Int, value: Int) {
        when (reg) {
            PRA -> pra = value and 0xFF
            PRB -> prb = value and 0xFF
            DDRA -> ddra = value and 0xFF
            DDRB -> ddrb = value and 0xFF
            TALO -> timerALatch = (timerALatch and 0xFF00) or (value and 0xFF)
            TAHI -> {
                timerALatch = (timerALatch and 0x00FF) or ((value and 0xFF) shl 8)
                if (cra and 0x01 == 0) timerACounter = timerALatch // load if stopped
            }
            TBLO -> timerBLatch = (timerBLatch and 0xFF00) or (value and 0xFF)
            TBHI -> {
                timerBLatch = (timerBLatch and 0x00FF) or ((value and 0xFF) shl 8)
                if (crb and 0x01 == 0) timerBCounter = timerBLatch
            }
            TOD_LO -> todLo = value and 0xFF
            TOD_MID -> todMid = value and 0xFF
            TOD_HI -> todHi = value and 0xFF
            SDR -> sdr = value and 0xFF
            ICR -> {
                if (value and 0x80 != 0) icrMask = icrMask or (value and 0x1F)
                else icrMask = icrMask and (value and 0x1F).inv()
            }
            CRA -> {
                cra = value and 0xFF
                if (value and 0x10 != 0) timerACounter = timerALatch // force load
            }
            CRB -> {
                crb = value and 0xFF
                if (value and 0x10 != 0) timerBCounter = timerBLatch
            }
        }
    }

    /**
     * Tick the CIA (called at E-clock rate, ~709 kHz on PAL).
     * Decrements running timers and fires interrupts on underflow.
     */
    fun tick() {
        if (cra and 0x01 != 0) { // Timer A running
            timerACounter = (timerACounter - 1) and 0xFFFF
            if (timerACounter == 0) {
                icrData = icrData or 0x01 // TA underflow flag
                timerACounter = if (cra and 0x08 != 0) {
                    cra = cra and 0xFE.toInt() // one-shot: stop
                    timerALatch
                } else timerALatch
            }
        }
        if (crb and 0x01 != 0) { // Timer B running
            timerBCounter = (timerBCounter - 1) and 0xFFFF
            if (timerBCounter == 0) {
                icrData = icrData or 0x02 // TB underflow flag
                timerBCounter = if (crb and 0x08 != 0) {
                    crb = crb and 0xFE.toInt()
                    timerBLatch
                } else timerBLatch
            }
        }
    }

    /** Returns true if an interrupt is pending (ICR bit 7). */
    fun irqPending(): Boolean = icrData and icrMask != 0
}
