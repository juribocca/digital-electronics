package com.namirial.trust.electronics.amiga500

/**
 * Motorola MC68000 CPU — behavioral simulation (~95% instruction coverage).
 *
 * - 8 data registers (D0–D7): 32-bit
 * - 8 address registers (A0–A7): 32-bit (A7 = stack pointer)
 * - Program Counter: 32-bit (only 24 bits used on address bus)
 * - Status Register: 16-bit (system byte + CCR)
 * - Supervisor Stack Pointer (SSP) / User Stack Pointer (USP)
 */
class MC68000(private val bus: AddressBus) {

    val d = IntArray(8)
    val a = IntArray(8)
    var pc: Int = 0
    var sr: Int = 0x2700
    var ssp: Int = 0
    var usp: Int = 0
    var stopped = false
    var halted = false
    var cycles: Long = 0

    val ccr: Int get() = sr and 0x1F
    val supervisorMode: Boolean get() = (sr and 0x2000) != 0
    val interruptMask: Int get() = (sr shr 8) and 0x7

    private val FLAG_C = 0x01
    private val FLAG_V = 0x02
    private val FLAG_Z = 0x04
    private val FLAG_N = 0x08
    private val FLAG_X = 0x10

    fun flagC() = (sr and FLAG_C) != 0
    fun flagV() = (sr and FLAG_V) != 0
    fun flagZ() = (sr and FLAG_Z) != 0
    fun flagN() = (sr and FLAG_N) != 0
    fun flagX() = (sr and FLAG_X) != 0

    private fun setFlags(n: Boolean, z: Boolean, v: Boolean, c: Boolean, affectX: Boolean = true) {
        sr = (sr and (if (affectX) 0xFFE0.toInt() else 0xFFF0.toInt())) or
            (if (c) FLAG_C else 0) or
            (if (v) FLAG_V else 0) or
            (if (z) FLAG_Z else 0) or
            (if (n) FLAG_N else 0) or
            (if (affectX) (if (c) FLAG_X else 0) else (sr and FLAG_X))
    }

    private fun setCCR(value: Int) { sr = (sr and 0xFF00.toInt()) or (value and 0x1F) }
    private fun setSR(value: Int) {
        val wasSupervisor = (sr and 0x2000) != 0
        sr = value and 0xFFFF
        val nowSupervisor = (sr and 0x2000) != 0
        if (wasSupervisor && !nowSupervisor) { ssp = a[7]; a[7] = usp }
        else if (!wasSupervisor && nowSupervisor) { usp = a[7]; a[7] = ssp }
    }

    private fun readWord(addr: Int): Int {
        val a = addr and 0xFFFFFF
        if ((a and 1) != 0) { addressError(a, true); return 0 }
        return bus.readWord(a)
    }
    private fun readLong(addr: Int): Int {
        val a = addr and 0xFFFFFF
        if ((a and 1) != 0) { addressError(a, true); return 0 }
        return bus.readLong(a)
    }
    private fun writeWord(addr: Int, value: Int) {
        val a = addr and 0xFFFFFF
        if ((a and 1) != 0) { addressError(a, false); return }
        bus.writeWord(a, value)
    }
    private fun writeLong(addr: Int, value: Int) {
        val a = addr and 0xFFFFFF
        if ((a and 1) != 0) { addressError(a, false); return }
        bus.writeLong(a, value)
    }
    private fun readByte(addr: Int): Int = bus.readByte(addr and 0xFFFFFF)
    private fun writeByte(addr: Int, value: Int) = bus.writeByte(addr and 0xFFFFFF, value)

    private var inException = false
    private fun addressError(addr: Int, isRead: Boolean) {
        if (inException) { halted = true; return } // double fault
        inException = true
        exception(3)
        inException = false
    }

    // --- Prefetch queue (IRC register) ---
    // The 68000 has a 2-word prefetch. The key observable effect:
    // writes to memory at the address about to be fetched don't take effect
    // because the word is already in the prefetch buffer.
    private var irc: Int = 0        // Instruction Register Capture (next word to be consumed)
    private var prefetchValid = false

    private fun fetchWord(): Int {
        val w: Int
        if (prefetchValid) {
            w = irc
            irc = bus.readWord(pc and 0xFFFFFF)
            pc += 2
        } else {
            w = bus.readWord(pc and 0xFFFFFF)
            pc += 2
            irc = bus.readWord(pc and 0xFFFFFF)
            pc += 2
            prefetchValid = true
        }
        return w
    }

    private fun fetchLong(): Int { val hi = fetchWord(); val lo = fetchWord(); return (hi shl 16) or (lo and 0xFFFF) }

    /** Flush prefetch — called after any non-sequential PC change. */
    private fun flushPrefetch() { prefetchValid = false }

    private fun push(value: Int, size: Int) {
        when (size) {
            2 -> { a[7] -= 2; writeWord(a[7], value) }
            4 -> { a[7] -= 4; writeLong(a[7], value) }
        }
    }

    private fun pop(size: Int): Int = when (size) {
        2 -> { val v = readWord(a[7]); a[7] += 2; v }
        4 -> { val v = readLong(a[7]); a[7] += 4; v }
        else -> 0
    }

    fun reset() {
        ssp = bus.readLong(0x000000)
        pc = bus.readLong(0x000004)
        a[7] = ssp
        sr = 0x2700
        halted = false
        stopped = false
        cycles = 0
        prefetchValid = false
    }

    var instructionPC: Int = 0
        private set

    // --- Diagnostics ---
    /** Called after each instruction with (pc, opcode, d[], a[], sr). */
    var traceCallback: ((CpuState) -> Unit)? = null
    /** Called when an illegal/unimplemented opcode is encountered. Return true to suppress exception. */
    var illegalOpcodeCallback: ((Int, Int) -> Boolean)? = null
    /** Set of PC addresses that trigger a halt. */
    val breakpoints = mutableSetOf<Int>()
    /** Last executed opcode (for diagnostics). */
    var lastOpcode: Int = 0
        private set

    data class CpuState(
        val pc: Int, val opcode: Int,
        val d: IntArray, val a: IntArray,
        val sr: Int, val cycles: Long
    )

    fun step(): Boolean {
        if (halted) return false
        if (stopped) return true
        val traceEnabled = (sr and 0x8000) != 0
        instructionPC = pc - (if (prefetchValid) 2 else 0)
        // Breakpoint check
        if (instructionPC in breakpoints) { halted = true; return false }
        val opcode = fetchWord()
        lastOpcode = opcode
        decode(opcode)
        cycles++
        // Trace callback
        traceCallback?.invoke(CpuState(instructionPC, opcode, d.copyOf(), a.copyOf(), sr, cycles))
        if (!halted && traceEnabled) exception(9)
        return !halted
    }

    fun run(maxCycles: Long = 100_000): Long {
        var count = 0L
        while (!halted && count < maxCycles) {
            step()
            count++
        }
        return count
    }

