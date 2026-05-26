package com.namirial.trust.electronics.combinational

import com.namirial.trust.electronics.core.Input
import kotlin.test.*

class CombinationalTest {

    @Test fun `HalfAdder truth table`() {
        val a = Input(); val b = Input()
        val ha = HalfAdder(a, b)
        for ((va, vb, expSum, expCarry) in listOf(
            listOf(false, false, false, false),
            listOf(false, true, true, false),
            listOf(true, false, true, false),
            listOf(true, true, false, true)
        )) {
            a.value = va as Boolean; b.value = vb as Boolean
            assertEquals(expSum, ha.sum.value, "sum($va,$vb)")
            assertEquals(expCarry, ha.carryOut.value, "carry($va,$vb)")
        }
    }

    @Test fun `FullAdder truth table`() {
        val a = Input(); val b = Input(); val cIn = Input()
        val fa = FullAdder(a, b, cIn)
        val cases = listOf(
            Triple(false, false, false) to (false to false),
            Triple(false, false, true) to (true to false),
            Triple(false, true, false) to (true to false),
            Triple(false, true, true) to (false to true),
            Triple(true, false, false) to (true to false),
            Triple(true, false, true) to (false to true),
            Triple(true, true, false) to (false to true),
            Triple(true, true, true) to (true to true),
        )
        for ((inputs, expected) in cases) {
            a.value = inputs.first; b.value = inputs.second; cIn.value = inputs.third
            assertEquals(expected.first, fa.sum.value, "sum$inputs")
            assertEquals(expected.second, fa.carryOut.value, "carry$inputs")
        }
    }

    @Test fun `Multiplexer selects i0 when sel is false`() {
        val i0 = Input(true); val i1 = Input(false); val sel = Input(false)
        assertTrue(Multiplexer(i0, i1, sel).output.value)
    }

    @Test fun `Multiplexer selects i1 when sel is true`() {
        val i0 = Input(false); val i1 = Input(true); val sel = Input(true)
        assertTrue(Multiplexer(i0, i1, sel).output.value)
    }

    @Test fun `Demultiplexer routes to output0 when sel is false`() {
        val input = Input(true); val sel = Input(false)
        val demux = Demultiplexer(input, sel)
        assertTrue(demux.output0.value)
        assertFalse(demux.output1.value)
    }

    @Test fun `Demultiplexer routes to output1 when sel is true`() {
        val input = Input(true); val sel = Input(true)
        val demux = Demultiplexer(input, sel)
        assertFalse(demux.output0.value)
        assertTrue(demux.output1.value)
    }

    @Test fun `Encoder4to2 encodes one-hot inputs`() {
        val inputs = List(4) { Input() }
        val enc = Encoder4to2(inputs[0], inputs[1], inputs[2], inputs[3])
        val expected = listOf(
            0 to (false to false),
            1 to (true to false),
            2 to (false to true),
            3 to (true to true),
        )
        for ((active, exp) in expected) {
            inputs.forEach { it.value = false }
            inputs[active].value = true
            assertEquals(exp.first, enc.output0.value, "out0 for i$active")
            assertEquals(exp.second, enc.output1.value, "out1 for i$active")
        }
    }

    @Test fun `PriorityEncoder4to2 selects highest active input`() {
        val inputs = List(4) { Input() }
        val enc = PriorityEncoder4to2(inputs[0], inputs[1], inputs[2], inputs[3])

        inputs.forEach { it.value = false }
        assertFalse(enc.valid.value)

        // i3 has priority even when i1 is also active
        inputs[1].value = true; inputs[3].value = true
        assertTrue(enc.valid.value)
        assertTrue(enc.output0.value)
        assertTrue(enc.output1.value)

        // only i2 active
        inputs.forEach { it.value = false }
        inputs[2].value = true
        assertFalse(enc.output0.value)
        assertTrue(enc.output1.value)
    }

    @Test fun `Decoder2to4 activates correct output`() {
        val a = Input(); val b = Input()
        val dec = Decoder2to4(a, b)
        val outputs = listOf(dec.output0, dec.output1, dec.output2, dec.output3)
        val cases = listOf(
            (false to false) to 0,
            (true to false) to 1,
            (false to true) to 2,
            (true to true) to 3,
        )
        for ((input, activeIdx) in cases) {
            a.value = input.first; b.value = input.second
            outputs.forEachIndexed { i, out ->
                assertEquals(i == activeIdx, out.value, "output$i for a=${input.first},b=${input.second}")
            }
        }
    }

    @Test fun `Encoder into Decoder round-trips`() {
        val inputs = List(4) { Input() }
        val enc = Encoder4to2(inputs[0], inputs[1], inputs[2], inputs[3])
        val dec = Decoder2to4(enc.output0, enc.output1)
        val outputs = listOf(dec.output0, dec.output1, dec.output2, dec.output3)

        for (active in 0..3) {
            inputs.forEach { it.value = false }
            inputs[active].value = true
            outputs.forEachIndexed { i, out ->
                assertEquals(i == active, out.value, "round-trip i$active -> output$i")
            }
        }
    }
}
