package com.namirial.trust.electronics.amiga500

/**
 * Amiga floppy disk drive (DF0:–DF3:) — reads ADF disk images.
 *
 * ADF format: 880 KB = 80 tracks × 2 sides × 11 sectors × 512 bytes
 * Track layout in ADF: track 0 side 0, track 0 side 1, track 1 side 0, ...
 *
 * The drive provides raw MFM-encoded track data to the disk controller.
 * Each MFM track = 11 sectors × (64 bytes header + 512 bytes data) encoded as MFM.
 * Raw MFM track size: 12668 bytes (6334 words) — standard Amiga track length.
 */
class FloppyDrive {

    companion object {
        const val TRACKS = 80
        const val SIDES = 2
        const val SECTORS_PER_TRACK = 11
        const val SECTOR_SIZE = 512
        const val TRACK_SIZE = SECTORS_PER_TRACK * SECTOR_SIZE // 5632 bytes (decoded)
        const val ADF_SIZE = TRACKS * SIDES * TRACK_SIZE       // 901120 bytes (880 KB)
        const val MFM_TRACK_WORDS = 6250  // standard raw MFM track length in words
    }

    private var diskData: ByteArray? = null
    var currentTrack: Int = 0
        private set
    var side: Int = 0           // 0 = upper, 1 = lower
    var motorOn: Boolean = false
    var diskInserted: Boolean = false
        private set
    var diskChanged: Boolean = true  // /DSKCHANGE signal (active low: true = disk was changed)
        private set
    var writeProtected: Boolean = true

    /** Insert an ADF disk image. */
    fun insertDisk(adf: ByteArray) {
        require(adf.size == ADF_SIZE) { "ADF must be exactly $ADF_SIZE bytes (880 KB)" }
        diskData = adf.copyOf()
        diskInserted = true
        diskChanged = true
        writeProtected = true
    }

    /** Eject the disk. */
    fun ejectDisk() {
        diskData = null
        diskInserted = false
        diskChanged = true
    }

    /** Step head one track in the given direction. */
    fun step(directionInward: Boolean) {
        if (directionInward) {
            if (currentTrack < TRACKS - 1) currentTrack++
        } else {
            if (currentTrack > 0) currentTrack--
        }
    }

    /** Returns true if head is at track 0. */
    fun isTrack0(): Boolean = currentTrack == 0

    /** Acknowledge disk change (reading DSKCHANGE clears it if disk is present). */
    fun acknowledgeChange() {
        if (diskInserted) diskChanged = false
    }

    /**
     * Read decoded sector data from the current track/side.
     * Returns 512 bytes for the given sector (0–10).
     */
    fun readSector(sector: Int): ByteArray {
        val data = diskData ?: return ByteArray(SECTOR_SIZE)
        val trackOffset = ((currentTrack * 2) + side) * TRACK_SIZE
        val sectorOffset = trackOffset + (sector * SECTOR_SIZE)
        return data.copyOfRange(sectorOffset, sectorOffset + SECTOR_SIZE)
    }