    private fun exception(vector: Int) {
        if (inException && vector == 3) { halted = true; return }
        val wasInException = inException
        inException = true
        val oldSr = sr
        val wasSupervisor = (oldSr and 0x2000) != 0
        sr = (sr or 0x2000) and 0x7FFF.toInt()
        if (!wasSupervisor) { usp = a[7]; a[7] = ssp }
        // PC to push: for group 1/2 exceptions (bus/address error, illegal, etc.)
        // push the address of the faulting instruction; for others push next instruction
        val returnPC = when (vector) {
            2, 3, 4, 8, 10, 11 -> instructionPC // faulting instruction
            else -> pc - (if (prefetchValid) 2 else 0) // next instruction
        }
        push(returnPC, 4)
        push(oldSr, 2)
        pc = bus.readLong((vector * 4) and 0xFFFFFF)
        stopped = false
        flushPrefetch()
        inException = wasInException
    }

    private fun decode(opcode: Int) {
        when ((opcode shr 12) and 0xF) {
            0x0 -> decodeGroup0(opcode)
            0x1 -> decodeMove(opcode, 1)
            0x2 -> decodeMove(opcode, 4)
            0x3 -> decodeMove(opcode, 2)
            0x4 -> decodeGroup4(opcode)
            0x5 -> decodeGroup5(opcode)
            0x6 -> decodeBranch(opcode)
            0x7 -> decodeMoveQ(opcode)
            0x8 -> decodeGroup8(opcode)
            0x9 -> decodeGroup9(opcode)
            0xA -> triggerIllegal(opcode, 10)
            0xB -> decodeGroupB(opcode)
            0xC -> decodeGroupC(opcode)
            0xD -> decodeGroupD(opcode)
            0xE -> decodeShift(opcode)
            0xF -> triggerIllegal(opcode, 11)
        }
    }

    private fun triggerIllegal(opcode: Int, vector: Int) {
        if (illegalOpcodeCallback?.invoke(opcode, instructionPC) == true) return
        exception(vector)
    }

    // --- Effective Address resolution ---
    private fun eaRead(mode: Int, reg: Int, size: Int): Int = when (mode) {
        0 -> d[reg].sized(size)
        1 -> a[reg].sized(size)
        2 -> readSized(a[reg], size)
        3 -> { val v = readSized(a[reg], size); a[reg] += if (reg == 7 && size == 1) 2 else size; v }
        4 -> { a[reg] -= if (reg == 7 && size == 1) 2 else size; readSized(a[reg], size) }
        5 -> { val disp = fetchWord().toShort().toInt(); readSized(a[reg] + disp, size) }
        6 -> { val ext = fetchWord(); readSized(a[reg] + indexedDisp(ext), size) }
        7 -> when (reg) {
            0 -> { val addr = fetchWord().toShort().toInt(); readSized(addr, size) }
            1 -> { val addr = fetchLong(); readSized(addr, size) }
            2 -> { val disp = fetchWord().toShort().toInt(); readSized(pc - 2 + disp, size) }
            3 -> { val ext = fetchWord(); readSized(pc - 2 + indexedDisp(ext), size) }
            4 -> if (size == 4) fetchLong() else fetchWord().let { if (size == 1) it and 0xFF else it }
            else -> { exception(4); 0 }
        }
        else -> { exception(4); 0 }
    }

    private fun eaWrite(mode: Int, reg: Int, size: Int, value: Int) {
        when (mode) {
            0 -> d[reg] = d[reg].withSized(value, size)
            1 -> a[reg] = if (size == 2) value.toShort().toInt() else value
            2 -> writeSized(a[reg], size, value)
            3 -> { writeSized(a[reg], size, value); a[reg] += if (reg == 7 && size == 1) 2 else size }
            4 -> { a[reg] -= if (reg == 7 && size == 1) 2 else size; writeSized(a[reg], size, value) }
            5 -> { val disp = fetchWord().toShort().toInt(); writeSized(a[reg] + disp, size, value) }
            6 -> { val ext = fetchWord(); writeSized(a[reg] + indexedDisp(ext), size, value) }
            7 -> when (reg) {
                0 -> { val addr = fetchWord().toShort().toInt(); writeSized(addr, size, value) }
                1 -> { val addr = fetchLong(); writeSized(addr, size, value) }
                else -> exception(4)
            }
        }
    }

    private fun eaCalcAddr(mode: Int, reg: Int): Int = when (mode) {
        2 -> a[reg]
        3 -> a[reg] // for MOVEM (An)+
        5 -> { val disp = fetchWord().toShort().toInt(); a[reg] + disp }
        6 -> { val ext = fetchWord(); a[reg] + indexedDisp(ext) }
        7 -> when (reg) {
            0 -> fetchWord().toShort().toInt()
            1 -> fetchLong()
            2 -> { val disp = fetchWord().toShort().toInt(); pc - 2 + disp }
            3 -> { val ext = fetchWord(); pc - 2 + indexedDisp(ext) }
            else -> { exception(4); 0 }
        }
        else -> { exception(4); 0 }
    }

    private fun indexedDisp(ext: Int): Int {
        val disp = (ext and 0xFF).toByte().toInt()
        val idxReg = (ext shr 12) and 0x7
        val isAddr = (ext and 0x8000) != 0
        val long = (ext and 0x0800) != 0
        val idx = if (isAddr) a[idxReg] else d[idxReg]
        return disp + if (long) idx else idx.toShort().toInt()
    }

    private fun readSized(addr: Int, size: Int): Int = when (size) {
        1 -> readByte(addr); 2 -> readWord(addr); 4 -> readLong(addr); else -> 0
    }

    private fun writeSized(addr: Int, size: Int, value: Int) { when (size) {
        1 -> writeByte(addr, value); 2 -> writeWord(addr, value); 4 -> writeLong(addr, value)
    } }

    private fun Int.sized(size: Int): Int = when (size) {
        1 -> this and 0xFF; 2 -> this and 0xFFFF; else -> this
    }

    private fun Int.withSized(value: Int, size: Int): Int = when (size) {
        1 -> (this and 0xFFFFFF00.toInt()) or (value and 0xFF)
        2 -> (this and 0xFFFF0000.toInt()) or (value and 0xFFFF)
        else -> value
    }

    private fun Int.signExtByte(): Int = (this and 0xFF).toByte().toInt()
    private fun Int.signExtWord(): Int = (this and 0xFFFF).toShort().toInt()

    private fun sizeFromBits(bits: Int): Int = when (bits) { 0 -> 1; 1 -> 2; 2 -> 4; else -> 4 }
    private fun msbMask(size: Int): Int = when (size) { 1 -> 0x80; 2 -> 0x8000; else -> 0x80000000.toInt() }
    private fun sizeMask(size: Int): Int = when (size) { 1 -> 0xFF; 2 -> 0xFFFF; else -> -1 }

