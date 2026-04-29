package com.namirial.trust.electronics.cpu

import com.namirial.trust.electronics.core.*
import com.namirial.trust.electronics.combinational.ALU
import com.namirial.trust.electronics.clock.ManualClock
import com.namirial.trust.electronics.sequential.*

/**
 * SAP-1 (Simple As Possible) 8-bit microprocessor.
 *
 * Architecture:
 * - 8-bit data bus, 4-bit address bus (16 bytes of RAM)
 * - Instruction format: [XXXX][YYYY] — 4-bit opcode + 4-bit operand
 * - Registers: A (accumulator), B (operand), IR (instruction), OUT (output)
 * - Program Counter: 4-bit (addresses 0–15)
 * - ALU: 8-bit add/subtract with carry and zero flags
 *
 * Instruction set:
 * | Opcode | Mnemonic | Description                        |
 * |--------|----------|------------------------------------|
 * | 0000   | NOP      | No operation                       |
 * | 0001   | LDA addr | Load RAM[addr] into A              |
 * | 0010   | ADD addr | A = A + RAM[addr]                  |
 * | 0011   | SUB addr | A = A - RAM[addr]                  |
 * | 0100   | STA addr | Store A into RAM[addr]             |
 * | 0101   | LDI val  | Load immediate 4-bit value into A  |
 * | 0110   | JMP addr | Jump to address                    |
 * | 0111   | JC  addr | Jump if carry flag set             |
 * | 1000   | JZ  addr | Jump if zero flag set              |
 * | 1110   | OUT      | Copy A to output register          |
 * | 1111   | HLT      | Halt execution                     |
 */
class CPU {

    // --- Opcodes ---
    companion object {
        const val NOP = 0x0
        const val LDA = 0x1
        const val ADD = 0x2
        const val SUB = 0x3
        const val STA = 0x4
        const val LDI = 0x5
        const val JMP = 0x6
        const val JC  = 0x7
        const val JZ  = 0x8
        const val OUT = 0xE
        const val HLT = 0xF
    }

    // --- Internal state (registers as LatchSignals, like real flip-flops) ---
    private val regA = IntArray(8) { 0 }       // Accumulator
    private val regB = IntArray(8) { 0 }       // B register
    private var pc = 0                          // Program counter (4-bit)
    private var flagCarry = false
    private var flagZero = false
    var halted = false
        private set

    var outputValue: Int = 0                    // Output register
        private set

    // --- RAM: 16 bytes, signal-based using existing RAM component ---
    private val addressInputs = List(4) { Input() }
    private val dataInputs = List(8) { Input() }
    private val writeEnable = Input()
    private val ram = RAM(addressInputs, dataInputs, writeEnable, addressBits = 4)

    // --- ALU: wired from register signals ---
    private val aluInputA = List(8) { Input() }
    private val aluInputB = List(8) { Input() }
    private val aluSubtract = Input()
    private val alu = ALU(aluInputA, aluInputB, aluSubtract)

    // --- Clock ---
    val clock = ManualClock()

    /**
     * Load a program into RAM. Each byte is an instruction or data.
     * Index = address (0–15).
     */
    fun loadProgram(program: List<Int>) {
        require(program.size <= 16) { "Program too large for 16-byte RAM" }
        writeEnable.value = true
        program.forEachIndexed { addr, byte ->
            setAddress(addr)
            setDataIn(byte)
            ram.clock()
        }
        writeEnable.value = false
    }

    /**
     * Execute one full instruction cycle (fetch → decode → execute).
     * Returns false if halted.
     */
    fun step(): Boolean {
        if (halted) return false

        // --- FETCH ---
        setAddress(pc)
        val instruction = readRam()
        val opcode = (instruction shr 4) and 0xF
        val operand = instruction and 0xF

        // Advance PC (before execute, so jumps can override)
        pc = (pc + 1) and 0xF

        // --- DECODE & EXECUTE ---
        when (opcode) {
            NOP -> { /* nothing */ }

            LDA -> {
                setAddress(operand)
                storeToRegA(readRam())
            }

            ADD -> {
                setAddress(operand)
                storeToRegB(readRam())
                runALU(subtract = false)
            }

            SUB -> {
                setAddress(operand)
                storeToRegB(readRam())
                runALU(subtract = true)
            }

            STA -> {
                writeEnable.value = true
                setAddress(operand)
                setDataIn(regAValue())
                ram.clock()
                writeEnable.value = false
            }

            LDI -> {
                storeToRegA(operand)
            }

            JMP -> {
                pc = operand
            }

            JC -> {
                if (flagCarry) pc = operand
            }

            JZ -> {
                if (flagZero) pc = operand
            }

            OUT -> {
                outputValue = regAValue()
            }

            HLT -> {
                halted = true
            }
        }

        return !halted
    }

    /**
     * Run until HLT or [maxCycles] reached. Returns the number of cycles executed.
     */
    fun run(maxCycles: Int = 1000): Int {
        var cycles = 0
        while (step()) {
            cycles++
            if (cycles >= maxCycles) break
        }
        return cycles
    }

    // --- Helpers: bridge between register arrays and Signal-based components ---

    private fun setAddress(addr: Int) {
        addressInputs.forEachIndexed { i, input -> input.value = (addr shr i) and 1 == 1 }
    }

    private fun setDataIn(data: Int) {
        dataInputs.forEachIndexed { i, input -> input.value = (data shr i) and 1 == 1 }
    }

    private fun readRam(): Int = ram.dataOut.foldIndexed(0) { i, acc, s ->
        acc or (if (s.value) (1 shl i) else 0)
    }

    private fun regAValue(): Int = regA.foldIndexed(0) { i, acc, bit ->
        acc or (bit shl i)
    }

    private fun storeToRegA(value: Int) {
        for (i in 0 until 8) regA[i] = (value shr i) and 1
    }

    private fun storeToRegB(value: Int) {
        for (i in 0 until 8) regB[i] = (value shr i) and 1
    }

    private fun runALU(subtract: Boolean) {
        // Feed A and B into ALU inputs
        for (i in 0 until 8) {
            aluInputA[i].value = regA[i] == 1
            aluInputB[i].value = regB[i] == 1
        }
        aluSubtract.value = subtract

        // Read result from ALU (combinational — instant)
        val result = alu.result.foldIndexed(0) { i, acc, s ->
            acc or (if (s.value) (1 shl i) else 0)
        }
        flagCarry = alu.carryOut.value
        flagZero = alu.zero.value

        storeToRegA(result)
    }

    /** Peek at register A (for testing). */
    fun peekA(): Int = regAValue()

    /** Peek at program counter (for testing). */
    fun peekPC(): Int = pc

    /** Peek at flags (for testing). */
    fun peekFlags(): Pair<Boolean, Boolean> = flagCarry to flagZero
}