    /**
     * Encode the current track/side as a raw MFM bitstream (as words).
     * This is what the disk DMA reads into Chip RAM.
     *
     * Amiga MFM track format per sector:
     * - 2 words: $0000 (gap)
     * - 1 word: $4489 (sync)
     * - 1 word: $4489 (sync)
     * - 1 long: sector info (MFM encoded)
     * - 16 bytes: sector label (MFM encoded, usually zeros)
     * - 1 long: header checksum (MFM)
     * - 1 long: data checksum (MFM)
     * - 512 bytes: sector data (MFM encoded)
     */
    fun readTrackMFM(): IntArray {
        val mfm = IntArray(MFM_TRACK_WORDS)
        var pos = 0

        // Gap at start of track
        repeat(2) { if (pos < mfm.size) mfm[pos++] = 0xAAAA.toInt() }

        for (sector in 0 until SECTORS_PER_TRACK) {
            // Sync words
            if (pos < mfm.size) mfm[pos++] = 0x4489
            if (pos < mfm.size) mfm[pos++] = 0x4489

            // Sector info: format=$FF, track, sector, sectors-to-gap
            val info = (0xFF shl 24) or (currentTrack * 2 + side shl 16) or
                       (sector shl 8) or (SECTORS_PER_TRACK - sector)
            val infoMfm = encodeMFMLong(info)
            if (pos + 3 < mfm.size) { mfm[pos++] = infoMfm[0]; mfm[pos++] = infoMfm[1]; mfm[pos++] = infoMfm[2]; mfm[pos++] = infoMfm[3] }

            // Sector label (16 bytes of zeros, MFM encoded = 16 words)
            repeat(16) { if (pos < mfm.size) mfm[pos++] = 0xAAAA.toInt() }

            // Header checksum (XOR of info + label MFM odd/even longs)
            val hdrChk = encodeMFMLong(computeHeaderChecksum(info))
            if (pos + 3 < mfm.size) { mfm[pos++] = hdrChk[0]; mfm[pos++] = hdrChk[1]; mfm[pos++] = hdrChk[2]; mfm[pos++] = hdrChk[3] }

            // Data checksum
            val sectorData = readSector(sector)
            val dataChk = encodeMFMLong(computeDataChecksum(sectorData))
            if (pos + 3 < mfm.size) { mfm[pos++] = dataChk[0]; mfm[pos++] = dataChk[1]; mfm[pos++] = dataChk[2]; mfm[pos++] = dataChk[3] }

            // Sector data (512 bytes = 256 words, MFM encoded = 512 words)
            val dataMfm = encodeMFMBlock(sectorData)
            for (w in dataMfm) { if (pos < mfm.size) mfm[pos++] = w }

            // Inter-sector gap
            repeat(2) { if (pos < mfm.size) mfm[pos++] = 0xAAAA.toInt() }
        }

        // Fill remaining with gap
        while (pos < mfm.size) mfm[pos++] = 0xAAAA.toInt()
        return mfm
    }

    // --- MFM encoding helpers ---

    /** Encode a 32-bit value as 4 MFM words (odd bits first, then even bits). */
    private fun encodeMFMLong(value: Int): IntArray {
        val odd = ((value shr 1) and 0x55555555)
        val even = (value and 0x55555555)
        return intArrayOf(
            (odd ushr 16) and 0xFFFF, odd and 0xFFFF,
            (even ushr 16) and 0xFFFF, even and 0xFFFF
        )
    }

    /** Encode a byte array as MFM words (Amiga odd/even split encoding). */
    private fun encodeMFMBlock(data: ByteArray): IntArray {
        // Amiga MFM: first all odd bits of all longs, then all even bits
        val longs = data.size / 4
        val result = IntArray(data.size / 2 * 2) // odd words + even words
        var pos = 0
        // Odd bits
        for (i in 0 until longs) {
            val v = ((data[i * 4].toInt() and 0xFF) shl 24) or
                    ((data[i * 4 + 1].toInt() and 0xFF) shl 16) or
                    ((data[i * 4 + 2].toInt() and 0xFF) shl 8) or
                    (data[i * 4 + 3].toInt() and 0xFF)
            val odd = (v ushr 1) and 0x55555555
            result[pos++] = (odd ushr 16) and 0xFFFF
            result[pos++] = odd and 0xFFFF
        }
        // Even bits
        for (i in 0 until longs) {
            val v = ((data[i * 4].toInt() and 0xFF) shl 24) or
                    ((data[i * 4 + 1].toInt() and 0xFF) shl 16) or
                    ((data[i * 4 + 2].toInt() and 0xFF) shl 8) or
                    (data[i * 4 + 3].toInt() and 0xFF)
            val even = v and 0x55555555
            result[pos++] = (even ushr 16) and 0xFFFF
            result[pos++] = even and 0xFFFF
        }
        return result
    }

    private fun computeHeaderChecksum(info: Int): Int {
        // XOR of odd and even bits of the info long (label is zeros so doesn't contribute)
        return ((info ushr 1) and 0x55555555) xor (info and 0x55555555)
    }

    private fun computeDataChecksum(data: ByteArray): Int {
        var chk = 0
        for (i in data.indices step 4) {
            val v = ((data[i].toInt() and 0xFF) shl 24) or
                    ((data[i + 1].toInt() and 0xFF) shl 16) or
                    ((data[i + 2].toInt() and 0xFF) shl 8) or
                    (data[i + 3].toInt() and 0xFF)
            chk = chk xor ((v ushr 1) and 0x55555555) xor (v and 0x55555555)
        }
        return chk
    }
}
