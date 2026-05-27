package com.namirial.trust.electronics.amiga500

/**
 * Amiga Floppy Disk Controller — handles disk DMA between drive and Chip RAM.
 *
 * Custom registers (offsets within $DFF000):
 * - DSKPTH ($020): Disk DMA pointer (high)
 * - DSKPTL ($022): Disk DMA pointer (low)
 * - DSKLEN ($024): Disk DMA length + control
 * - DSKBYTR ($01A): Disk byte and status (read-only)
 * - DSKSYNC ($07E): Disk sync pattern
 * - ADKCON ($09E): Audio/disk control (bits 8–15 = disk)
 * - ADKCONR ($010): Audio/disk control read
 *
 * DMA operation:
 * 1. CPU sets DSKPT to Chip RAM destination
 * 2. CPU writes DSKLEN twice (with bit 15 set) to start DMA
 * 3. Controller waits for DSKSYNC word in MFM stream
 * 4. After sync found, transfers (DSKLEN & $3FFF) words to Chip RAM
 * 5. Fires DSKBLK interrupt when done
 */
class FloppyController(private val bus: AddressBus, private val paula: Paula) {

    // --- Registers ---
    var dskpt: Int = 0          // DMA pointer (24-bit)
    var dsklen: Int = 0         // Length register (bit15=DMA enable, bit14=write, bits 0-13=word count)
    var dsksync: Int = 0x4489   // Sync word to search for
    var adkcon: Int = 0         // Audio/disk control

    // --- DMA state ---
    private var dmaActive = false
    private var dmaWrite = false
    private var wordsRemaining = 0
    private var prevDsklenWrite = 0  // For double-write detection
    private var syncFound = false

    // --- MFM stream from current track ---
    private var mfmTrack: IntArray = IntArray(0)
    private var mfmPos = 0       // Current position in MFM stream (simulates disk rotation)

    // --- Drive reference ---
    var drive: FloppyDrive? = null

    /** Read a disk controller register. */
    fun readReg(offset: Int): Int = when (offset) {
        0x01A -> readDskbytr()
        0x010 -> adkcon  // ADKCONR
        else -> 0
    }

    /** Write a disk controller register. */
    fun writeReg(offset: Int, value: Int) {
        when (offset) {
            0x020 -> dskpt = (dskpt and 0x0000FFFF) or ((value and 0x1F) shl 16) // DSKPTH
            0x022 -> dskpt = (dskpt and 0xFFFF0000.toInt()) or (value and 0xFFFE) // DSKPTL
            0x024 -> writeDsklen(value)
            0x07E -> dsksync = value and 0xFFFF
            0x09E -> { // ADKCON
                if ((value and 0x8000) != 0) adkcon = adkcon or (value and 0x7FFF)
                else adkcon = adkcon and (value and 0x7FFF).inv()
            }
        }
    }

    private fun writeDsklen(value: Int) {
        if ((value and 0x8000) != 0 && (prevDsklenWrite and 0x8000) != 0) {
            // Double write with bit 15 set — start DMA
            dsklen = value
            dmaActive = true
            dmaWrite = (value and 0x4000) != 0
            wordsRemaining = value and 0x3FFF
            syncFound = (adkcon and 0x0400) == 0 // If WORDSYNC disabled, start immediately
            loadTrackMFM()
        } else if ((value and 0x8000) == 0) {
            // Bit 15 clear — stop DMA
            dmaActive = false
            dmaWrite = false
            wordsRemaining = 0
        }
        prevDsklenWrite = value
    }

    private fun readDskbytr(): Int {
        var status = 0
        if (dmaActive) status = status or 0x4000          // DSKBYT (DMA active)
        if (dmaActive && dmaWrite) status = status or 0x2000 // DSKWRITE
        if (syncFound) status = status or 0x1000           // WORDEQUAL (sync found)
        // Bits 0-7: last byte read from disk (approximate)
        if (mfmTrack.isNotEmpty()) {
            status = status or (mfmTrack[mfmPos % mfmTrack.size] and 0xFF)
        }
        return status
    }

    /** Load MFM data from the current drive track. */
    private fun loadTrackMFM() {
        val d = drive
        if (d != null && d.diskInserted && d.motorOn) {
            mfmTrack = d.readTrackMFM()
            mfmPos = 0
        } else {
            mfmTrack = IntArray(0)
        }
    }

    /**
     * Tick the disk DMA — called once per disk DMA slot (every 3 color clocks).
     * Transfers one word per call when active.
     */
    fun tick() {
        if (!dmaActive || mfmTrack.isEmpty()) return

        val word = mfmTrack[mfmPos % mfmTrack.size]
        mfmPos++

        if (!syncFound) {
            // Searching for sync word
            if (word == dsksync) {
                syncFound = true
                paula.requestInterrupt(Paula.INT_DSKSYN)
            }
            return
        }

        // Transfer word to/from Chip RAM
        if (!dmaWrite) {
            bus.writeWord(dskpt, word)
        } else {
            // Write to disk — not commonly used, ignore data
        }
        dskpt += 2
        wordsRemaining--

        if (wordsRemaining <= 0) {
            dmaActive = false
            paula.requestInterrupt(Paula.INT_DSKBLK)
        }
    }

    /** Reset DMA state (e.g., on track change). */
    fun resetDMA() {
        dmaActive = false
        syncFound = false
        wordsRemaining = 0
        mfmPos = 0
    }
}
