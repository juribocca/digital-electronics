package com.namirial.trust.electronics.amiga500

/**
 * Amiga 500 — top-level system simulation.
 *
 * Wires together all hardware components:
 * - MC68000 CPU (7.09 MHz effective on PAL)
 * - Agnus (DMA controller, Copper, Blitter)
 * - Denise (video output)
 * - Paula (interrupts, audio)
 * - 2× CIA 8520 (timers, I/O)
 * - 512 KB Chip RAM + 256 KB Kickstart ROM
 *
 * Clock relationships (PAL):
 * - Master clock: 28.37516 MHz
 * - CPU clock: master/4 = 7.09 MHz
 * - Color clock: master/8 = 3.55 MHz (one DMA slot)
 * - CIA E-clock: CPU/10 = ~709 kHz
 */
class Amiga500 {

    val agnus = Agnus()
    val denise = Denise()
    val paula = Paula()
    val ciaA = CIA8520()
    val ciaB = CIA8520()
    val bus = AddressBus()
    val cpu: MC68000

    // Floppy subsystem
    val floppyDrives = Array(4) { FloppyDrive() }
    val floppyController: FloppyController

    val customRegisters: CustomRegisters

    private var colorClockCounter = 0
    private var ciaTickDivider = 0
    private var diskDmaSlotCounter = 0
    private var prevCiaBStep = true  // Previous /STEP state for edge detection

    init {
        floppyController = FloppyController(bus, paula)
        floppyController.drive = floppyDrives[0]
        customRegisters = CustomRegisters(agnus, denise, paula, floppyController)
        bus.ciaA = ciaA
        bus.ciaB = ciaB
        bus.customRegisters = customRegisters
        cpu = MC68000(bus)
    }

    /**
     * Load a Kickstart ROM image and reset the system.
     */
    fun loadKickstart(rom: ByteArray) {
        bus.loadKickstart(rom)
        // Mirror reset vectors to address 0 (overlay mode at boot)
        for (i in 0 until 8) {
            bus.chipRam[i] = rom[i]
        }
        cpu.reset()
    }

    /** Insert an ADF disk image into a drive (0–3). */
    fun insertDisk(driveNum: Int, adf: ByteArray) {
        floppyDrives[driveNum].insertDisk(adf)
    }

    /**
     * Execute one color clock (one DMA slot = 2 CPU cycles).
     */
    fun tickColorClock() {
        // CPU gets 2 cycles per color clock
        cpu.step()
        cpu.step()

        // Copper gets one cycle every other color clock
        colorClockCounter++
        if (colorClockCounter and 1 == 0) {
            agnus.copperCycle(bus, paula)
        }

        // Disk DMA: one word every 3 color clocks when disk DMA enabled
        diskDmaSlotCounter++
        if (diskDmaSlotCounter >= 3) {
            diskDmaSlotCounter = 0
            if (agnus.dmaEnabled(Agnus.DMA_DISK)) {
                floppyController.tick()
            }
        }

        // Advance beam
        val vblank = agnus.advanceBeam()
        if (vblank) {
            paula.requestInterrupt(Paula.INT_VERTB)
        }

        // CIA E-clock: divide by 5 (color clocks) ≈ 709 kHz
        ciaTickDivider++
        if (ciaTickDivider >= 5) {
            ciaTickDivider = 0
            ciaA.tick()
            ciaB.tick()
            updateDiskSignals()
            if (ciaA.irqPending()) paula.requestInterrupt(Paula.INT_PORTS)
            if (ciaB.irqPending()) paula.requestInterrupt(Paula.INT_EXTER)
        }

        // Check interrupts for CPU
        val ipl = paula.activeIPL()
        if (ipl > cpu.interruptMask) {
            triggerInterrupt(ipl)
        }
    }

    /**
     * Process CIA-B port B outputs → drive control signals.
     * Process drive status → CIA-B port A inputs.
     */
    private fun updateDiskSignals() {
        val prb = ciaB.prb

        // Determine which drive is selected (active low, bits 3–6)
        val selectedDrive = when {
            (prb and 0x08) == 0 -> 0
            (prb and 0x10) == 0 -> 1
            (prb and 0x20) == 0 -> 2
            (prb and 0x40) == 0 -> 3
            else -> -1
        }

        val drive = if (selectedDrive in 0..3) floppyDrives[selectedDrive] else null
        floppyController.drive = drive

        if (drive != null) {
            // Motor control (active low)
            drive.motorOn = (prb and 0x80) == 0

            // Side select (active low: 0 = side 1/lower)
            drive.side = if ((prb and 0x04) == 0) 1 else 0

            // Step pulse (falling edge of /STEP, bit 0)
            val stepNow = (prb and 0x01) == 0
            if (stepNow && !prevCiaBStep) {
                // Falling edge detected — step the head
                val dirInward = (prb and 0x02) != 0
                drive.step(dirInward)
                floppyController.resetDMA() // Track changed, reload MFM
            }
            prevCiaBStep = stepNow

            // Update CIA-B PRA with drive status (active low signals)
            var pra = ciaB.pra or 0x3C // Set bits 5-2 high (inactive) by default
            if (drive.motorOn && drive.diskInserted) pra = pra and 0xDF.toInt() // /DSKRDY low
            if (drive.isTrack0()) pra = pra and 0xEF.toInt()                    // /DSKTRACK0 low
            if (drive.writeProtected) pra = pra and 0xF7.toInt()                // /DSKPROT low
            if (drive.diskChanged) pra = pra and 0xFB.toInt()                   // /DSKCHANGE low
            ciaB.pra = pra
        }
    }

    fun runScanlines(lines: Int) { repeat(lines * 228) { tickColorClock() } }
    fun runFrame() = runScanlines(313)

    private fun triggerInterrupt(level: Int) {
        val vectorAddr = (24 + level) * 4
        val oldSr = cpu.sr
        cpu.sr = (cpu.sr and 0xF8FF.toInt()) or (level shl 8)
        cpu.sr = cpu.sr or 0x2000
        cpu.a[7] -= 4
        bus.writeLong(cpu.a[7], cpu.pc)
        cpu.a[7] -= 2
        bus.writeWord(cpu.a[7], oldSr)
        cpu.pc = bus.readLong(vectorAddr)
    }
}
