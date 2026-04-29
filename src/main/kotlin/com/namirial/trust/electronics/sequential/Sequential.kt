package com.namirial.trust.electronics.sequential

import com.namirial.trust.electronics.core.*

interface Clockable {
    val q: Signal
    val qNot: Signal
    fun clock()
}

/**
 * SR (Set-Reset) Flip-Flop.
 *
 * Two inputs: [set] and [reset].
 * - S=1, R=0 → Q becomes 1 (set)
 * - S=0, R=1 → Q becomes 0 (reset)
 * - S=0, R=0 → Q holds its previous value
 * - S=1, R=1 → Invalid state (throws an error)
 */
class SRFlipFlop(val set: Signal, val reset: Signal) : Clockable {
    private val state = LatchSignal()
    override val q: Signal = state
    override val qNot: Signal = Output { !state.value }

    override fun clock() {
        when {
            set.value && reset.value -> error("SR flip-flop: S and R cannot both be high")
            set.value -> state.value = true
            reset.value -> state.value = false
        }
    }
}

/**
 * D (Data) Flip-Flop.
 *
 * Single input: [d].
 * On each clock edge, Q takes the value of D.
 * The simplest and most commonly used flip-flop, used for registers and data storage.
 */
class DFlipFlop(val d: Signal) : Clockable {
    private val state = LatchSignal()
    override val q: Signal = state
    override val qNot: Signal = Output { !state.value }

    override fun clock() {
        state.value = d.value
    }
}

/**
 * JK Flip-Flop.
 *
 * Two inputs: [j] and [k].
 * - J=0, K=0 → Q holds its previous value (hold)
 * - J=1, K=0 → Q becomes 1 (set)
 * - J=0, K=1 → Q becomes 0 (reset)
 * - J=1, K=1 → Q toggles (inverts)
 *
 * Considered the universal flip-flop since it combines SR behavior
 * with a toggle mode, and has no invalid input combination.
 */
class JKFlipFlop(val j: Signal, val k: Signal) : Clockable {
    private val state = LatchSignal()
    override val q: Signal = state
    override val qNot: Signal = Output { !state.value }

    override fun clock() {
        state.value = when {
            j.value && k.value -> !state.value
            j.value -> true
            k.value -> false
            else -> state.value
        }
    }
}

/**
 * T (Toggle) Flip-Flop.
 *
 * Single input: [t].
 * - T=1 → Q toggles (inverts) on each clock edge
 * - T=0 → Q holds its previous value
 *
 * Commonly used in counters and frequency dividers.
 */
class TFlipFlop(val t: Signal) : Clockable {
    private val state = LatchSignal()
    override val q: Signal = state
    override val qNot: Signal = Output { !state.value }

    override fun clock() {
        if (t.value) state.value = !state.value
    }
}

interface Memory : Clockable {
    // this is the address selected
    val address: List<Signal>
    // this is the data to be written
    val dataIn: List<Signal>
    // protect acidental writes
    val writeEnable: Signal
    // data at "address" selected
    val dataOut: List<Signal>
}

interface Countable : Clockable {
    val bits: Int
    val count: Int
    fun bit(index: Int): Signal
}

/**
 * N-bit binary ripple up-counter.
 *
 * Built from chained [TFlipFlop]s. The first flip-flop toggles every clock cycle,
 * each subsequent flip-flop toggles when the previous one transitions from 1 to 0.
 * Counts from 0 to 2^[bits]-1, then wraps around.
 */
class UpCounter(override val bits: Int) : Countable {
    private val alwaysHigh = Input(true)
    private val flipFlops = List(bits) { TFlipFlop(alwaysHigh) }

    override val q: Signal = flipFlops.last().q
    override val qNot: Signal = flipFlops.last().qNot

    override val count: Int get() = flipFlops.foldIndexed(0) { i, acc, ff ->
        acc or (if (ff.q.value) (1 shl i) else 0)
    }

    override fun bit(index: Int): Signal = flipFlops[index].q

    override fun clock() {
        val prev = BooleanArray(bits) { flipFlops[it].q.value }
        flipFlops[0].clock()
        for (i in 1 until bits) {
            if (prev[i - 1] && !flipFlops[i - 1].q.value) flipFlops[i].clock()
        }
    }
}

/**
 * N-bit binary ripple down-counter.
 *
 * Built from chained [TFlipFlop]s. The first flip-flop toggles every clock cycle,
 * each subsequent flip-flop toggles when the previous one transitions from 0 to 1.
 * Counts from 2^[bits]-1 down to 0, then wraps around.
 */
class DownCounter(override val bits: Int) : Countable {
    private val alwaysHigh = Input(true)
    private val flipFlops = List(bits) { TFlipFlop(alwaysHigh) }

    override val q: Signal = flipFlops.last().q
    override val qNot: Signal = flipFlops.last().qNot

    override val count: Int get() = flipFlops.foldIndexed(0) { i, acc, ff ->
        acc or (if (ff.q.value) (1 shl i) else 0)
    }

    override fun bit(index: Int): Signal = flipFlops[index].q

    override fun clock() {
        val prev = BooleanArray(bits) { flipFlops[it].q.value }
        flipFlops[0].clock()
        for (i in 1 until bits) {
            if (!prev[i - 1] && flipFlops[i - 1].q.value) flipFlops[i].clock()
        }
    }
}

/**
 * N-bit parallel register.
 *
 * Built from [DFlipFlop]s. On each clock edge, if [enable] is high,
 * the register captures the current values of all [inputs].
 * When [enable] is low, the stored value is held.
 */
class Register(val inputs: List<Signal>, val enable: Signal) : Clockable {
    private val flipFlops = inputs.map { DFlipFlop(it) }

    override val q: Signal = flipFlops.last().q
    override val qNot: Signal = flipFlops.last().qNot

    fun bit(index: Int): Signal = flipFlops[index].q

    val value: Int get() = flipFlops.foldIndexed(0) { i, acc, ff ->
        acc or (if (ff.q.value) (1 shl i) else 0)
    }

    override fun clock() {
        if (enable.value) flipFlops.forEach { it.clock() }
    }
}
