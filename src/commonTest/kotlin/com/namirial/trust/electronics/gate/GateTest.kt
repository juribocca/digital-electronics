package com.namirial.trust.electronics.gate

import com.namirial.trust.electronics.core.Input
import kotlin.test.*

class GateTest {

    private fun binary(gate: BinaryGate, a: Boolean, b: Boolean): Boolean {
        (gate.a as Input).value = a
        (gate.b as Input).value = b
        return gate.output.value
    }

    private fun assertTruthTable(gate: BinaryGate, expected: List<Boolean>) {
        val inputs = listOf(false to false, false to true, true to false, true to true)
        inputs.zip(expected).forEach { (input, exp) ->
            assertEquals(exp, binary(gate, input.first, input.second),
                "${gate::class.simpleName}(${input.first}, ${input.second})")
        }
    }

    @Test fun `NotGate inverts input`() {
        val i = Input()
        val gate = NotGate(i)
        assertTrue(gate.output.value)
        i.value = true
        assertFalse(gate.output.value)
    }

    @Test fun `AndGate truth table`() =
        assertTruthTable(AndGate(Input(), Input()), listOf(false, false, false, true))

    @Test fun `OrGate truth table`() =
        assertTruthTable(OrGate(Input(), Input()), listOf(false, true, true, true))

    @Test fun `NandGate truth table`() =
        assertTruthTable(NandGate(Input(), Input()), listOf(true, true, true, false))

    @Test fun `NorGate truth table`() =
        assertTruthTable(NorGate(Input(), Input()), listOf(true, false, false, false))

    @Test fun `XorGate truth table`() =
        assertTruthTable(XorGate(Input(), Input()), listOf(false, true, true, false))

    @Test fun `XnorGate truth table`() =
        assertTruthTable(XnorGate(Input(), Input()), listOf(true, false, false, true))
}