    // --- Group 0: Immediate ops, bit ops, MOVEP ---
    private fun decodeGroup0(opcode: Int) {
        if ((opcode and 0x0100) != 0) {
            // Dynamic bit ops (register) or MOVEP
            val bitReg = (opcode shr 9) and 0x7
            val mode = (opcode shr 3) and 0x7
            val reg = opcode and 0x7
            if (mode == 1) { // MOVEP
                decodeMovep(opcode); return
            }
            val bitNum = d[bitReg]
            when ((opcode shr 6) and 0x3) {
                0 -> doBtst(mode, reg, bitNum)
                1 -> doBchg(mode, reg, bitNum)
                2 -> doBclr(mode, reg, bitNum)
                3 -> doBset(mode, reg, bitNum)
            }
            return
        }
        val op = (opcode shr 9) and 0x7
        if (op == 4) { // Static bit ops (immediate bit number)
            val bitNum = fetchWord() and 0xFF
            val mode = (opcode shr 3) and 0x7
            val reg = opcode and 0x7
            when ((opcode shr 6) and 0x3) {
                0 -> doBtst(mode, reg, bitNum)
                1 -> doBchg(mode, reg, bitNum)
                2 -> doBclr(mode, reg, bitNum)
                3 -> doBset(mode, reg, bitNum)
            }
            return
        }
        // Immediate ALU: ORI, ANDI, SUBI, ADDI, EORI, CMPI
        val sizeBits = (opcode shr 6) and 0x3
        val size = sizeFromBits(sizeBits)
        val mode = (opcode shr 3) and 0x7
        val reg = opcode and 0x7
        // Check for ORI/ANDI/EORI to CCR/SR
        if (mode == 7 && reg == 4) {
            val imm = if (size == 4) fetchLong() else fetchWord().let { if (size == 1) it and 0xFF else it }
            when (op) {
                0 -> setCCR(ccr or imm)        // ORI to CCR
                1 -> setCCR(ccr and imm)       // ANDI to CCR
                5 -> setCCR(ccr xor imm)       // EORI to CCR
                else -> exception(4)
            }
            return
        }
        if (mode == 7 && reg == 4 && size == 2) {
            val imm = fetchWord()
            when (op) {
                0 -> setSR(sr or imm)          // ORI to SR
                1 -> setSR(sr and imm)         // ANDI to SR
                5 -> setSR(sr xor imm)         // EORI to SR
                else -> exception(4)
            }
            return
        }
        val imm = if (size == 4) fetchLong() else fetchWord().let { if (size == 1) it and 0xFF else it }
        val dst = eaRead(mode, reg, size)
        val result: Int
        when (op) {
            0 -> { result = dst or imm; eaWrite(mode, reg, size, result) }   // ORI
            1 -> { result = dst and imm; eaWrite(mode, reg, size, result) }  // ANDI
            2 -> { result = dst - imm; eaWrite(mode, reg, size, result) }    // SUBI
            3 -> { result = dst + imm; eaWrite(mode, reg, size, result) }    // ADDI
            5 -> { result = dst xor imm; eaWrite(mode, reg, size, result) }  // EORI
            6 -> { result = dst - imm }                                       // CMPI
            else -> { exception(4); return }
        }
        val masked = result and sizeMask(size)
        val msb = msbMask(size)
        val dstM = (dst and msb) != 0
        val srcM = (imm and msb) != 0
        val resM = (masked and msb) != 0
        val overflow = when (op) {
            2, 6 -> !dstM && srcM && resM || dstM && !srcM && !resM
            3 -> dstM && srcM && !resM || !dstM && !srcM && resM
            else -> false
        }
        val carry = when (op) {
            2, 6 -> (imm.toLong() and sizeMask(size).toLong()) > (dst.toLong() and sizeMask(size).toLong())
            3 -> (result.toLong() and 0xFFFFFFFFL) > sizeMask(size).toLong().let { if (it < 0) 0xFFFFFFFFL else it.toULong().toLong() }
            else -> false
        }
        setFlags(n = resM, z = masked == 0, v = overflow, c = carry)
    }

    private fun doBtst(mode: Int, reg: Int, bitNum: Int) {
        if (mode == 0) {
            val bit = bitNum and 31
            val z = (d[reg] and (1 shl bit)) == 0
            sr = (sr and FLAG_Z.inv()) or (if (z) FLAG_Z else 0)
        } else {
            val v = eaRead(mode, reg, 1)
            val bit = bitNum and 7
            val z = (v and (1 shl bit)) == 0
            sr = (sr and FLAG_Z.inv()) or (if (z) FLAG_Z else 0)
        }
    }

    private fun doBchg(mode: Int, reg: Int, bitNum: Int) {
        if (mode == 0) {
            val bit = bitNum and 31
            val z = (d[reg] and (1 shl bit)) == 0
            d[reg] = d[reg] xor (1 shl bit)
            sr = (sr and FLAG_Z.inv()) or (if (z) FLAG_Z else 0)
        } else {
            val v = eaRead(mode, reg, 1)
            val bit = bitNum and 7
            val z = (v and (1 shl bit)) == 0
            eaWrite(mode, reg, 1, v xor (1 shl bit))
            sr = (sr and FLAG_Z.inv()) or (if (z) FLAG_Z else 0)
        }
    }

    private fun doBclr(mode: Int, reg: Int, bitNum: Int) {
        if (mode == 0) {
            val bit = bitNum and 31
            val z = (d[reg] and (1 shl bit)) == 0
            d[reg] = d[reg] and (1 shl bit).inv()
            sr = (sr and FLAG_Z.inv()) or (if (z) FLAG_Z else 0)
        } else {
            val v = eaRead(mode, reg, 1)
            val bit = bitNum and 7
            val z = (v and (1 shl bit)) == 0
            eaWrite(mode, reg, 1, v and (1 shl bit).inv())
            sr = (sr and FLAG_Z.inv()) or (if (z) FLAG_Z else 0)
        }
    }

    private fun doBset(mode: Int, reg: Int, bitNum: Int) {
        if (mode == 0) {
            val bit = bitNum and 31
            val z = (d[reg] and (1 shl bit)) == 0
            d[reg] = d[reg] or (1 shl bit)
            sr = (sr and FLAG_Z.inv()) or (if (z) FLAG_Z else 0)
        } else {
            val v = eaRead(mode, reg, 1)
            val bit = bitNum and 7
            val z = (v and (1 shl bit)) == 0
            eaWrite(mode, reg, 1, v or (1 shl bit))
            sr = (sr and FLAG_Z.inv()) or (if (z) FLAG_Z else 0)
        }
    }

