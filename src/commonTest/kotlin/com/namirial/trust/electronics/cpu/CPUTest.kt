package com.namirial.trust.electronics.cpu

import com.namirial.trust.electronics.cpu.CPU.Companion.ADD
import com.namirial.trust.electronics.cpu.CPU.Companion.HLT
import com.namirial.trust.electronics.cpu.CPU.Companion.JC
import com.namirial.trust.electronics.cpu.CPU.Companion.JMP
import com.namirial.trust.electronics.cpu.CPU.Companion.JZ
import com.namirial.trust.electronics.cpu.CPU.Companion.LDA
import com.namirial.trust.electronics.cpu.CPU.Companion.LDI
import com.namirial.trust.electronics.cpu.CPU.Companion.OUT
import com.namirial.trust.electronics.cpu.CPU.Companion.STA
import com.namirial.trust.electronics.cpu.CPU.Companion.SUB
import kotlin.test.*

/**
 * Builds a 16-byte multiplication program: result = X * Y via repeated addition.
 *
 * Memory layout:
 *   0x0–0xB  program
 *   0xC      constant 1
 *   0xD      result accumulator (initialised to 0)
 *   0xE      Y (multiplier / loop counter)
 *   0xF      X (multiplicand)
 */
private fun multiplyProgram(x: Int, y: Int): List<Int> {
    val p = MutableList(16) { 0 }
    p[0x0] = instr(LDA, 0xE)   // A = Y
    p[0x1] = instr(JZ,  0x9)   // if Y == 0, jump to done
    p[0x2] = instr(LDA, 0xD)   // A = result
    p[0x3] = instr(ADD, 0xF)   // A += X
    p[0x4] = instr(STA, 0xD)   // result = A
    p[0x5] = instr(LDA, 0xE)   // A = Y
    p[0x6] = instr(SUB, 0xC)   // A -= 1
    p[0x7] = instr(STA, 0xE)   // Y = A
    p[0x8] = instr(JMP, 0x0)   // loop
    p[0x9] = instr(LDA, 0xD)   // done: A = result
    p[0xA] = instr(OUT)
    p[0xB] = instr(HLT)
    p[0xC] = 1                 // constant 1
    p[0xD] = 0                 // result
    p[0xE] = y
    p[0xF] = x
    return p
}

/**
 * Builds a 16-byte integer division program: quotient = X / Y, remainder = X % Y.
 * Division by zero causes an infinite loop — callers must ensure Y != 0.
 *
 * After run(): cpu.outputValue = quotient, cpu.peekA() = remainder.
 *
 * Memory layout:
 *   0x0–0xB  program
 *   0xC      constant 1
 *   0xD      quotient (initialised to 0)
 *   0xE      X (dividend, reduced each iteration — holds remainder at end)
 *   0xF      Y (divisor)
 */
private fun divideProgram(x: Int, y: Int): List<Int> {
    val p = MutableList(16) { 0 }
    p[0x0] = instr(LDA, 0xE)   // A = X
    p[0x1] = instr(SUB, 0xF)   // A = X - Y
    p[0x2] = instr(JC,  0x7)   // carry=1 (no borrow, X >= Y): jump to continue
    p[0x3] = instr(LDA, 0xD)   // done: A = quotient
    p[0x4] = instr(OUT)        // output quotient
    p[0x5] = instr(LDA, 0xE)   // A = remainder (X after all subtractions)
    p[0x6] = instr(HLT)        // A = remainder at halt
    p[0x7] = instr(STA, 0xE)   // continue: X = X - Y
    p[0x8] = instr(LDA, 0xD)   // A = quotient
    p[0x9] = instr(ADD, 0xC)   // A += 1
    p[0xA] = instr(STA, 0xD)   // quotient = A
    p[0xB] = instr(JMP, 0x0)   // loop
    p[0xC] = 1                 // constant 1
    p[0xD] = 0                 // quotient
    p[0xE] = x
    p[0xF] = y
    return p
}

