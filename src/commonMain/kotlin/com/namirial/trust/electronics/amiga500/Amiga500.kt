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

    val customRegisters = CustomRegisters(agnus, denise, paula)

    private var colorClockCounter = 0
    private var ciaTickDivider = 0

    init {
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

    /**
     * Execute one color clock (one DMA slot = 2 CPU cycles).
     * This is the fundamental timing unit of the Amiga.
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
            // CIA-A interrupt → Paula PORTS (IPL 2)
            if (ciaA.irqPending()) paula.requestInterrupt(Paula.INT_PORTS)
            // CIA-B interrupt → Paula EXTER (IPL 6)
            if (ciaB.irqPending()) paula.requestInterrupt(Paula.INT_EXTER)
        }

        // Check interrupts for CPU
        val ipl = paula.activeIPL()
        if (ipl > cpu.interruptMask) {
            // Trigger autovector interrupt on 68000
            triggerInterrupt(ipl)
        }
    }

    /**
     * Run for a specified number of scanlines.
     */
    fun runScanlines(lines: Int) {
        repeat(lines * 228) { tickColorClock() }
    }

    /**
     * Run for one full PAL frame (313 lines × 228 color clocks).
     */
    fun runFrame() = runScanlines(313)

    private fun triggerInterrupt(level: Int) {
        // 68000 autovector: vector = 24 + level
        val vectorAddr = (24 + level) * 4
        val oldSr = cpu.sr
        cpu.sr = (cpu.sr and 0xF8FF.toInt()) or (level shl 8) // set IPL mask
        cpu.sr = cpu.sr or 0x2000 // enter supervisor mode
        // Push PC and SR
        cpu.a[7] -= 4
        bus.writeLong(cpu.a[7], cpu.pc)
        cpu.a[7] -= 2
        bus.writeWord(cpu.a[7], oldSr)
        // Load new PC from vector table
        cpu.pc = bus.readLong(vectorAddr)
    }
}