    private fun decodeMovep(opcode: Int) {
        val dReg = (opcode shr 9) and 0x7
        val aReg = opcode and 0x7
        val disp = fetchWord().toShort().toInt()
        val addr = a[aReg] + disp
        when ((opcode shr 6) and 0x7) {
            4 -> { // MOVEP.W (d,An) → Dn
                val hi = readByte(addr)
                val lo = readByte(addr + 2)
                d[dReg] = d[dReg].withSized((hi shl 8) or lo, 2)
            }
            5 -> { // MOVEP.L (d,An) → Dn
                val b3 = readByte(addr); val b2 = readByte(addr + 2)
                val b1 = readByte(addr + 4); val b0 = readByte(addr + 6)
                d[dReg] = (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
            }
            6 -> { // MOVEP.W Dn → (d,An)
                writeByte(addr, (d[dReg] shr 8) and 0xFF)
                writeByte(addr + 2, d[dReg] and 0xFF)
            }
            7 -> { // MOVEP.L Dn → (d,An)
                writeByte(addr, (d[dReg] shr 24) and 0xFF)
                writeByte(addr + 2, (d[dReg] shr 16) and 0xFF)
                writeByte(addr + 4, (d[dReg] shr 8) and 0xFF)
                writeByte(addr + 6, d[dReg] and 0xFF)
            }
        }
    }

    // --- MOVE ---
    private fun decodeMove(opcode: Int, size: Int) {
        val srcMode = (opcode shr 3) and 0x7
        val srcReg = opcode and 0x7
        val dstReg = (opcode shr 9) and 0x7
        val dstMode = (opcode shr 6) and 0x7
        val value = eaRead(srcMode, srcReg, size)
        if (dstMode == 1) { // MOVEA — no flags, sign-extend word to long
            a[dstReg] = if (size == 2) value.signExtWord() else value
        } else {
            eaWrite(dstMode, dstReg, size, value)
            val masked = value and sizeMask(size)
            setFlags(n = (masked and msbMask(size)) != 0, z = masked == 0, v = false, c = false)
        }
    }

    private fun decodeMoveQ(opcode: Int) {
        val reg = (opcode shr 9) and 0x7
        val data = (opcode and 0xFF).toByte().toInt()
        d[reg] = data
        setFlags(n = data < 0, z = data == 0, v = false, c = false)
    }

    // --- Group 4: Misc ---
    private fun decodeGroup4(opcode: Int) {
        when {
            opcode == 0x4AFC -> exception(4) // ILLEGAL
            opcode == 0x4E70 -> {}
            opcode == 0x4E71 -> {}
            opcode == 0x4E72 -> { val imm = fetchWord(); setSR(imm); stopped = true }
            opcode == 0x4E73 -> { val newSr = pop(2); pc = pop(4); setSR(newSr); flushPrefetch() }
            opcode == 0x4E75 -> { pc = pop(4); flushPrefetch() }
            opcode == 0x4E76 -> { if (flagV()) exception(7) }
            opcode == 0x4E77 -> { setCCR(pop(2)); pc = pop(4); flushPrefetch() }
            (opcode and 0xFFF8) == 0x4E50 -> { val r = opcode and 0x7; push(a[r], 4); a[r] = a[7]; a[7] += fetchWord().toShort().toInt() }
            (opcode and 0xFFF8) == 0x4E58 -> { val r = opcode and 0x7; a[7] = a[r]; a[r] = pop(4) }
            (opcode and 0xFFF8) == 0x4E60 -> { if (supervisorMode) usp = a[opcode and 0x7] else exception(8) }
            (opcode and 0xFFF8) == 0x4E68 -> { if (supervisorMode) a[opcode and 0x7] = usp else exception(8) }
            (opcode and 0xFFF0) == 0x4E40 -> exception(32 + (opcode and 0xF))
            (opcode and 0xFFF8) == 0x4840 -> { val r = opcode and 0x7; d[r] = ((d[r] ushr 16) and 0xFFFF) or ((d[r] and 0xFFFF) shl 16); setFlags(n = d[r] < 0, z = d[r] == 0, v = false, c = false) }
            (opcode and 0xFFF8) == 0x4880 -> { val r = opcode and 0x7; d[r] = d[r].withSized(d[r].signExtByte(), 2); val v = d[r] and 0xFFFF; setFlags(n = (v and 0x8000) != 0, z = v == 0, v = false, c = false) }
            (opcode and 0xFFF8) == 0x48C0 -> { val r = opcode and 0x7; d[r] = d[r].signExtWord(); setFlags(n = d[r] < 0, z = d[r] == 0, v = false, c = false) }
            (opcode and 0xFFC0) == 0x4AC0 -> { val m = (opcode shr 3) and 0x7; val r = opcode and 0x7; val v = eaRead(m, r, 1); setFlags(n = (v and 0x80) != 0, z = v == 0, v = false, c = false, affectX = false); eaWrite(m, r, 1, v or 0x80) }
            (opcode and 0xF1C0) == 0x4180 -> { val dr = (opcode shr 9) and 0x7; val m = (opcode shr 3) and 0x7; val r = opcode and 0x7; val b = eaRead(m, r, 2); val v = (d[dr] and 0xFFFF).toShort().toInt(); if (v < 0) { sr = sr or FLAG_N; exception(6) } else if (v > b) { sr = sr and FLAG_N.inv(); exception(6) } }
            (opcode and 0xFFC0) == 0x4E80 -> { val m = (opcode shr 3) and 0x7; val r = opcode and 0x7; val addr = eaCalcAddr(m, r); push(pc, 4); pc = addr; flushPrefetch() }
            (opcode and 0xFFC0) == 0x4EC0 -> { val m = (opcode shr 3) and 0x7; val r = opcode and 0x7; pc = eaCalcAddr(m, r); flushPrefetch() }
            (opcode and 0xF1C0) == 0x41C0 -> { val dr = (opcode shr 9) and 0x7; val m = (opcode shr 3) and 0x7; val r = opcode and 0x7; a[dr] = eaCalcAddr(m, r) }
            (opcode and 0xFB80) == 0x4880 -> decodeMovem(opcode)
            (opcode and 0xFFC0) == 0x40C0 -> { val m = (opcode shr 3) and 0x7; val r = opcode and 0x7; eaWrite(m, r, 2, sr) }
            (opcode and 0xFFC0) == 0x44C0 -> { val m = (opcode shr 3) and 0x7; val r = opcode and 0x7; setCCR(eaRead(m, r, 2)) }
            (opcode and 0xFFC0) == 0x46C0 -> { val m = (opcode shr 3) and 0x7; val r = opcode and 0x7; if (supervisorMode) setSR(eaRead(m, r, 2)) else exception(8) }
            (opcode and 0xFFC0) == 0x4800 -> { val m = (opcode shr 3) and 0x7; val r = opcode and 0x7; val v = eaRead(m, r, 1); val x = if (flagX()) 1 else 0; val res = (0 - v - x) and 0xFF; eaWrite(m, r, 1, res); if (res != 0) sr = sr and FLAG_Z.inv(); sr = (sr and (FLAG_C or FLAG_X).inv()) or (if (v != 0 || x != 0) FLAG_C or FLAG_X else 0) }
            (opcode and 0xFF00) == 0x4000 -> { val sz = sizeFromBits((opcode shr 6) and 0x3); val m = (opcode shr 3) and 0x7; val r = opcode and 0x7; val v = eaRead(m, r, sz); val x = if (flagX()) 1 else 0; val res = (0 - v - x) and sizeMask(sz); eaWrite(m, r, sz, res); val c = v != 0 || x != 0; if (res != 0) sr = sr and FLAG_Z.inv(); setFlags(n = (res and msbMask(sz)) != 0, z = flagZ() && res == 0, v = false, c = c) }
            (opcode and 0xFF00) == 0x4200 -> { val sz = sizeFromBits((opcode shr 6) and 0x3); val m = (opcode shr 3) and 0x7; val r = opcode and 0x7; eaWrite(m, r, sz, 0); setFlags(n = false, z = true, v = false, c = false) }
            (opcode and 0xFF00) == 0x4400 -> { val sz = sizeFromBits((opcode shr 6) and 0x3); val m = (opcode shr 3) and 0x7; val r = opcode and 0x7; val v = eaRead(m, r, sz); val res = (0 - v) and sizeMask(sz); eaWrite(m, r, sz, res); setFlags(n = (res and msbMask(sz)) != 0, z = res == 0, v = v == msbMask(sz), c = res != 0) }
            (opcode and 0xFF00) == 0x4600 -> { val sz = sizeFromBits((opcode shr 6) and 0x3); val m = (opcode shr 3) and 0x7; val r = opcode and 0x7; val v = eaRead(m, r, sz); val res = v.inv() and sizeMask(sz); eaWrite(m, r, sz, res); setFlags(n = (res and msbMask(sz)) != 0, z = res == 0, v = false, c = false) }
            (opcode and 0xFF00) == 0x4A00 -> { val sz = sizeFromBits((opcode shr 6) and 0x3); val m = (opcode shr 3) and 0x7; val r = opcode and 0x7; val v = eaRead(m, r, sz) and sizeMask(sz); setFlags(n = (v and msbMask(sz)) != 0, z = v == 0, v = false, c = false) }
            (opcode and 0xFFC0) == 0x4840 -> { val m = (opcode shr 3) and 0x7; val r = opcode and 0x7; push(eaCalcAddr(m, r), 4) } // PEA
            else -> triggerIllegal(opcode, 4)
        }
    }

    private fun decodeMovem(opcode: Int) {
        val toMem = (opcode and 0x0400) == 0
        val size = if ((opcode and 0x0040) != 0) 4 else 2
        val mode = (opcode shr 3) and 0x7
        val reg = opcode and 0x7
        val mask = fetchWord()
        if (toMem && mode == 4) {
            for (i in 15 downTo 0) {
                if ((mask and (1 shl (15 - i))) != 0) {
                    a[reg] -= size
                    val v = if (i < 8) d[i] else a[i - 8]
                    writeSized(a[reg], size, v)
                }
            }
        } else if (toMem) {
            var addr = eaCalcAddr(mode, reg)
            for (i in 0 until 16) { if ((mask and (1 shl i)) != 0) { writeSized(addr, size, if (i < 8) d[i] else a[i - 8]); addr += size } }
        } else {
            var addr = if (mode == 3) a[reg] else eaCalcAddr(mode, reg)
            for (i in 0 until 16) { if ((mask and (1 shl i)) != 0) { val v = readSized(addr, size); if (i < 8) d[i] = if (size == 2) v.signExtWord() else v else a[i - 8] = if (size == 2) v.signExtWord() else v; addr += size } }
            if (mode == 3) a[reg] = addr
        }
    }

    // --- Group 5: ADDQ, SUBQ, Scc, DBcc ---
    private fun decodeGroup5(opcode: Int) {
        val sizeBits = (opcode shr 6) and 0x3
        if (sizeBits == 3) {
            val mode = (opcode shr 3) and 0x7; val reg = opcode and 0x7; val cond = (opcode shr 8) and 0xF
            if (mode == 1) { val disp = fetchWord().toShort().toInt(); if (!evalCondition(cond)) { val cnt = (d[reg] and 0xFFFF) - 1; d[reg] = d[reg].withSized(cnt, 2); if ((cnt and 0xFFFF) != 0xFFFF) { pc += disp - 2; flushPrefetch() } } }
            else { eaWrite(mode, reg, 1, if (evalCondition(cond)) 0xFF else 0x00) }
            return
        }
        val data = ((opcode shr 9) and 0x7).let { if (it == 0) 8 else it }
        val size = sizeFromBits(sizeBits); val mode = (opcode shr 3) and 0x7; val reg = opcode and 0x7
        if ((opcode and 0x0100) == 0) {
            if (mode == 1) { a[reg] += data; return }
            val dst = eaRead(mode, reg, size); val result = dst + data; eaWrite(mode, reg, size, result)
            val masked = result and sizeMask(size); val msb = msbMask(size)
            setFlags(n = (masked and msb) != 0, z = masked == 0, v = ((dst and msb) == 0) && ((masked and msb) != 0), c = false)
        } else {
            if (mode == 1) { a[reg] -= data; return }
            val dst = eaRead(mode, reg, size); val result = dst - data; eaWrite(mode, reg, size, result)
            val masked = result and sizeMask(size); val msb = msbMask(size)
            setFlags(n = (masked and msb) != 0, z = masked == 0, v = false, c = (data.toLong() and sizeMask(size).toLong()) > (dst.toLong() and sizeMask(size).toLong()))
        }
    }

    // --- Group 6: Bcc, BRA, BSR ---
    private fun decodeBranch(opcode: Int) {
        val cond = (opcode shr 8) and 0xF
        val basePC = pc - 2 // PC of the extension word (displacement is relative to this)
        val disp8 = (opcode and 0xFF).toByte().toInt()
        val disp: Int
        if (disp8 == 0) { disp = fetchWord().toShort().toInt() }
        else if (disp8 == -1) { disp = fetchLong() }
        else { disp = disp8 }
        val target = basePC + disp
        when {
            cond == 0 -> { pc = target; flushPrefetch() }                    // BRA
            cond == 1 -> { push(pc, 4); pc = target; flushPrefetch() }       // BSR
            evalCondition(cond) -> { pc = target; flushPrefetch() }           // Bcc
        }
    }

    // --- Group D: ADD, ADDA, ADDX ---
    private fun decodeGroupD(opcode: Int) {
        val dReg = (opcode shr 9) and 0x7
        val mode = (opcode shr 3) and 0x7
        val reg = opcode and 0x7
        val opMode = (opcode shr 6) and 0x7
        when {
            opMode == 3 -> { // ADDA.W
                a[dReg] += eaRead(mode, reg, 2).signExtWord()
            }
            opMode == 7 -> { // ADDA.L
                a[dReg] += eaRead(mode, reg, 4)
            }
            (opMode == 4 || opMode == 5 || opMode == 6) && (mode == 0 || mode == 1) -> { // ADDX
                val size = sizeFromBits(opMode - 4)
                val x = if (flagX()) 1 else 0
                val src: Int; val dst: Int
                if (mode == 0) { src = d[reg].sized(size); dst = d[dReg].sized(size) }
                else { a[reg] -= size; src = readSized(a[reg], size); a[dReg] -= size; dst = readSized(a[dReg], size) }
                val result = (dst + src + x) and sizeMask(size)
                if (mode == 0) d[dReg] = d[dReg].withSized(result, size) else writeSized(a[dReg], size, result)
                val msb = msbMask(size)
                if (result != 0) sr = sr and FLAG_Z.inv()
                setFlags(n = (result and msb) != 0, z = flagZ() && result == 0,
                    v = ((dst xor result) and (src xor result) and msb) != 0,
                    c = ((dst.toLong() and sizeMask(size).toLong()) + (src.toLong() and sizeMask(size).toLong()) + x) > sizeMask(size).toLong().let { if (it < 0) 0xFFFFFFFFL else it.toULong().toLong() })
            }
            else -> { // ADD
                val size = sizeFromBits(opMode and 0x3)
                if ((opMode and 0x4) == 0) { // ADD <ea>,Dn
                    val src = eaRead(mode, reg, size)
                    val dst = d[dReg].sized(size)
                    val result = (dst + src) and sizeMask(size)
                    d[dReg] = d[dReg].withSized(result, size)
                    val msb = msbMask(size)
                    setFlags(n = (result and msb) != 0, z = result == 0,
                        v = ((dst xor result) and (src xor result) and msb) != 0,
                        c = ((dst.toLong() and sizeMask(size).toLong()) + (src.toLong() and sizeMask(size).toLong())) > sizeMask(size).toLong().let { if (it < 0) 0xFFFFFFFFL else it.toULong().toLong() })
                } else { // ADD Dn,<ea>
                    val dst = eaRead(mode, reg, size)
                    val src = d[dReg].sized(size)
                    val result = (dst + src) and sizeMask(size)
                    eaWrite(mode, reg, size, result)
                    val msb = msbMask(size)
                    setFlags(n = (result and msb) != 0, z = result == 0,
                        v = ((dst xor result) and (src xor result) and msb) != 0,
                        c = ((dst.toLong() and sizeMask(size).toLong()) + (src.toLong() and sizeMask(size).toLong())) > sizeMask(size).toLong().let { if (it < 0) 0xFFFFFFFFL else it.toULong().toLong() })
                }
            }
        }
    }

    // --- Group 9: SUB, SUBA, SUBX ---
    private fun decodeGroup9(opcode: Int) {
        val dReg = (opcode shr 9) and 0x7
        val mode = (opcode shr 3) and 0x7
        val reg = opcode and 0x7
        val opMode = (opcode shr 6) and 0x7
        when {
            opMode == 3 -> { // SUBA.W
                val src = eaRead(mode, reg, 2).signExtWord()
                a[dReg] -= src
            }
            opMode == 7 -> { // SUBA.L
                a[dReg] -= eaRead(mode, reg, 4)
            }
            (opMode == 4 || opMode == 5 || opMode == 6) && (mode == 0 || mode == 1) -> { // SUBX
                val size = sizeFromBits(opMode - 4)
                val x = if (flagX()) 1 else 0
                val src: Int; val dst: Int
                if (mode == 0) { src = d[reg].sized(size); dst = d[dReg].sized(size) }
                else { a[reg] -= size; src = readSized(a[reg], size); a[dReg] -= size; dst = readSized(a[dReg], size) }
                val result = (dst - src - x) and sizeMask(size)
                if (mode == 0) d[dReg] = d[dReg].withSized(result, size) else writeSized(a[dReg], size, result)
                val msb = msbMask(size)
                if (result != 0) sr = sr and FLAG_Z.inv()
                setFlags(n = (result and msb) != 0, z = flagZ() && result == 0,
                    v = ((dst xor src) and (dst xor result) and msb) != 0,
                    c = ((src.toLong() and sizeMask(size).toLong()) + x) > (dst.toLong() and sizeMask(size).toLong()))
            }
            else -> { // SUB
                val size = sizeFromBits(opMode and 0x3)
                if ((opMode and 0x4) == 0) { // SUB <ea>,Dn
                    val src = eaRead(mode, reg, size)
                    val dst = d[dReg].sized(size)
                    val result = (dst - src) and sizeMask(size)
                    d[dReg] = d[dReg].withSized(result, size)
                    val msb = msbMask(size)
                    setFlags(n = (result and msb) != 0, z = result == 0,
                        v = ((dst xor src) and (dst xor result) and msb) != 0,
                        c = (src.toLong() and sizeMask(size).toLong()) > (dst.toLong() and sizeMask(size).toLong()))
                } else { // SUB Dn,<ea>
                    val dst = eaRead(mode, reg, size)
                    val src = d[dReg].sized(size)
                    val result = (dst - src) and sizeMask(size)
                    eaWrite(mode, reg, size, result)
                    val msb = msbMask(size)
                    setFlags(n = (result and msb) != 0, z = result == 0,
                        v = ((dst xor src) and (dst xor result) and msb) != 0,
                        c = (src.toLong() and sizeMask(size).toLong()) > (dst.toLong() and sizeMask(size).toLong()))
                }
            }
        }
    }

    // --- Group B: CMP, CMPA, CMPM, EOR ---
    private fun decodeGroupB(opcode: Int) {
        val dReg = (opcode shr 9) and 0x7
        val mode = (opcode shr 3) and 0x7
        val reg = opcode and 0x7
        val opMode = (opcode shr 6) and 0x7
        when {
            opMode == 3 -> { // CMPA.W
                val src = eaRead(mode, reg, 2).signExtWord()
                val dst = a[dReg]
                val result = dst - src
                setFlags(n = result < 0, z = result == 0,
                    v = ((dst xor src) and (dst xor result) and 0x80000000.toInt()) != 0,
                    c = (src.toLong() and 0xFFFFFFFFL) > (dst.toLong() and 0xFFFFFFFFL), affectX = false)
            }
            opMode == 7 -> { // CMPA.L
                val src = eaRead(mode, reg, 4)
                val dst = a[dReg]
                val result = dst - src
                setFlags(n = result < 0, z = result == 0,
                    v = ((dst xor src) and (dst xor result) and 0x80000000.toInt()) != 0,
                    c = (src.toLong() and 0xFFFFFFFFL) > (dst.toLong() and 0xFFFFFFFFL), affectX = false)
            }
            (opMode == 4 || opMode == 5 || opMode == 6) && mode == 1 -> { // CMPM (Ay)+,(Ax)+
                val size = sizeFromBits(opMode - 4)
                val src = readSized(a[reg], size); a[reg] += size
                val dst = readSized(a[dReg], size); a[dReg] += size
                val result = (dst - src) and sizeMask(size)
                val msb = msbMask(size)
                setFlags(n = (result and msb) != 0, z = result == 0,
                    v = ((dst xor src) and (dst xor result) and msb) != 0,
                    c = (src.toLong() and sizeMask(size).toLong()) > (dst.toLong() and sizeMask(size).toLong()), affectX = false)
            }
            (opMode and 0x4) != 0 && mode != 1 -> { // EOR Dn,<ea>
                val size = sizeFromBits(opMode and 0x3)
                val dst = eaRead(mode, reg, size)
                val result = (dst xor d[dReg].sized(size)) and sizeMask(size)
                eaWrite(mode, reg, size, result)
                setFlags(n = (result and msbMask(size)) != 0, z = result == 0, v = false, c = false, affectX = false)
            }
            else -> { // CMP <ea>,Dn
                val size = sizeFromBits(opMode and 0x3)
                val src = eaRead(mode, reg, size)
                val dst = d[dReg].sized(size)
                val result = (dst - src) and sizeMask(size)
                val msb = msbMask(size)
                setFlags(n = (result and msb) != 0, z = result == 0,
                    v = ((dst xor src) and (dst xor result) and msb) != 0,
                    c = (src.toLong() and sizeMask(size).toLong()) > (dst.toLong() and sizeMask(size).toLong()), affectX = false)
            }
        }
    }

    // --- Group 8: OR, DIVU, DIVS, SBCD ---
    private fun decodeGroup8(opcode: Int) {
        val dReg = (opcode shr 9) and 0x7
        val mode = (opcode shr 3) and 0x7
        val reg = opcode and 0x7
        val opMode = (opcode shr 6) and 0x7
        when {
            opMode == 3 -> { // DIVU
                val src = eaRead(mode, reg, 2) and 0xFFFF
                if (src == 0) { exception(5); return }
                val dst = d[dReg].toLong() and 0xFFFFFFFFL
                val quot = (dst / src).toInt()
                val rem = (dst % src).toInt()
                if (quot > 0xFFFF) { setFlags(n = (quot and 0x8000) != 0, z = false, v = true, c = false, affectX = false); return }
                d[dReg] = ((rem and 0xFFFF) shl 16) or (quot and 0xFFFF)
                setFlags(n = (quot and 0x8000) != 0, z = (quot and 0xFFFF) == 0, v = false, c = false, affectX = false)
            }
            opMode == 7 -> { // DIVS
                val src = (eaRead(mode, reg, 2) and 0xFFFF).toShort().toInt()
                if (src == 0) { exception(5); return }
                val dst = d[dReg]
                val quot = dst / src
                val rem = dst % src
                if (quot < -32768 || quot > 32767) { setFlags(n = (quot and 0x8000) != 0, z = false, v = true, c = false, affectX = false); return }
                d[dReg] = ((rem and 0xFFFF) shl 16) or (quot and 0xFFFF)
                setFlags(n = (quot and 0x8000) != 0, z = (quot and 0xFFFF) == 0, v = false, c = false, affectX = false)
            }
            opMode == 4 && mode == 0 -> { // SBCD Dy,Dx (register)
                doSbcd(d[reg] and 0xFF, dReg, false, reg)
            }
            opMode == 4 && mode == 1 -> { // SBCD -(Ay),-(Ax)
                doSbcd(0, dReg, true, reg)
            }
            else -> { // OR
                val size = sizeFromBits(opMode and 0x3)
                if ((opMode and 0x4) == 0) { // OR <ea>,Dn
                    val src = eaRead(mode, reg, size)
                    val result = (d[dReg].sized(size) or src) and sizeMask(size)
                    d[dReg] = d[dReg].withSized(result, size)
                    setFlags(n = (result and msbMask(size)) != 0, z = result == 0, v = false, c = false, affectX = false)
                } else { // OR Dn,<ea>
                    val dst = eaRead(mode, reg, size)
                    val result = (dst or d[dReg].sized(size)) and sizeMask(size)
                    eaWrite(mode, reg, size, result)
                    setFlags(n = (result and msbMask(size)) != 0, z = result == 0, v = false, c = false, affectX = false)
                }
            }
        }
    }

    private fun doSbcd(srcVal: Int, dxReg: Int, mem: Boolean, syReg: Int) {
        val src: Int; val dst: Int
        if (mem) {
            a[syReg] -= 1; src = readByte(a[syReg])
            a[dxReg] -= 1; dst = readByte(a[dxReg])
        } else { src = srcVal; dst = d[dxReg] and 0xFF }
        val x = if (flagX()) 1 else 0
        var lo = (dst and 0xF) - (src and 0xF) - x
        var hi = ((dst shr 4) and 0xF) - ((src shr 4) and 0xF)
        if (lo < 0) { lo += 10; hi -= 1 }
        val c = hi < 0
        if (hi < 0) hi += 10
        val result = ((hi and 0xF) shl 4) or (lo and 0xF)
        if (mem) writeByte(a[dxReg], result) else d[dxReg] = d[dxReg].withSized(result, 1)
        if (result != 0) sr = sr and FLAG_Z.inv()
        sr = (sr and (FLAG_C or FLAG_X).inv()) or (if (c) FLAG_C or FLAG_X else 0)
    }

    // --- Group C: AND, MULU, MULS, EXG, ABCD ---
    private fun decodeGroupC(opcode: Int) {
        val dReg = (opcode shr 9) and 0x7
        val mode = (opcode shr 3) and 0x7
        val reg = opcode and 0x7
        val opMode = (opcode shr 6) and 0x7
        when {
            opMode == 3 -> { // MULU
                val src = (eaRead(mode, reg, 2) and 0xFFFF).toLong()
                val dst = (d[dReg] and 0xFFFF).toLong()
                d[dReg] = (src * dst).toInt()
                setFlags(n = d[dReg] < 0, z = d[dReg] == 0, v = false, c = false, affectX = false)
            }
            opMode == 7 -> { // MULS
                val src = (eaRead(mode, reg, 2) and 0xFFFF).toShort().toInt()
                val dst = (d[dReg] and 0xFFFF).toShort().toInt()
                d[dReg] = src * dst
                setFlags(n = d[dReg] < 0, z = d[dReg] == 0, v = false, c = false, affectX = false)
            }
            opMode == 5 && mode == 0 -> { // EXG Dx,Dy
                val tmp = d[dReg]; d[dReg] = d[reg]; d[reg] = tmp
            }
            opMode == 5 && mode == 1 -> { // EXG Ax,Ay
                val tmp = a[dReg]; a[dReg] = a[reg]; a[reg] = tmp
            }
            opMode == 6 && mode == 1 -> { // EXG Dx,Ay
                val tmp = d[dReg]; d[dReg] = a[reg]; a[reg] = tmp
            }
            opMode == 4 && mode == 0 -> { // ABCD Dy,Dx
                doAbcd(d[reg] and 0xFF, dReg, false, reg)
            }
            opMode == 4 && mode == 1 -> { // ABCD -(Ay),-(Ax)
                doAbcd(0, dReg, true, reg)
            }
            else -> { // AND
                val size = sizeFromBits(opMode and 0x3)
                if ((opMode and 0x4) == 0) { // AND <ea>,Dn
                    val src = eaRead(mode, reg, size)
                    val result = (d[dReg].sized(size) and src) and sizeMask(size)
                    d[dReg] = d[dReg].withSized(result, size)
                    setFlags(n = (result and msbMask(size)) != 0, z = result == 0, v = false, c = false, affectX = false)
                } else { // AND Dn,<ea>
                    val dst = eaRead(mode, reg, size)
                    val result = (dst and d[dReg].sized(size)) and sizeMask(size)
                    eaWrite(mode, reg, size, result)
                    setFlags(n = (result and msbMask(size)) != 0, z = result == 0, v = false, c = false, affectX = false)
                }
            }
        }
    }

    private fun doAbcd(srcVal: Int, dxReg: Int, mem: Boolean, syReg: Int) {
        val src: Int; val dst: Int
        if (mem) {
            a[syReg] -= 1; src = readByte(a[syReg])
            a[dxReg] -= 1; dst = readByte(a[dxReg])
        } else { src = srcVal; dst = d[dxReg] and 0xFF }
        val x = if (flagX()) 1 else 0
        var lo = (dst and 0xF) + (src and 0xF) + x
        var hi = ((dst shr 4) and 0xF) + ((src shr 4) and 0xF)
        if (lo > 9) { lo -= 10; hi += 1 }
        val c = hi > 9
        if (hi > 9) hi -= 10
        val result = ((hi and 0xF) shl 4) or (lo and 0xF)
        if (mem) writeByte(a[dxReg], result) else d[dxReg] = d[dxReg].withSized(result, 1)
        if (result != 0) sr = sr and FLAG_Z.inv()
        sr = (sr and (FLAG_C or FLAG_X).inv()) or (if (c) FLAG_C or FLAG_X else 0)
    }

    // --- Group E: ASL, ASR, LSL, LSR, ROL, ROR, ROXL, ROXR ---
    private fun decodeShift(opcode: Int) {
        val sizeBits = (opcode shr 6) and 0x3
        if (sizeBits == 3) {
            // Memory shift/rotate (1-bit, word size)
            val mode = (opcode shr 3) and 0x7
            val reg = opcode and 0x7
            val type = (opcode shr 9) and 0x3
            val dir = (opcode and 0x0100) != 0 // true = left
            val v = eaRead(mode, reg, 2) and 0xFFFF
            val result = doShiftOne(v, 2, type, dir)
            eaWrite(mode, reg, 2, result)
            return
        }
        // Register shift/rotate
        val reg = opcode and 0x7
        val dir = (opcode and 0x0100) != 0
        val size = sizeFromBits(sizeBits)
        val type = (opcode shr 3) and 0x3
        val count = if ((opcode and 0x0020) != 0) d[(opcode shr 9) and 0x7] and 0x3F
                    else ((opcode shr 9) and 0x7).let { if (it == 0) 8 else it }
        var value = d[reg].sized(size)
        var overflow = false
        repeat(count) {
            value = doShiftOne(value, size, type, dir)
            if ((sr and FLAG_V) != 0) overflow = true
        }
        d[reg] = d[reg].withSized(value, size)
        if (count == 0) {
            setFlags(n = (value and msbMask(size)) != 0, z = value == 0, v = false, c = false, affectX = false)
        } else if (type == 0 && dir) {
            // ASL: V is set if MSB changed at ANY point during the shift
            sr = (sr and FLAG_V.inv()) or (if (overflow) FLAG_V else 0)
        }
    }

    private fun doShiftOne(value: Int, size: Int, type: Int, left: Boolean): Int {
        val msb = msbMask(size)
        val mask = sizeMask(size)
        val bits = size * 8
        val result: Int
        var c = false
        var v = false
        when (type) {
            0 -> { // ASR / ASL
                if (left) {
                    c = (value and msb) != 0
                    result = (value shl 1) and mask
                    v = (result and msb) != (value and msb)
                } else {
                    c = (value and 1) != 0
                    result = ((value and mask) ushr 1) or (value and msb) // sign extend
                }
            }
            1 -> { // LSR / LSL
                if (left) {
                    c = (value and msb) != 0
                    result = (value shl 1) and mask
                } else {
                    c = (value and 1) != 0
                    result = (value and mask) ushr 1
                }
            }
            2 -> { // ROXR / ROXL
                val x = if (flagX()) 1 else 0
                if (left) {
                    c = (value and msb) != 0
                    result = ((value shl 1) and mask) or x
                } else {
                    c = (value and 1) != 0
                    result = ((value and mask) ushr 1) or (x * msb)
                }
                sr = (sr and FLAG_X.inv()) or (if (c) FLAG_X else 0)
            }
            3 -> { // ROR / ROL
                if (left) {
                    c = (value and msb) != 0
                    result = ((value shl 1) and mask) or (if (c) 1 else 0)
                } else {
                    c = (value and 1) != 0
                    result = ((value and mask) ushr 1) or (if (c) msb else 0)
                }
            }
            else -> { result = value }
        }
        val resN = (result and msb) != 0
        sr = (sr and 0xFFE0.toInt()) or
            (if (c) FLAG_C else 0) or
            (if (v) FLAG_V else 0) or
            (if (result == 0) FLAG_Z else 0) or
            (if (resN) FLAG_N else 0) or
            (if (type != 3) (if (c) FLAG_X else (sr and FLAG_X)) else (sr and FLAG_X))
        return result
    }

    private fun evalCondition(cond: Int): Boolean = when (cond) {
        0 -> true                                    // T
        1 -> false                                   // F
        2 -> !flagC() && !flagZ()                    // HI
        3 -> flagC() || flagZ()                      // LS
        4 -> !flagC()                                // CC/HS
        5 -> flagC()                                 // CS/LO
        6 -> !flagZ()                                // NE
        7 -> flagZ()                                 // EQ
        8 -> !flagV()                                // VC
        9 -> flagV()                                 // VS
        10 -> !flagN()                               // PL
        11 -> flagN()                                // MI
        12 -> flagN() == flagV()                     // GE
        13 -> flagN() != flagV()                     // LT
        14 -> !flagZ() && (flagN() == flagV())       // GT
        15 -> flagZ() || (flagN() != flagV())        // LE
        else -> false
    }
}