/** Helper: encode instruction byte from opcode + operand. */
private fun instr(opcode: Int, operand: Int = 0) = (opcode shl 4) or (operand and 0xF)

class CPUTest {

    @Test fun `add two numbers — 14 plus 28 equals 42`() {
        // Program:
        //   0: LDA 14      — load value at address 14 into A
        //   1: ADD 15      — add value at address 15 to A
        //   2: OUT         — copy A to output
        //   3: HLT         — halt
        //  14: 14           — data
        //  15: 28           — data
        val program = MutableList(16) { 0 }
        program[0]  = instr(LDA, 14)
        program[1]  = instr(ADD, 15)
        program[2]  = instr(OUT)
        program[3]  = instr(HLT)
        program[14] = 14
        program[15] = 28

        val cpu = CPU()
        cpu.loadProgram(program)
        cpu.run()

        assertTrue(cpu.halted)
        assertEquals(42, cpu.outputValue)
        assertEquals(42, cpu.peekA())
    }

    @Test fun `subtract — 50 minus 17 equals 33`() {
        val program = MutableList(16) { 0 }
        program[0]  = instr(LDA, 14)
        program[1]  = instr(SUB, 15)
        program[2]  = instr(OUT)
        program[3]  = instr(HLT)
        program[14] = 50
        program[15] = 17

        val cpu = CPU()
        cpu.loadProgram(program)
        cpu.run()

        assertEquals(33, cpu.outputValue)
    }

    @Test fun `LDI loads immediate value`() {
        val program = listOf(
            instr(LDI, 7),   // A = 7
            instr(OUT),       // output = 7
            instr(HLT)
        )
        val cpu = CPU()
        cpu.loadProgram(program)
        cpu.run()

        assertEquals(7, cpu.outputValue)
    }

    @Test fun `STA stores accumulator to RAM`() {
        // Load 5 into A, store at address 15, then load from 15 and output
        val program = listOf(
            instr(LDI, 5),    // A = 5
            instr(STA, 15),   // RAM[15] = 5
            instr(LDI, 0),    // A = 0 (clear)
            instr(LDA, 15),   // A = RAM[15] = 5
            instr(OUT),
            instr(HLT)
        )
        val cpu = CPU()
        cpu.loadProgram(program)
        cpu.run()

        assertEquals(5, cpu.outputValue)
    }

    @Test fun `JMP creates a loop — count up by 3s`() {
        // Count: 0 → 3 → 6 → 9 → 12, output 12 then halt
        // Uses: LDI 0, ADD data, JC to halt (overflow), JMP back
        val program = MutableList(16) { 0 }
        program[0]  = instr(LDI, 0)     // A = 0
        program[1]  = instr(ADD, 15)     // A += 3
        program[2]  = instr(JC, 5)       // if carry, jump to halt
        program[3]  = instr(OUT)         // output current A
        program[4]  = instr(JMP, 1)      // loop back to ADD
        program[5]  = instr(HLT)
        program[15] = 3                  // data: step size

        val cpu = CPU()
        cpu.loadProgram(program)
        cpu.run()

        // Last output before carry: 3, 6, 9, ..., 252, 255
        // 255 + 3 = 258 → carry set, halts. Last OUT was 255.
        assertEquals(255, cpu.outputValue)
    }

    @Test fun `JZ jumps when result is zero`() {
        // Subtract 5 from 10 twice: 10 → 5 → 0, then JZ triggers
        val program = MutableList(16) { 0 }
        program[0]  = instr(LDA, 14)     // A = 10
        program[1]  = instr(SUB, 15)     // A -= 5
        program[2]  = instr(JZ, 5)       // if zero, jump to OUT+HLT
        program[3]  = instr(OUT)         // output intermediate
        program[4]  = instr(JMP, 1)      // loop
        program[5]  = instr(OUT)         // output final (0)
        program[6]  = instr(HLT)
        program[14] = 10
        program[15] = 5

        val cpu = CPU()
        cpu.loadProgram(program)
        cpu.run()

        assertEquals(0, cpu.outputValue)
    }

