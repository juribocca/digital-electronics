# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Test Commands

```bash
./gradlew build          # Compile and run all tests
./gradlew test           # Run tests only
./gradlew clean          # Delete build directory
./gradlew clean build    # Full clean rebuild
```

Run a single test class:
```bash
./gradlew test --tests "com.namirial.trust.electronics.cpu.CPUTest"
```

**Runtime:** JDK 23, Kotlin 2.3.20, kotlinx-coroutines 1.10.2.

## Architecture Overview

This is a digital electronics simulator built in Kotlin — components compose hierarchically from basic gates up to a complete SAP-1 CPU. All circuit state is modeled through a signal abstraction.

### Signal Model (`core/Signal.kt`)

Every component connects via signals:
- `Signal` (interface) — read-only boolean
- `Input` — mutable signal; used for circuit inputs the caller drives
- `Output(evaluate: () -> Boolean)` — lazily computed signal; wires component outputs to inputs
- `LatchSignal` — mutable signal used for flip-flop state (changes only on `clock()`)
- `InputByte` / `OutputByte` / `InputAddress` — value-class wrappers for multi-bit busses

The lazy `Output` pattern means circuits evaluate on-demand: changing an `Input` propagates through any chain of `Output`s automatically.

### Package Layers

```
gate/           – 7 basic logic gates (NOT, AND, OR, NAND, NOR, XOR, XNOR)
combinational/  – Multi-bit combinational logic: adders, ALU, mux, demux, encoders, decoders
sequential/     – Stateful components: flip-flops (SR/D/JK/T), counters, registers, RAM
clock/          – ManualClock (for tests) and TimedClock (coroutine-based auto-tick)
cpu/            – SAP-1 8-bit microprocessor wired from all the above
```

Each layer composes from the one below it: the ALU chains FullAdders; the CPU uses ALU + Register + RAM + counters.

### Sequential / Clock Pattern

All stateful components implement `Clockable` (exposes `q`, `qNot`, `clock()`). Clock objects hold a list of registered `Clockable`s and drive them:

- `ManualClock` — call `tick()` explicitly; used in tests for deterministic stepping.
- `TimedClock(period)` — launches a coroutine that ticks at the given interval.

### RAM Implementations

There are two RAM implementations with the same `Memory` interface:
- `GateRAM` — gate-level, 16×8 bits, educational (wired with Decoder2to4 + Register trees + mux trees).
- `RAM(addressBits)` — behavioral, backed by `ByteArray`, scales to 64 KB.
- `ByteAddressedRAM` — type-safe wrapper around `RAM` using `InputAddress`/`InputByte`/`OutputByte`.

### CPU (`cpu/CPU.kt`)

SAP-1 microprocessor: 4-bit address space (16 words), 8-bit data. Key methods:
- `loadProgram(program: List<Int>)` — load up to 16 bytes
- `step(): Boolean` — execute one instruction; returns `false` when halted
- `run(maxCycles: Int)` — run until `HLT` or cycle limit
- `peekA()`, `peekPC()`, `peekFlags()` — debugging

Instruction set: `NOP LDA ADD SUB STA LDI JMP JC JZ OUT HLT` (opcodes 0x0–0xF, upper nibble = opcode, lower = operand/address).
