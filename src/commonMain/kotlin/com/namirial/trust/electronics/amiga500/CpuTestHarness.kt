package com.namirial.trust.electronics.amiga500

/**
 * CPU Test Harness — runs isolated instruction sequences and verifies results.
 *
 * Usage:
 * ```
 * val harness = CpuTestHarness()
 * harness.setReg("D0", 0x12345678)
 * harness.setReg("A0", 0x1000)
 * harness.poke(0x1000, 0xABCD) // word at address
 * harness.run(0x7000, 0xD041)  // ADD.W D1, D0 at address $7000
 * harness.assertReg("D0", 0x12345678 + expectedD1)
 * harness.assertFlags(n = false, z = false, v = false, c = false)
 * ```
 */
class CpuTestHarness {

    private val bus = AddressBus()
    val cpu: MC68000
    val errors = mutableListOf<String>()

    init {
        bus.ciaA = CIA8520()
        bus.ciaA!!.pra = 0  // OVL off so we can use low memory
        cpu = MC68000(bus)
        cpu.sr = 0x2000 // supervisor mode, no interrupts masked, no flags
    }

    /** Set a register by name: D0–D7, A0–A7, SR, PC. */
    fun setReg(name: String, value: Int) {
        when {
            name.startsWith("D") -> cpu.d[name.substring(1).toInt()] = value
            name.startsWith("A") -> cpu.a[name.substring(1).toInt()] = value
            name == "SR" -> cpu.sr = value
            name == "PC" -> cpu.pc = value
            name == "SSP" -> cpu.ssp = value
            name == "USP" -> cpu.usp = value
        }
    }

    /** Get a register by name. */
    fun getReg(name: String): Int = when {
        name.startsWith("D") -> cpu.d[name.substring(1).toInt()]
        name.startsWith("A") -> cpu.a[name.substring(1).toInt()]
        name == "SR" -> cpu.sr
        name == "PC" -> cpu.pc
        else -> 0
    }

    /** Write a word to memory. */
    fun poke(addr: Int, word: Int) { bus.writeWord(addr, word) }

    /** Write a long to memory. */
    fun pokeLong(addr: Int, value: Int) { bus.writeLong(addr, value) }

    /** Read a word from memory. */
    fun peek(addr: Int): Int = bus.readWord(addr)

    /** Read a long from memory. */
    fun peekLong(addr: Int): Int = bus.readLong(addr)

    /**
     * Place instruction words at [addr], set PC there, and execute [count] instructions.
     * Returns the CpuState after execution.
     */
    fun run(addr: Int, vararg opcodes: Int, count: Int = 1): MC68000.CpuState? {
        var offset = addr
        for (op in opcodes) { bus.writeWord(offset, op); offset += 2 }
        // Place a Line-F trap after the instruction to halt
        bus.writeWord(offset, 0xFFFF)
        cpu.pc = addr
        cpu.halted = false
        // Reset prefetch
        cpu.illegalOpcodeCallback = { _, _ -> cpu.halted = true; true }
        var lastState: MC68000.CpuState? = null
        cpu.traceCallback = { lastState = it }
        repeat(count) { if (!cpu.halted) cpu.step() }
        cpu.traceCallback = null
        cpu.illegalOpcodeCallback = null
        return lastState
    }

    /** Assert a register has the expected value. */
    fun assertReg(name: String, expected: Int): Boolean {
        val actual = getReg(name)
        if (actual != expected) {
            errors.add("$name: expected 0x${expected.toUInt().toString(16)} got 0x${actual.toUInt().toString(16)}")
            return false
        }
        return true
    }

    /** Assert memory word at addr. */
    fun assertMem(addr: Int, expected: Int): Boolean {
        val actual = peek(addr)
        if (actual != expected) {
            errors.add("[$${addr.toString(16)}]: expected 0x${expected.toString(16)} got 0x${actual.toString(16)}")
            return false
        }
        return true
    }

    /** Assert CCR flags. Pass null for "don't care". */
    fun assertFlags(c: Boolean? = null, v: Boolean? = null, z: Boolean? = null, n: Boolean? = null, x: Boolean? = null): Boolean {
        var ok = true
        val sr = cpu.sr
        if (c != null && ((sr and 0x01) != 0) != c) { errors.add("C flag: expected $c"); ok = false }
        if (v != null && ((sr and 0x02) != 0) != v) { errors.add("V flag: expected $v"); ok = false }
        if (z != null && ((sr and 0x04) != 0) != z) { errors.add("Z flag: expected $z"); ok = false }
        if (n != null && ((sr and 0x08) != 0) != n) { errors.add("N flag: expected $n"); ok = false }
        if (x != null && ((sr and 0x10) != 0) != x) { errors.add("X flag: expected $x"); ok = false }
        return ok
    }

    /** Reset state for next test. */
    fun reset() {
        cpu.d.fill(0)
        cpu.a.fill(0)
        cpu.sr = 0x2000
        cpu.pc = 0
        cpu.halted = false
        cpu.cycles = 0
        errors.clear()
    }

    /** Run a batch of test cases. Returns (passed, failed, errors). */
    fun runBatch(tests: List<TestCase>): TestResult {
        var passed = 0; var failed = 0
        val allErrors = mutableListOf<String>()
        for (test in tests) {
            reset()
            test.setup(this)
            run(test.addr, *test.opcodes, count = test.instructionCount)
            errors.clear()
            test.verify(this)
            if (errors.isEmpty()) passed++ else {
                failed++
                allErrors.add("FAIL [${test.name}]: ${errors.joinToString("; ")}")
            }
        }
        return TestResult(passed, failed, allErrors)
    }

    data class TestCase(
        val name: String,
        val addr: Int = 0x1000,
        val opcodes: IntArray,
        val instructionCount: Int = 1,
        val setup: (CpuTestHarness) -> Unit,
        val verify: (CpuTestHarness) -> Unit
    )

    data class TestResult(val passed: Int, val failed: Int, val errors: List<String>)
}