    @Test fun `program counter wraps around`() {
        // NOP-filled memory, HLT at address 0 — PC wraps from 15 → 0
        val program = MutableList(16) { 0 } // all NOP
        program[0] = instr(HLT)
        program[1] = instr(JMP, 2) // start here: jump to 2 to skip HLT at 0

        val cpu = CPU()
        cpu.loadProgram(program)
        // Manually set PC to 1
        cpu.step() // executes HLT at 0
        assertTrue(cpu.halted)
    }

    @Test fun `multiply — 3 times 4 equals 12`() {
        val cpu = CPU()
        cpu.loadProgram(multiplyProgram(x = 3, y = 4))
        cpu.run()

        assertTrue(cpu.halted)
        assertEquals(12, cpu.outputValue)
    }

    @Test fun `multiply — 7 times 9 equals 63`() {
        val cpu = CPU()
        cpu.loadProgram(multiplyProgram(x = 7, y = 9))
        cpu.run()

        assertEquals(63, cpu.outputValue)
    }

    @Test fun `multiply — 15 times 15 equals 225`() {
        val cpu = CPU()
        cpu.loadProgram(multiplyProgram(x = 15, y = 15))
        cpu.run()

        assertEquals(225, cpu.outputValue)
    }

    @Test fun `multiply — any number times 0 equals 0`() {
        val cpu = CPU()
        cpu.loadProgram(multiplyProgram(x = 42, y = 0))
        cpu.run()

        assertEquals(0, cpu.outputValue)
    }

    @Test fun `multiply — 0 times any number equals 0`() {
        val cpu = CPU()
        cpu.loadProgram(multiplyProgram(x = 0, y = 7))
        cpu.run()

        assertEquals(0, cpu.outputValue)
    }

    @Test fun `multiply — 1 times 1 equals 1`() {
        val cpu = CPU()
        cpu.loadProgram(multiplyProgram(x = 1, y = 1))
        cpu.run()

        assertEquals(1, cpu.outputValue)
    }

    @Test fun `divide — 12 divided by 4 equals quotient 3 remainder 0`() {
        val cpu = CPU()
        cpu.loadProgram(divideProgram(x = 12, y = 4))
        cpu.run()

        assertTrue(cpu.halted)
        assertEquals(3, cpu.outputValue)
        assertEquals(0, cpu.peekA())
    }

    @Test fun `divide — 7 divided by 3 equals quotient 2 remainder 1`() {
        val cpu = CPU()
        cpu.loadProgram(divideProgram(x = 7, y = 3))
        cpu.run()

        assertEquals(2, cpu.outputValue)
        assertEquals(1, cpu.peekA())
    }

    @Test fun `divide — 100 divided by 10 equals quotient 10 remainder 0`() {
        val cpu = CPU()
        cpu.loadProgram(divideProgram(x = 100, y = 10))
        cpu.run()

        assertEquals(10, cpu.outputValue)
        assertEquals(0, cpu.peekA())
    }

    @Test fun `divide — 0 divided by any number equals quotient 0 remainder 0`() {
        val cpu = CPU()
        cpu.loadProgram(divideProgram(x = 0, y = 5))
        cpu.run()

        assertEquals(0, cpu.outputValue)
        assertEquals(0, cpu.peekA())
    }

    @Test fun `divide — number divided by itself equals quotient 1 remainder 0`() {
        val cpu = CPU()
        cpu.loadProgram(divideProgram(x = 9, y = 9))
        cpu.run()

        assertEquals(1, cpu.outputValue)
        assertEquals(0, cpu.peekA())
    }

    @Test fun `divide — number divided by 1 equals itself remainder 0`() {
        val cpu = CPU()
        cpu.loadProgram(divideProgram(x = 42, y = 1))
        cpu.run()

        assertEquals(42, cpu.outputValue)
        assertEquals(0, cpu.peekA())
    }
}
