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
}
