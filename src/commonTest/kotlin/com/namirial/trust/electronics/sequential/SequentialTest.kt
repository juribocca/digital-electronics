package com.namirial.trust.electronics.sequential

import com.namirial.trust.electronics.core.Input
import kotlin.test.*

class SequentialTest {

    // --- SR Flip-Flop ---

    @Test fun `SRFlipFlop starts at false`() {
        val ff = SRFlipFlop(Input(), Input())
        assertFalse(ff.q.value)
        assertTrue(ff.qNot.value)
    }

    @Test fun `SRFlipFlop set`() {
        val s = Input(); val r = Input()
        val ff = SRFlipFlop(s, r)
        s.value = true; ff.clock()
        assertTrue(ff.q.value)
        assertFalse(ff.qNot.value)
    }

    @Test fun `SRFlipFlop reset`() {
        val s = Input(true); val r = Input()
        val ff = SRFlipFlop(s, r)
        ff.clock() // set
        s.value = false; r.value = true; ff.clock()
        assertFalse(ff.q.value)
    }

    @Test fun `SRFlipFlop hold`() {
        val s = Input(true); val r = Input()
        val ff = SRFlipFlop(s, r)
        ff.clock() // set
        s.value = false; ff.clock() // hold
        assertTrue(ff.q.value)
    }

    @Test fun `SRFlipFlop invalid state throws`() {
        val ff = SRFlipFlop(Input(true), Input(true))
        assertFailsWith<IllegalStateException> { ff.clock() }
    }

    // --- D Flip-Flop ---

    @Test fun `DFlipFlop captures data on clock`() {
        val d = Input(true)
        val ff = DFlipFlop(d)
        assertFalse(ff.q.value)
        ff.clock()
        assertTrue(ff.q.value)
        d.value = false; ff.clock()
        assertFalse(ff.q.value)
    }

    // --- JK Flip-Flop ---

    @Test fun `JKFlipFlop set`() {
        val j = Input(true); val k = Input()
        val ff = JKFlipFlop(j, k)
        ff.clock()
        assertTrue(ff.q.value)
    }

    @Test fun `JKFlipFlop reset`() {
        val j = Input(true); val k = Input()
        val ff = JKFlipFlop(j, k)
        ff.clock() // set
        j.value = false; k.value = true; ff.clock()
        assertFalse(ff.q.value)
    }

    @Test fun `JKFlipFlop toggle`() {
        val j = Input(true); val k = Input(true)
        val ff = JKFlipFlop(j, k)
        ff.clock() // false -> true
        assertTrue(ff.q.value)
        ff.clock() // true -> false
        assertFalse(ff.q.value)
    }

    @Test fun `JKFlipFlop hold`() {
        val j = Input(true); val k = Input()
        val ff = JKFlipFlop(j, k)
        ff.clock() // set
        j.value = false; ff.clock() // hold
        assertTrue(ff.q.value)
    }

    // --- T Flip-Flop ---

    @Test fun `TFlipFlop toggles when high`() {
        val t = Input(true)
        val ff = TFlipFlop(t)
        ff.clock()
        assertTrue(ff.q.value)
        ff.clock()
        assertFalse(ff.q.value)
    }

    @Test fun `TFlipFlop holds when low`() {
        val t = Input()
        val ff = TFlipFlop(t)
        ff.clock()
        assertFalse(ff.q.value)
    }

    // --- UpCounter ---

    @Test fun `UpCounter counts 0 to 15 and wraps`() {
        val counter = UpCounter(4)
        for (expected in 0..15) {
            assertEquals(expected, counter.count, "cycle $expected")
            counter.clock()
        }
        assertEquals(0, counter.count, "wrap")
    }

    @Test fun `UpCounter bit access`() {
        val counter = UpCounter(4)
        repeat(5) { counter.clock() } // count = 5 = 0101
        assertTrue(counter.bit(0).value)
        assertFalse(counter.bit(1).value)
        assertTrue(counter.bit(2).value)
        assertFalse(counter.bit(3).value)
    }

    // --- DownCounter ---

    @Test fun `DownCounter counts down from 15 to 0 and wraps`() {
        val counter = DownCounter(4)
        assertEquals(0, counter.count)
        counter.clock() // wraps to 15
        assertEquals(15, counter.count)
        counter.clock()
        assertEquals(14, counter.count)
    }

    // --- Register ---

    @Test fun `Register captures inputs when enabled`() {
        val inputs = listOf(Input(true), Input(false), Input(true), Input(true))
        val enable = Input(true)
        val reg = Register(inputs, enable)
        reg.clock()
        assertEquals(0b1101, reg.value) // bits: 1,0,1,1 = 13
    }

    @Test fun `Register holds when disabled`() {
        val inputs = listOf(Input(true), Input(true))
        val enable = Input(true)
        val reg = Register(inputs, enable)
        reg.clock() // capture 11
        assertEquals(3, reg.value)
        enable.value = false
        inputs[0].value = false; inputs[1].value = false
        reg.clock() // should hold
        assertEquals(3, reg.value)
    }

    @Test fun `Register bit access`() {
        val inputs = listOf(Input(true), Input(false))
        val reg = Register(inputs, Input(true))
        reg.clock()
        assertTrue(reg.bit(0).value)
        assertFalse(reg.bit(1).value)
    }
}
